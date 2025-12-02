package com.nvr.authservice.web;

import com.nvr.authservice.service.BillingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

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
     * Каркас для эквайринга: создание платёжной "сессии".
     * Сейчас просто валидируем вход, сохраняем попытку и возвращаем фиктивный результат.
     */
    @PostMapping("/create-session")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.BillingSession createSession(@RequestBody CreateSessionRequest req) {
        Long userId = currentUserIdOrThrow();

        if (req.amountMinor == null || req.amountMinor <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "amountMinor must be > 0"
            );
        }
        if (req.currency == null || req.currency.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "currency is required"
            );
        }

        return billingService.createTestSession(
                userId,
                req.amountMinor,
                req.currency,
                req.planCode
        );
    }

    @Data
    public static class CreateSessionRequest {
        /**
         * Сумма в минимальных единицах (копейки / тиын).
         */
        private Long amountMinor;

        /**
         * Валюта (например, KZT / RUB / USD).
         */
        private String currency;

        /**
         * Необязательный код плана / подписки (FREE / PRO / ...).
         */
        private String planCode;
    }
}


