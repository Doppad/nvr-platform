package com.nvr.authservice.service;

import com.nvr.authservice.domain.PaymentAttempt;
import com.nvr.authservice.domain.PaymentAttemptCamera;
import com.nvr.authservice.repo.PaymentAttemptCameraRepository;
import com.nvr.authservice.repo.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис, который содержит бизнес-логику обработки платежей в отдельных транзакциях.
 * Используется из BillingService, контроллеров и фоновых задач.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingProcessingService {

    private final PaymentAttemptRepository paymentAttemptRepo;
    private final PaymentAttemptCameraRepository paymentAttemptCameraRepo;
    private final TinkoffApiClient tinkoffApiClient;
    private final SubscriptionService subscriptionService;

    /**
     * Обрабатывает успешный платеж и создает подписку через SubscriptionService.
     * Идемпотентный метод: повторные вызовы не создают дубликаты.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @param orderId   номер заказа
     * @throws ResponseStatusException с HTTP статусами:
     *                                  - 404 NOT_FOUND если attempt не найден
     *                                  - 409 CONFLICT для неконсистентных данных
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        log.info("Found payment attempt: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}",
                attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(),
                attempt.getPlanCode(), attempt.getUser().getId());

        // ИДЕМПОТЕНТНОСТЬ: если уже обработан - возвращаемся без побочных эффектов
        if (!"PENDING".equals(attempt.getStatus())) {
            log.info("Payment attempt {} already processed with status {}. Returning OK (idempotent).",
                    attempt.getId(), attempt.getStatus());
            return; // 200 OK - идемпотентность
        }

        // Проверяем статус в Тинькофф
        log.info("Checking payment status with PaymentId={}", paymentId);
        TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
        log.info("Payment status from Tinkoff: Status={}, Success={}", state.status(), state.success());

        // Для тестовых платежей Тинькофф может возвращать AUTHORIZED вместо CONFIRMED
        if (!"CONFIRMED".equals(state.status()) && !"AUTHORIZED".equals(state.status())) {
            log.warn("Payment {} status is not CONFIRMED or AUTHORIZED: {}", paymentId, state.status());
            attempt.setStatus("FAILED");
            paymentAttemptRepo.save(attempt);
            return;
        }

        // ИДЕМПОТЕНТНОСТЬ: атомарное обновление статуса PENDING -> SUCCESS
        // Если статус уже не PENDING (изменился между проверкой и обновлением) - метод вернет 0
        Long attemptId = attempt.getId();
        int updated = paymentAttemptRepo.updateStatusFromPendingToSuccess(attemptId);
        if (updated == 0) {
            // Статус уже был изменен другим потоком - идемпотентность
            log.info("Payment attempt {} status was already changed by another process. Returning OK (idempotent).",
                    attemptId);
            return; // 200 OK - идемпотентность
        }

        log.info("Updated payment attempt status to SUCCESS: AttemptId={}", attemptId);

        // Получаем сохраненные cameraIds из PaymentAttempt
        List<PaymentAttemptCamera> paymentCameras = paymentAttemptCameraRepo.findByPaymentAttemptId(attemptId);
        List<Long> cameraIds = paymentCameras.stream()
                .map(PaymentAttemptCamera::getCameraId)
                .collect(Collectors.toList());

        log.info("Found {} camera(s) for payment attempt: CameraIds={}", cameraIds.size(), cameraIds);

        // Создаем подписку через SubscriptionService (в отдельной транзакции)
        subscriptionService.handleSuccessfulPayment(
                attempt.getUser().getId(),
                attempt.getPlanCode(),
                cameraIds
        );

        log.info("Successfully processed payment and created subscription: PaymentId={}, UserId={}, PlanCode={}, CameraIds={}",
                paymentId, attempt.getUser().getId(), attempt.getPlanCode(), cameraIds);
    }

    /**
     * Обрабатывает возврат средств по платежу.
     * Проверяет статус платежа, вызывает Tinkoff API для возврата,
     * обновляет статус PaymentAttempt и отменяет подписку.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @param userId    ID пользователя (для проверки владельца платежа)
     * @throws ResponseStatusException если платеж не найден, не подтвержден, уже возвращен или не принадлежит пользователю
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRefund(String paymentId, Long userId) {
        log.info("handleRefund called (by paymentId): PaymentId={}, UserId={}", paymentId, userId);

        // Находим платеж
        PaymentAttempt attempt = paymentAttemptRepo.findByProviderSessionId(paymentId)
                .orElseThrow(() -> {
                    log.error("Payment attempt not found: PaymentId={}", paymentId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payment attempt not found: " + paymentId
                    );
                });

        log.info("Found payment attempt: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}",
                attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(),
                attempt.getPlanCode(), attempt.getUser().getId());

        // Проверяем, что платеж принадлежит пользователю
        if (!attempt.getUser().getId().equals(userId)) {
            log.error("Payment {} does not belong to user {}. Payment owner: {}",
                    paymentId, userId, attempt.getUser().getId());
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Payment does not belong to user"
            );
        }

        // Проверяем, что платеж подтвержден
        if (!"SUCCESS".equals(attempt.getStatus())) {
            log.warn("Payment {} is not confirmed (status: {}). Cannot refund.", paymentId, attempt.getStatus());
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment is not confirmed. Current status: " + attempt.getStatus()
            );
        }

        // Проверяем, что платеж еще не был возвращен
        if ("REFUNDED".equals(attempt.getStatus())) {
            log.info("Payment {} already refunded. Returning OK (idempotent).", paymentId);
            return; // Идемпотентность
        }

        // Проверяем статус в Тинькофф перед возвратом
        log.info("Checking payment status before refund with PaymentId={}", paymentId);
        TinkoffApiClient.TinkoffStateResponse state = tinkoffApiClient.getState(paymentId);
        log.info("Payment status from Tinkoff: Status={}, Success={}", state.status(), state.success());

        if (!"CONFIRMED".equals(state.status()) && !"AUTHORIZED".equals(state.status())) {
            log.warn("Payment {} status is not CONFIRMED or AUTHORIZED: {}. Cannot refund.", paymentId, state.status());
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment status is not CONFIRMED or AUTHORIZED: " + state.status()
            );
        }

        // Выполняем возврат через Tinkoff API (полный возврат, amount = null)
        log.info("Calling Tinkoff refund API for PaymentId={}", paymentId);
        TinkoffApiClient.TinkoffRefundResponse refundResponse = tinkoffApiClient.refundPayment(paymentId, null);
        log.info("Tinkoff refund response: Success={}, Status={}, Amount={}",
                refundResponse.success(), refundResponse.status(), refundResponse.amount());

        // Обновляем статус платежа на REFUNDED
        attempt.setStatus("REFUNDED");
        paymentAttemptRepo.save(attempt);
        log.info("Updated payment attempt status to REFUNDED: AttemptId={}", attempt.getId());

        // Отменяем подписку через SubscriptionService (в отдельной транзакции)
        subscriptionService.cancelSubscription(attempt.getUser().getId(), attempt.getPlanCode());

        log.info("Successfully processed refund: PaymentId={}, UserId={}, PlanCode={}",
                paymentId, attempt.getUser().getId(), attempt.getPlanCode());
    }

    /**
     * Обрабатывает возврат средств по платежу по orderId.
     * Используется, когда фронтенд знает только orderId после редиректа.
     *
     * Алгоритм:
     * 1. Находит PaymentAttempt по orderId (с блокировкой FOR UPDATE).
     * 2. Берет providerSessionId (PaymentId в Тинькофф).
     * 3. Вызывает TinkoffApiClient.cancelPayment(providerSessionId).
     * 4. Обновляет статус PaymentAttempt на REFUNDED.
     * 5. Отменяет подписку через SubscriptionService.
     *
     * @param orderId номер заказа
     * @param userId  идентификатор пользователя (для проверки владельца платежа)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRefundByOrderId(String orderId, Long userId) {
        log.info("handleRefundByOrderId called: OrderId={}, UserId={}", orderId, userId);

        // Находим платеж по orderId с блокировкой FOR UPDATE для защиты от гонок
        PaymentAttempt attempt = paymentAttemptRepo.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> {
                    log.error("Payment attempt not found for refund by orderId: OrderId={}", orderId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payment attempt not found for OrderId=" + orderId
                    );
                });

        log.info("Found payment attempt for refund by orderId: Id={}, Status={}, ProviderSessionId={}, PlanCode={}, UserId={}",
                attempt.getId(), attempt.getStatus(), attempt.getProviderSessionId(),
                attempt.getPlanCode(), attempt.getUser().getId());

        // Проверяем, что платеж принадлежит пользователю
        if (!attempt.getUser().getId().equals(userId)) {
            log.error("Payment with OrderId={} does not belong to user {}. Payment owner: {}",
                    orderId, userId, attempt.getUser().getId());
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Payment does not belong to user"
            );
        }

        // Если уже возвращен - идемпотентность
        if ("REFUNDED".equals(attempt.getStatus())) {
            log.info("Payment with OrderId={} already refunded. Returning OK (idempotent).", orderId);
            return;
        }

        // Не даем вернуть платеж, если он не в статусе SUCCESS
        if (!"SUCCESS".equals(attempt.getStatus())) {
            log.warn("Payment with OrderId={} is not in SUCCESS status (current: {}). Cannot refund.",
                    orderId, attempt.getStatus());
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment is not confirmed. Current status: " + attempt.getStatus()
            );
        }

        String providerSessionId = attempt.getProviderSessionId();
        if (providerSessionId == null || providerSessionId.isBlank() || providerSessionId.startsWith("ORDER_")) {
            log.error("ProviderSessionId not available or invalid for refund by orderId: OrderId={}, ProviderSessionId={}",
                    orderId, providerSessionId);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment provider session id is not available for refund"
            );
        }

        // Вызываем Tinkoff API для отмены платежа
        log.info("Calling Tinkoff cancel API for PaymentId={} (OrderId={})", providerSessionId, orderId);
        tinkoffApiClient.cancelPayment(providerSessionId);

        // Обновляем статус на REFUNDED
        attempt.setStatus("REFUNDED");
        paymentAttemptRepo.save(attempt);
        log.info("Updated payment attempt status to REFUNDED: AttemptId={}, OrderId={}", attempt.getId(), orderId);

        // Отменяем подписку
        subscriptionService.cancelSubscription(attempt.getUser().getId(), attempt.getPlanCode());

        log.info("Successfully processed refund by orderId: OrderId={}, PaymentId={}, UserId={}, PlanCode={}",
                orderId, providerSessionId, attempt.getUser().getId(), attempt.getPlanCode());
    }

    /**
     * Обрабатывает неуспешный платеж.
     *
     * @param paymentId идентификатор платежа
     * @param orderId   номер заказа
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
}

