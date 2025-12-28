package com.nvr.authservice.repo;

import com.nvr.authservice.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByProviderSessionId(String providerSessionId);
    Optional<PaymentAttempt> findByOrderId(String orderId);
    List<PaymentAttempt> findByStatus(String status);

    /**
     * Находит PaymentAttempt по providerSessionId с блокировкой FOR UPDATE для предотвращения race conditions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pa FROM PaymentAttempt pa WHERE pa.providerSessionId = :providerSessionId")
    Optional<PaymentAttempt> findByProviderSessionIdForUpdate(@Param("providerSessionId") String providerSessionId);

    /**
     * Находит PaymentAttempt по orderId с блокировкой FOR UPDATE для предотвращения race conditions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pa FROM PaymentAttempt pa WHERE pa.orderId = :orderId")
    Optional<PaymentAttempt> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * Атомарное обновление статуса с PENDING на SUCCESS.
     * Возвращает количество обновленных записей (0 или 1).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE PaymentAttempt pa SET pa.status = 'SUCCESS' WHERE pa.id = :id AND pa.status = 'PENDING'")
    int updateStatusFromPendingToSuccess(@Param("id") Long id);
}


