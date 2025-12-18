package com.nvr.nvrservice.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "nvr_camera",
        uniqueConstraints = @UniqueConstraint(columnNames = {"device_id","channel_no"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NvrCamera {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private NvrDevice device;

    @Column(name = "channel_no", nullable = false) private Integer channelNo;
    @Column(nullable = false) private String name;

    // шаблон вроде: rtsp://{u}:{p}@{ip}:{port}/Streaming/Channels/{ch}01
    private String rtspTemplate;

    @Column(nullable = false) private Boolean enabled;

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // Поля для поддержки Dahua API (из миграции V4)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "port")
    private Integer port;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "channel_name", length = 255)
    private String channelName;

    @Column(name = "protocol", length = 32)
    private String protocol;

    @Column(name = "type", length = 64)
    private String type;

    @Column(name = "rtsp_url", columnDefinition = "TEXT")
    private String rtspUrl;

    @Column(name = "status", length = 16)
    @Builder.Default
    private String status = "UNKNOWN"; // Legacy поле, оставлено для обратной совместимости

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "status_updated_at")
    private OffsetDateTime statusUpdatedAt;

    // Новые поля для разделения статусов
    // nullable = true, чтобы Hibernate не пытался создать NOT NULL колонку при синхронизации схемы
    // NOT NULL будет установлен через миграцию после заполнения данных
    @Column(name = "has_camera")
    @Builder.Default
    private Boolean hasCamera = false;

    @Column(name = "nvr_status", length = 16)
    @Builder.Default
    private String nvrStatus = "UNKNOWN";

    @Column(name = "rtsp_status", length = 16)
    @Builder.Default
    private String rtspStatus = "NONE";

    @Column(name = "nvr_status_updated_at")
    private OffsetDateTime nvrStatusUpdatedAt;

    @Column(name = "rtsp_status_updated_at")
    private OffsetDateTime rtspStatusUpdatedAt;
}
