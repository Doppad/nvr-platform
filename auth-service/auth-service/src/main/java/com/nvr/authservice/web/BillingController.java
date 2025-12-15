package com.nvr.authservice.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nvr.authservice.service.BillingService;
import com.nvr.authservice.service.TinkoffApiClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final TinkoffApiClient tinkoffApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long currentUserIdOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "No authenticated user"
            );
        }
        Object p = auth.getPrincipal();
        if (p instanceof Long l) return l;
        if (p instanceof String s) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid user id in principal"
                );
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Unsupported principal type"
        );
    }

    /**
     * Получение списка доступных планов для покупки.
     * Фронтенд использует этот эндпоинт для отображения цен.
     */
    @GetMapping("/plans")
    public ResponseEntity<?> getPlans() {
        return ResponseEntity.ok(billingService.getAvailablePlans());
    }

    /**
     * Создание платёжной сессии в Тинькофф для покупки подписки.
     * Цена берется из БД по planCode - безопасно!
     * Поддерживаются планы: CAM_1 (1 камера) и CAM_3 (3 камеры).
     */
    @PostMapping("/create-session")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.BillingSession createSession(@RequestBody CreateSessionRequest req) {
        Long userId = currentUserIdOrThrow();

        if (req.planCode == null || req.planCode.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "planCode is required (CAM_1 or CAM_3)"
            );
        }
        if (!"CAM_1".equals(req.planCode) && !"CAM_3".equals(req.planCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "planCode must be CAM_1 or CAM_3"
            );
        }

        // Цена берется из БД - безопасно!
        return billingService.createSession(userId, req.planCode);
    }

    /**
     * Webhook для уведомлений от Тинькофф о статусе платежа.
     * Тинькофф отправляет POST запросы с данными о платеже.
     */
    @PostMapping("/webhook/tinkoff")
    public ResponseEntity<?> handleTinkoffWebhook(@RequestBody TinkoffWebhookRequest req) {
        try {
            log.info("Received Tinkoff webhook: PaymentId={}, OrderId={}, Status={}, Success={}, ErrorCode={}",
                    req.paymentId, req.orderId, req.status, req.success, req.errorCode);

            // Проверяем подпись (Token) от Тинькофф для безопасности
            // Преобразуем объект в Map, чтобы получить все поля (включая возможные дополнительные)
            @SuppressWarnings("unchecked")
            Map<String, Object> webhookData = objectMapper.convertValue(req, Map.class);
            // Удаляем Token из данных для проверки подписи
            webhookData.remove("token");

            if (!tinkoffApiClient.verifyToken(webhookData, req.token)) {
                log.error("Invalid token in Tinkoff webhook: PaymentId={}, OrderId={}", req.paymentId, req.orderId);
                // Возвращаем 200 OK, чтобы Тинькофф не повторял запрос, но логируем ошибку
                return ResponseEntity.ok(Map.of("status", "ERROR", "message", "Invalid token"));
            }

            if (req.success != null && req.success && "CONFIRMED".equals(req.status)) {
                billingService.handleSuccessfulPayment(req.paymentId, req.orderId);
                return ResponseEntity.ok(Map.of("status", "OK"));
            } else {
                // Обрабатываем все остальные статусы как неуспешные
                // (REJECTED, CANCELED, NEW и т.д.)
                billingService.handleFailedPayment(req.paymentId, req.orderId);
                return ResponseEntity.ok(Map.of("status", "OK"));
            }
        } catch (IllegalArgumentException e) {
            // Платеж не найден - это нормально, может быть дубликат webhook
            log.warn("Payment not found in webhook: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("status", "OK", "message", "Payment not found"));
        } catch (Exception e) {
            log.error("Error processing Tinkoff webhook: {}", e.getMessage(), e);
            // Возвращаем 200 OK, чтобы Тинькофф не повторял запрос
            // Но логируем ошибку для мониторинга
            return ResponseEntity.ok(Map.of("status", "ERROR", "message", "Internal error"));
        }
    }

    /**
     * Подтверждает платеж и активирует подписку без webhook.
     * Используется как fallback, когда webhook не приходит.
     *
     * @param req запрос с paymentId
     * @return статус подтверждения
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirmPayment(@RequestBody ConfirmPaymentRequest req) {
        Long userId = currentUserIdOrThrow();

        try {
            billingService.confirmPayment(userId, req.paymentId);
            return ResponseEntity.ok(Map.of("status", "OK", "message", "Payment confirmed and subscription activated"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error confirming payment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "ERROR", "message", "Internal error"));
        }
    }

    @Data
    public static class CreateSessionRequest {
        /**
         * Код плана подписки (CAM_1 или CAM_3).
         * Цена берется из БД автоматически - безопасно!
         */
        private String planCode;
    }

    /**
     * DTO для подтверждения платежа.
     */
    @Data
    public static class ConfirmPaymentRequest {
        private String paymentId;
    }

    /**
     * DTO для webhook уведомлений от Тинькофф.
     */
    @Data
    public static class TinkoffWebhookRequest {
        private Boolean success;
        private String errorCode;
        private String message;
        private String terminalKey;
        private String status; // NEW, CONFIRMED, REJECTED, CANCELED и т.д.
        private String paymentId;
        private String orderId;
        private Long amount;
        private String token; // подпись запроса
    }
}


