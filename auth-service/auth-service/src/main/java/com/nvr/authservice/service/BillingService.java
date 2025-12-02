package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.PaymentAttempt;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final AppUserRepository userRepo;
    private final PaymentAttemptRepository paymentAttemptRepo;

    @Transactional
    public BillingSession createTestSession(Long userId, long amountMinor, String currency, String planCode) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String sessionId = "test_" + UUID.randomUUID();

        PaymentAttempt attempt = PaymentAttempt.builder()
                .user(user)
                .amountMinor(amountMinor)
                .currency(currency)
                .planCode(planCode)
                .status("PENDING")
                .provider("TEST")
                .providerSessionId(sessionId)
                .build();

        paymentAttemptRepo.save(attempt);

        String redirectUrl = "https://example-pay.test/sessions/" + sessionId;

        return new BillingSession(sessionId, redirectUrl);
    }

    /**
     * Простейший DTO результата создания платёжной сессии.
     * В будущем можно будет маппить сюда реальные поля провайдера (Tinkoff / CloudPayments / ...).
     */
    public record BillingSession(
            String sessionId,
            String redirectUrl
    ) {
    }
}


