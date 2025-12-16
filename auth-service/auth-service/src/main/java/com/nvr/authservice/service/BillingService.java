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

    @Value("${app.billing.public-base-url:https://pay.okodoma.ru}")
    private String publicBaseUrl;

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
                .orderId(orderId) // Сохраняем orderId отдельно для поиска
                .providerSessionId(orderId) // временно, потом обновим на PaymentId
                .build();

        paymentAttemptRepo.save(attempt);

        // Создаем описание платежа
        String description = String.format("Подписка %s (%s)", plan.getTitle(), planCode);

        // Формируем URL для редиректа после оплаты
        // Tinkoff поддерживает подстановку {PaymentId} в URL, используем его для надежности
        // Также передаем orderId для fallback
        String successUrl = publicBaseUrl + "/billing/redirect/success?paymentId={PaymentId}&orderId=" + orderId;
        String failUrl = publicBaseUrl + "/billing/redirect/fail?paymentId={PaymentId}&orderId=" + orderId;

        // Вызываем API Тинькофф для создания платежа
        TinkoffApiClient.TinkoffInitResponse response = tinkoffApiClient.initPayment(
                amountMinor,
                orderId,
                successUrl,
                failUrl,
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
        log.info("handleSuccessfulPayment called: PaymentId={}, OrderId={}", paymentId, orderId);
        
        // Находим попытку платежа по orderId или paymentId
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionId(paymentId)
                .orElseGet(() -> {
                    if (orderId != null) {
                        return paymentAttemptRepo.findByOrderId(orderId)
                                .orElseThrow(() -> new IllegalArgumentException("Payment attempt not found for PaymentId=" + paymentId + ", OrderId=" + orderId));
                    }
                    throw new IllegalArgumentException("Payment attempt not found for PaymentId=" + paymentId);
                });

        log.info("Found payment attempt in handleSuccessfulPayment: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}", 
                attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(), 
                attempt.getPlanCode(), attempt.getUser().getId());

        if (!"PENDING".equals(attempt.getStatus())) {
            log.warn("Payment attempt {} already processed with status {}", attempt.getId(), attempt.getStatus());
            return;
        }

        // Проверяем статус в Тинькофф
        log.info("Checking payment status in handleSuccessfulPayment with PaymentId={}", paymentId);
        TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
        log.info("Payment status from Tinkoff in handleSuccessfulPayment: Status={}, Success={}", state.status(), state.success());
        
        // Для тестовых платежей Тинькофф может возвращать AUTHORIZED вместо CONFIRMED
        if (!"CONFIRMED".equals(state.status()) && !"AUTHORIZED".equals(state.status())) {
            log.warn("Payment {} status is not CONFIRMED or AUTHORIZED: {}", paymentId, state.status());
            attempt.setStatus("FAILED");
            paymentAttemptRepo.save(attempt);
            return;
        }

        // Обновляем статус попытки платежа
        attempt.setStatus("SUCCESS");
        paymentAttemptRepo.save(attempt);
        log.info("Updated payment attempt status to SUCCESS: AttemptId={}", attempt.getId());

        // Создаем подписку
        SubscriptionPlan plan = planRepo.findByCode(attempt.getPlanCode())
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + attempt.getPlanCode()));

        Instant now = Instant.now();
        Instant endsAt = now.plus(30, ChronoUnit.DAYS); // подписка на 30 дней

        log.info("Creating subscription: UserId={}, PlanCode={}, StartsAt={}, EndsAt={}", 
                attempt.getUser().getId(), plan.getCode(), now, endsAt);

        UserSubscription subscription = UserSubscription.builder()
                .user(attempt.getUser())
                .plan(plan)
                .startsAt(now)
                .endsAt(endsAt)
                .active(true)
                .build();

        subscriptionRepo.save(subscription);
        log.info("Subscription saved: SubscriptionId={}, UserId={}, PlanCode={}, Active={}, EndsAt={}", 
                subscription.getId(), attempt.getUser().getId(), plan.getCode(), subscription.isActive(), subscription.getEndsAt());

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
                .orElseGet(() -> orderId != null ? paymentAttemptRepo.findByOrderId(orderId).orElse(null) : null);

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
     * Автоматически обрабатывает платеж по orderId или paymentId.
     * Используется как fallback на странице редиректа, когда webhook не приходит.
     * Безопасно вызывать несколько раз - проверяет статус PENDING.
     *
     * @param orderId номер заказа (может быть null)
     * @param paymentId идентификатор платежа в системе Тинькофф (может быть null)
     * @return true, если платеж был успешно обработан, false если уже обработан или не найден
     */
    @Transactional
    public boolean tryProcessPayment(String orderId, String paymentId) {
        try {
            log.info("tryProcessPayment called: PaymentId={}, OrderId={}", paymentId, orderId);
            
            // Определяем, какой идентификатор использовать
            String identifier = paymentId != null ? paymentId : orderId;
            if (identifier == null) {
                log.warn("Both orderId and paymentId are null, cannot process payment");
                return false;
            }

            // Пытаемся найти платеж: сначала по paymentId, потом по orderId
            PaymentAttempt attempt = null;
            if (paymentId != null) {
                attempt = paymentAttemptRepo.findByProviderSessionId(paymentId).orElse(null);
                log.debug("Searching by paymentId={}, found: {}", paymentId, attempt != null);
            }
            if (attempt == null && orderId != null) {
                attempt = paymentAttemptRepo.findByOrderId(orderId).orElse(null);
                log.debug("Searching by orderId={}, found: {}", orderId, attempt != null);
            }

            if (attempt == null) {
                log.warn("Payment attempt not found for PaymentId={}, OrderId={}", paymentId, orderId);
                return false;
            }

            log.info("Found payment attempt: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}", 
                    attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(), 
                    attempt.getPlanCode(), attempt.getUser().getId());

            // Если уже обработан, возвращаем true (успешно обработан ранее)
            if (!"PENDING".equals(attempt.getStatus())) {
                log.info("Payment {} already processed with status {}", identifier, attempt.getStatus());
                return true;
            }

            // Определяем paymentId для проверки статуса
            // providerSessionId должен содержать paymentId от Тинькофф (обновляется после создания платежа)
            String paymentIdForCheck = attempt.getProviderSessionId();
            
            // Если providerSessionId это orderId (начинается с ORDER_), значит paymentId еще не был сохранен
            // Используем переданный paymentId, если он есть
            if (paymentIdForCheck == null || paymentIdForCheck.startsWith("ORDER_")) {
                log.warn("PaymentId not yet saved in attempt (providerSessionId={}), trying to use provided paymentId={}", 
                        paymentIdForCheck, paymentId);
                if (paymentId != null && !paymentId.startsWith("ORDER_")) {
                    paymentIdForCheck = paymentId;
                    log.info("Using provided paymentId={} for status check", paymentIdForCheck);
                } else {
                    log.warn("Cannot check payment status: PaymentId not available (providerSessionId={}, provided paymentId={})", 
                            paymentIdForCheck, paymentId);
                    return false;
                }
            } else {
                // paymentId уже сохранен в БД, используем его
                log.info("Using paymentId from DB: {}", paymentIdForCheck);
            }

            log.info("Checking payment status with PaymentId={}", paymentIdForCheck);
            
            // Проверяем статус в Тинькофф
            TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentIdForCheck);
            log.info("Payment status from Tinkoff: Status={}, Success={}", state.status(), state.success());
            
            // Для тестовых платежей Тинькофф может возвращать AUTHORIZED вместо CONFIRMED
            if (!"CONFIRMED".equals(state.status()) && !"AUTHORIZED".equals(state.status())) {
                log.warn("Payment {} status is not CONFIRMED or AUTHORIZED: {}", paymentIdForCheck, state.status());
                return false;
            }

            // Обрабатываем успешный платеж (создает подписку)
            log.info("Calling handleSuccessfulPayment with PaymentId={}, OrderId={}", paymentIdForCheck, orderId);
            handleSuccessfulPayment(paymentIdForCheck, orderId);
            log.info("Successfully processed payment from redirect page: PaymentId={}, OrderId={}", paymentIdForCheck, orderId);
            return true;
        } catch (Exception e) {
            log.error("Error processing payment from redirect page: PaymentId={}, OrderId={}, Error={}", 
                    paymentId, orderId, e.getMessage(), e);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Подтверждает платеж и активирует подписку без webhook.
     * Используется как fallback, когда webhook не приходит.
     *
     * @param userId ID пользователя (для проверки владельца платежа)
     * @param paymentId идентификатор платежа в системе Тинькофф
     */
    @Transactional
    public void confirmPayment(Long userId, String paymentId) {
        // Находим попытку платежа по paymentId
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment attempt not found: " + paymentId));

        // Проверяем, что платеж принадлежит пользователю
        if (!attempt.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Payment does not belong to user");
        }

        // Если уже обработан, просто возвращаемся
        if (!"PENDING".equals(attempt.getStatus())) {
            log.info("Payment {} already processed with status {}", paymentId, attempt.getStatus());
            return;
        }

        // Проверяем статус в Тинькофф
        TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
        // Для тестовых платежей Тинькофф может возвращать AUTHORIZED вместо CONFIRMED
        if (!"CONFIRMED".equals(state.status()) && !"AUTHORIZED".equals(state.status())) {
            throw new IllegalArgumentException("Payment status is not CONFIRMED or AUTHORIZED: " + state.status());
        }

        // Обрабатываем успешный платеж (создает подписку)
        handleSuccessfulPayment(paymentId, null);
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


