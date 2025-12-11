package com.nvr.authservice.repo;

import com.nvr.authservice.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByProviderSessionId(String providerSessionId);
}


