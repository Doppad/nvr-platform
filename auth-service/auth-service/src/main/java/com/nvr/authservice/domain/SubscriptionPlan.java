package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity @Table(name="subscription_plan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable=false, unique=true) String code; // FREE, PRO
    @Column(nullable=false) String title;
    @Column(nullable=false) Integer archiveDays;
    @Column(nullable=false) Integer maxCameras;
    @Builder.Default OffsetDateTime createdAt = OffsetDateTime.now();
}