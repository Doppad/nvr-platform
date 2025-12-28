package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.PaymentAttempt;
import com.nvr.authservice.domain.PaymentAttemptCamera;
import com.nvr.authservice.domain.SubscriptionPlan;
import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.PaymentAttemptCameraRepository;
import com.nvr.authservice.repo.PaymentAttemptRepository;
import com.nvr.authservice.repo.SubscriptionPlanRepository;
import com.nvr.authservice.repo.UserSubscriptionCameraRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import com.nvr.authservice.subscription.UserSubscriptionCamera;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final AppUserRepository userRepo;
    private final PaymentAttemptRepository paymentAttemptRepo;
    private final PaymentAttemptCameraRepository paymentAttemptCameraRepo;
    private final TinkoffApiClient tinkoffApiClient;
    private final SubscriptionPlanRepository planRepo;
    private final UserSubscriptionRepository subscriptionRepo;
    private final UserSubscriptionCameraRepository userSubscriptionCameraRepo;
    private final NvrCameraValidationService cameraValidationService;

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
     * @param cameraIds список ID камер для подписки (обязателен)
     * @return платёжная сессия с URL для редиректа
     */
    @Transactional
    public BillingSession createSession(Long userId, String planCode, List<Long> cameraIds) {
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

        // Валидация cameraIds
        if (cameraIds == null || cameraIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cameraIds is required and cannot be empty"
            );
        }

        // Проверка на дубликаты
        Set<Long> uniqueIds = new HashSet<>(cameraIds);
        if (uniqueIds.size() != cameraIds.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cameraIds contains duplicates"
            );
        }

        // Проверка количества камер в соответствии с планом
        Integer expectedCount = plan.getCameraQuota();
        if (expectedCount == null) {
            throw new IllegalStateException("Plan " + planCode + " has no cameraQuota configured");
        }

        if (cameraIds.size() != expectedCount) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.format("Plan %s requires exactly %d camera(s), but %d provided", 
                            planCode, expectedCount, cameraIds.size())
            );
        }

        // Проверка принадлежности камер пользователю через nvr-service
        // Получаем addressId из пользователя для актуальной модели доступа
        Long userAddressId = user.getAddressId();
        cameraValidationService.validateCameraOwnership(userId, userAddressId, cameraIds);

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

        // Сохраняем выбранные камеры
        for (Long cameraId : cameraIds) {
            PaymentAttemptCamera paymentCamera = PaymentAttemptCamera.builder()
                    .paymentAttempt(attempt)
                    .cameraId(cameraId)
                    .build();
            paymentAttemptCameraRepo.save(paymentCamera);
        }

        log.info("Saved {} camera(s) for payment attempt: AttemptId={}, CameraIds={}", 
                cameraIds.size(), attempt.getId(), cameraIds);

        // Создаем описание платежа
        String description = String.format("Подписка %s (%s)", plan.getTitle(), planCode);

        // Формируем URL для редиректа после оплаты
        // Используем только orderId, так как Тинькофф не подставляет {PaymentId} в URL редиректа
        // PaymentId будет получен из БД при обработке редиректа
        String successUrl = publicBaseUrl + "/billing/redirect/success?orderId=" + orderId;
        String failUrl = publicBaseUrl + "/billing/redirect/fail?orderId=" + orderId;

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
     * Идемпотентный метод: повторные вызовы не создают дубликаты.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @param orderId номер заказа
     * @throws ResponseStatusException с HTTP статусами:
     *         - 404 NOT_FOUND если attempt не найден
     *         - 409 CONFLICT для неконсистентных данных (неверное количество камер, план не найден и т.д.)
     */
    @Transactional
    public void handleSuccessfulPayment(String paymentId, String orderId) {
        log.info("handleSuccessfulPayment called: PaymentId={}, OrderId={}", paymentId, orderId);
        
        // ИДЕМПОТЕНТНОСТЬ: находим attempt с блокировкой FOR UPDATE для предотвращения race conditions
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionIdForUpdate(paymentId)
                .orElseGet(() -> {
                    if (orderId != null) {
                        return paymentAttemptRepo.findByOrderIdForUpdate(orderId)
                                .orElseThrow(() -> {
                                    log.error("Payment attempt not found: PaymentId={}, OrderId={}", paymentId, orderId);
                                    return new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Payment attempt not found for PaymentId=" + paymentId + ", OrderId=" + orderId
                                    );
                                });
                    }
                    log.error("Payment attempt not found: PaymentId={}", paymentId);
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payment attempt not found for PaymentId=" + paymentId
                    );
                });

        log.info("Found payment attempt in handleSuccessfulPayment: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}", 
                attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(), 
                attempt.getPlanCode(), attempt.getUser().getId());

        // ИДЕМПОТЕНТНОСТЬ: если уже обработан - возвращаемся без побочных эффектов
        if (!"PENDING".equals(attempt.getStatus())) {
            log.info("Payment attempt {} already processed with status {}. Returning OK (idempotent).", 
                    attempt.getId(), attempt.getStatus());
            return; // 200 OK - идемпотентность
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

        // ИДЕМПОТЕНТНОСТЬ: атомарное обновление статуса PENDING -> SUCCESS
        // Если статус уже не PENDING (изменился между проверкой и обновлением) - метод вернет 0
        Long attemptId = attempt.getId(); // Сохраняем ID в final переменную для использования в lambda
        int updated = paymentAttemptRepo.updateStatusFromPendingToSuccess(attemptId);
        if (updated == 0) {
            // Статус уже был изменен другим потоком - идемпотентность
            log.info("Payment attempt {} status was already changed by another process. Returning OK (idempotent).", 
                    attemptId);
            return; // 200 OK - идемпотентность
        }
        
        // Перезагружаем attempt для получения актуального статуса
        attempt = paymentAttemptRepo.findById(attemptId)
                .orElseThrow(() -> {
                    log.error("Payment attempt {} not found after status update", attemptId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payment attempt not found after status update"
                    );
                });
        
        log.info("Updated payment attempt status to SUCCESS: AttemptId={}", attemptId);

        // Создаем подписку
        // Сохраняем значения в final переменные для использования в lambda
        final Long finalAttemptId = attempt.getId();
        final String planCode = attempt.getPlanCode();
        
        SubscriptionPlan plan = planRepo.findByCode(planCode)
                .orElseThrow(() -> {
                    log.error("Plan not found for payment attempt {}: PlanCode={}", finalAttemptId, planCode);
                    return new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Plan not found: " + planCode
                    );
                });

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

        // Получаем сохраненные cameraIds из PaymentAttempt
        List<PaymentAttemptCamera> paymentCameras = paymentAttemptCameraRepo.findByPaymentAttemptId(attempt.getId());
        List<Long> cameraIds = paymentCameras.stream()
                .map(PaymentAttemptCamera::getCameraId)
                .collect(Collectors.toList());

        log.info("Found {} camera(s) for payment attempt: CameraIds={}", cameraIds.size(), cameraIds);

        // СТРОГАЯ ВАЛИДАЦИЯ: проверяем размер списка камер в соответствии с планом
        Integer expectedCameraCount = plan.getCameraQuota();
        if (expectedCameraCount == null) {
            log.error("Plan {} has no cameraQuota configured. Cannot validate camera count. PaymentAttemptId={}", 
                    plan.getCode(), attempt.getId());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Plan " + plan.getCode() + " has no cameraQuota configured"
            );
        }

        if (cameraIds.isEmpty()) {
            log.error("Payment attempt {} has no cameras saved. Cannot create subscription.", attempt.getId());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment attempt has no cameras saved. Cannot create subscription."
            );
        }

        if (cameraIds.size() != expectedCameraCount) {
            log.error("Payment attempt {} has incorrect camera count. Expected: {}, Actual: {}, CameraIds: {}", 
                    attempt.getId(), expectedCameraCount, cameraIds.size(), cameraIds);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    String.format("Payment attempt has incorrect camera count. Plan %s requires exactly %d camera(s), but %d found in payment attempt", 
                            plan.getCode(), expectedCameraCount, cameraIds.size())
            );
        }

        // СТРОГАЯ ВАЛИДАЦИЯ: проверяем принадлежность камер пользователю (использует актуальную модель через addressId)
        // Работает в webhook-контексте без SecurityContext/JWT
        Long userAddressId = attempt.getUser().getAddressId();
        try {
            cameraValidationService.validateCameraOwnership(attempt.getUser().getId(), userAddressId, cameraIds);
            log.info("Camera ownership validated for user {} (addressId={}): CameraIds={}", 
                    attempt.getUser().getId(), userAddressId, cameraIds);
        } catch (ResponseStatusException e) {
            log.error("Camera ownership validation failed for user {} (addressId={}): CameraIds={}, Error: {}", 
                    attempt.getUser().getId(), userAddressId, cameraIds, e.getMessage());
            // Пробрасываем ResponseStatusException как есть (уже содержит правильный HTTP статус)
            throw e;
        }

        // ИДЕМПОТЕНТНОСТЬ уже проверена выше: если статус не PENDING, метод возвращается раньше
        // Дополнительная защита: проверяем, что подписка еще не была создана для этого платежа
        // (на случай, если статус был изменен вручную или есть race condition)

        // Проверяем, что у этого пользователя нет активных подписок на эти камеры
        for (Long cameraId : cameraIds) {
            // Проверяем активные подписки на эту камеру ДЛЯ ЭТОГО ПОЛЬЗОВАТЕЛЯ
            List<UserSubscriptionCamera> existingCameras = userSubscriptionCameraRepo
                    .findActiveByCameraIdAndUserId(cameraId, attempt.getUser().getId(), now);

            if (!existingCameras.isEmpty()) {
                String existingSubscriptionIds = existingCameras.stream()
                        .map(usc -> usc.getUserSubscription().getId().toString())
                        .collect(Collectors.joining(", "));
                log.error("User {} already has active subscription(s) for camera {}: SubscriptionIds={}", 
                        attempt.getUser().getId(), cameraId, existingSubscriptionIds);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        String.format("User already has an active subscription for camera %d. Subscription IDs: %s", 
                                cameraId, existingSubscriptionIds)
                );
            }
        }

        // Создаем записи UserSubscriptionCamera для каждой камеры
        int createdCount = 0;
        for (Long cameraId : cameraIds) {
            // Проверяем, не создана ли уже запись (защита от дублей)
            boolean alreadyExists = userSubscriptionCameraRepo.findAll().stream()
                    .anyMatch(usc -> usc.getUserSubscription().getId().equals(subscription.getId())
                            && usc.getCameraId().equals(cameraId));

            if (alreadyExists) {
                log.warn("UserSubscriptionCamera already exists: SubscriptionId={}, CameraId={}", 
                        subscription.getId(), cameraId);
                continue;
            }

            UserSubscriptionCamera subscriptionCamera = UserSubscriptionCamera.builder()
                    .userSubscription(subscription)
                    .cameraId(cameraId)
                    .build();

            userSubscriptionCameraRepo.save(subscriptionCamera);
            createdCount++;
            log.debug("Created UserSubscriptionCamera: SubscriptionId={}, CameraId={}", 
                    subscription.getId(), cameraId);
        }

        log.info("Created {} camera subscription(s) for user {}: Plan={}, PaymentId={}, SubscriptionId={}, CameraIds={}",
                createdCount, attempt.getUser().getId(), plan.getCode(), paymentId, subscription.getId(), cameraIds);
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
     * Фоновая задача для обработки PENDING платежей.
     * Запускается каждые 5 минут и проверяет статус платежей в Тинькофф.
     * Защита от дублей: handleSuccessfulPayment() проверяет статус PENDING перед обработкой.
     */
    @Scheduled(fixedDelay = 300000) // каждые 5 минут (300000 мс)
    @Transactional
    public void processPendingPayments() {
        log.debug("Starting scheduled task to process pending payments");
        
        List<PaymentAttempt> pendingPayments = paymentAttemptRepo.findByStatus("PENDING");
        
        if (pendingPayments.isEmpty()) {
            log.debug("No pending payments found");
            return;
        }
        
        log.info("Found {} pending payment(s) to process", pendingPayments.size());
        
        int processed = 0;
        int failed = 0;
        
        for (PaymentAttempt attempt : pendingPayments) {
            try {
                // Проверяем, что есть providerSessionId и это не временный orderId
                String paymentId = attempt.getProviderSessionId();
                if (paymentId == null || paymentId.startsWith("ORDER_")) {
                    log.debug("Skipping payment attempt {} - paymentId not yet available (providerSessionId={})", 
                            attempt.getId(), paymentId);
                    continue;
                }
                
                log.debug("Checking payment status for PaymentAttempt {} (PaymentId={})", attempt.getId(), paymentId);
                
                // Проверяем статус в Тинькофф
                TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
                log.debug("Payment status from Tinkoff: Status={}, Success={}", state.status(), state.success());
                
                // Если платеж успешен - обрабатываем
                if ("CONFIRMED".equals(state.status()) || "AUTHORIZED".equals(state.status())) {
                    log.info("Processing successful payment: PaymentAttemptId={}, PaymentId={}, OrderId={}", 
                            attempt.getId(), paymentId, attempt.getOrderId());
                    
                    // handleSuccessfulPayment() защищен от дублей проверкой статуса PENDING
                    handleSuccessfulPayment(paymentId, attempt.getOrderId());
                    processed++;
                } else if ("REJECTED".equals(state.status()) || "CANCELED".equals(state.status())) {
                    // Помечаем как неуспешный
                    log.info("Marking payment as failed: PaymentAttemptId={}, PaymentId={}, Status={}", 
                            attempt.getId(), paymentId, state.status());
                    handleFailedPayment(paymentId, attempt.getOrderId());
                    failed++;
                } else {
                    // NEW, AUTHORIZING и т.д. - еще обрабатывается, оставляем PENDING
                    log.debug("Payment still in progress: PaymentAttemptId={}, PaymentId={}, Status={}", 
                            attempt.getId(), paymentId, state.status());
                }
                
            } catch (Exception e) {
                log.error("Error processing pending payment {} (PaymentId={}, OrderId={}): {}", 
                        attempt.getId(), attempt.getProviderSessionId(), attempt.getOrderId(), 
                        e.getMessage(), e);
                // Продолжаем обработку следующих платежей
            }
        }
        
        log.info("Scheduled task completed: processed={}, failed={}, total={}", 
                processed, failed, pendingPayments.size());
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


