package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity @Table(name="user_subscription")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserSubscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    @Column(nullable=false) Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="plan_id", nullable=false)
    private SubscriptionPlan plan;

    @Column(nullable=false) OffsetDateTime startsAt;
    @Column(nullable=false) OffsetDateTime endsAt;
    @Builder.Default @Column(nullable=false) Boolean isActive = true;
}