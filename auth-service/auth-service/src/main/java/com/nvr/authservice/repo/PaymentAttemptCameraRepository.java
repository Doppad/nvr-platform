package com.nvr.authservice.repo;

import com.nvr.authservice.domain.PaymentAttemptCamera;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAttemptCameraRepository extends JpaRepository<PaymentAttemptCamera, Long> {
    List<PaymentAttemptCamera> findByPaymentAttemptId(Long paymentAttemptId);
}








