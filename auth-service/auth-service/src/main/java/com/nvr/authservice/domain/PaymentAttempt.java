package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /**
     * Сумма в минимальных единицах (тиын/копейки) — чтобы не хранить деньги в double.
     */
    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 16)
    private String currency;

    /**
     * Код плана / подписки, если оплата за конкретный план (FREE/PRO/...).
     */
    @Column(name = "plan_code", length = 64)
    private String planCode;

    /**
     * Статус попытки: NEW / PENDING / SUCCESS / FAILED / CANCELED
     */
    @Column(nullable = false, length = 32)
    private String status;

    /**
     * Имя провайдера (TINKOFF, CLOUDPAYMENTS, YOOKASSA, STRIPE, TEST и т.п.).
     * Сейчас будет TEST.
     */
    @Column(length = 64)
    private String provider;

    /**
     * Внешний идентификатор сессии/платежа у провайдера.
     * Пока фиктивный.
     */
    @Column(name = "provider_session_id", length = 128)
    private String providerSessionId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "NEW";
        if (provider == null) provider = "TEST";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}


