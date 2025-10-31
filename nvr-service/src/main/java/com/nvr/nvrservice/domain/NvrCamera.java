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
}
