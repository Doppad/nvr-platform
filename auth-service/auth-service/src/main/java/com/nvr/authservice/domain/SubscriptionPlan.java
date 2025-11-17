package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.OffsetDateTime;




@Entity @Table(name="subscription_plan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code; // FREE, CAM_1, CAM_3

    @Column(nullable = false, length = 64)
    private String title;

    @Column(name = "archive_days", nullable = false)
    private Integer archiveDays; // 14, 30 и т.д.

    @Column(name = "max_cameras", nullable = false)
    private Integer maxCameras; // историческое поле, можно использовать как "рекомендация по кол-ву камер"

    @Column(name = "camera_quota")
    private Integer cameraQuota; // сколько камер покрывает план (для FREE может быть null)

    @Column(name = "is_addon", nullable = false)
    private boolean addon; // план-расширение (CAM_1, CAM_3) или базовый (FREE)

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ NOT NULL DEFAULT now()")
    private Instant createdAt;
}