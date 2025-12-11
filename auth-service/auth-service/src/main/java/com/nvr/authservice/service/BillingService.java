package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.PaymentAttempt;
import com.nvr.authservice.domain.SubscriptionPlan;
import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.PaymentAttemptRepository;
import com.nvr.authservice.repo.SubscriptionPlanRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final AppUserRepository userRepo;
    private final PaymentAttemptRepository paymentAttemptRepo;
    private final TinkoffApiClient tinkoffApiClient;
    private final SubscriptionPlanRepository planRepo;
    private final UserSubscriptionRepository subscriptionRepo;

    @Value("${app.billing.success-url:https://example.com/payment/success}")
    private String successUrl;

    @Value("${app.billing.fail-url:https://example.com/payment/fail}")
    private String failUrl;

    /**
     * Получает список доступных планов для покупки.
     *
     * @return список планов с ценами
     */
    public List<PlanInfo> getAvailablePlans() {
        return planRepo.findAll().stream()
                .filter(SubscriptionPlan::isAddon)
                .map(plan -> new PlanInfo(
                        plan.getCode(),
                        plan.getTitle(),
                        plan.getPriceMinor(),
                        plan.getCurrency(),
                        plan.getCameraQuota(),
                        plan.getArchiveDays()
                ))
                .toList();
    }

    /**
     * Создает платёжную сессию в Тинькофф для покупки подписки.
     * Цена берется из БД по planCode - безопасно!
     *
     * @param userId ID пользователя
     * @param planCode код плана (CAM_1 или CAM_3)
     * @return платёжная сессия с URL для редиректа
     */
    @Transactional
    public BillingSession createSession(Long userId, String planCode) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Проверяем, что план существует
        SubscriptionPlan plan = planRepo.findByCode(planCode)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planCode));

        if (!plan.isAddon()) {
            throw new IllegalArgumentException("Only addon plans (CAM_1, CAM_3) can be purchased: " + planCode);
        }

        if (plan.getPriceMinor() == null || plan.getPriceMinor() <= 0) {
            throw new IllegalStateException("Plan " + planCode + " has no price configured");
        }

        if (plan.getCurrency() == null || plan.getCurrency().isBlank()) {
            throw new IllegalStateException("Plan " + planCode + " has no currency configured");
        }

        // Берем цену из БД - безопасно!
        Long amountMinor = plan.getPriceMinor();
        String currency = plan.getCurrency();

        // Генерируем уникальный номер заказа
        String orderId = "ORDER_" + userId + "_" + System.currentTimeMillis();

        // Создаем запись о попытке платежа
        PaymentAttempt attempt = PaymentAttempt.builder()
                .user(user)
                .amountMinor(amountMinor)
                .currency(currency)
                .planCode(planCode)
                .status("PENDING")
                .provider("TINKOFF")
                .providerSessionId(orderId) // временно, потом обновим на PaymentId
                .build();

        paymentAttemptRepo.save(attempt);

        // Создаем описание платежа
        String description = String.format("Подписка %s (%s)", plan.getTitle(), planCode);

        // Вызываем API Тинькофф для создания платежа
        TinkoffApiClient.TinkoffInitResponse response = tinkoffApiClient.initPayment(
                amountMinor,
                orderId,
                successUrl + "?orderId=" + orderId,
                failUrl + "?orderId=" + orderId,
                description
        );

        // Обновляем запись о попытке платежа с PaymentId от Тинькофф
        attempt.setProviderSessionId(response.paymentId());
        paymentAttemptRepo.save(attempt);

        log.info("Created Tinkoff payment session: PaymentId={}, OrderId={}, UserId={}, Plan={}, Amount={} {}",
                response.paymentId(), orderId, userId, planCode, amountMinor, currency);

        return new BillingSession(response.paymentId(), response.paymentUrl());
    }

    /**
     * Обрабатывает успешный платеж и создает подписку.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @param orderId номер заказа
     */
    @Transactional
    public void handleSuccessfulPayment(String paymentId, String orderId) {
        // Находим попытку платежа по orderId или paymentId
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionId(paymentId)
                .orElseGet(() -> paymentAttemptRepo.findByProviderSessionId(orderId)
                        .orElseThrow(() -> new IllegalArgumentException("Payment attempt not found: " + paymentId)));

        if (!"PENDING".equals(attempt.getStatus())) {
            log.warn("Payment attempt {} already processed with status {}", attempt.getId(), attempt.getStatus());
            return;
        }

        // Проверяем статус в Тинькофф
        TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
        if (!"CONFIRMED".equals(state.status())) {
            log.warn("Payment {} status is not CONFIRMED: {}", paymentId, state.status());
            attempt.setStatus("FAILED");
            paymentAttemptRepo.save(attempt);
            return;
        }

        // Обновляем статус попытки платежа
        attempt.setStatus("SUCCESS");
        paymentAttemptRepo.save(attempt);

        // Создаем подписку
        SubscriptionPlan plan = planRepo.findByCode(attempt.getPlanCode())
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + attempt.getPlanCode()));

        Instant now = Instant.now();
        Instant endsAt = now.plus(30, ChronoUnit.DAYS); // подписка на 30 дней

        UserSubscription subscription = UserSubscription.builder()
                .user(attempt.getUser())
                .plan(plan)
                .startsAt(now)
                .endsAt(endsAt)
                .active(true)
                .build();

        subscriptionRepo.save(subscription);

        log.info("Created subscription for user {}: Plan={}, PaymentId={}, SubscriptionId={}",
                attempt.getUser().getId(), plan.getCode(), paymentId, subscription.getId());
    }

    /**
     * Обрабатывает неуспешный платеж.
     *
     * @param paymentId идентификатор платежа
     * @param orderId номер заказа
     */
    @Transactional
    public void handleFailedPayment(String paymentId, String orderId) {
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionId(paymentId)
                .orElseGet(() -> paymentAttemptRepo.findByProviderSessionId(orderId)
                        .orElse(null));

        if (attempt == null) {
            log.warn("Payment attempt not found for PaymentId={}, OrderId={}", paymentId, orderId);
            return;
        }

        // Не обновляем статус, если уже обработан
        if (!"PENDING".equals(attempt.getStatus())) {
            log.warn("Payment attempt {} already processed with status {}", attempt.getId(), attempt.getStatus());
            return;
        }

        attempt.setStatus("FAILED");
        paymentAttemptRepo.save(attempt);

        log.info("Marked payment as failed: PaymentId={}, OrderId={}", paymentId, orderId);
    }

    /**
     * DTO результата создания платёжной сессии.
     */
    public record BillingSession(
            String sessionId,  // PaymentId от Тинькофф
            String redirectUrl // URL для редиректа на платёжную форму
    ) {
    }

    /**
     * DTO информации о плане подписки для фронтенда.
     */
    public record PlanInfo(
            String code,
            String title,
            Long priceMinor,
            String currency,
            Integer cameraQuota,
            Integer archiveDays
    ) {
    }
}


