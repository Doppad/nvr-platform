package com.nvr.nvrservice.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "nvr_device_user",
        uniqueConstraints = @UniqueConstraint(columnNames = {"device_id","role"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NvrDeviceUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private NvrDevice device;

    @Column(nullable = false) private String role;        // user_admin | user_default | user_archive | user_ai
    @Column(nullable = false) private String username;
    @Column(nullable = false) private String passwordEnc; // шифротекст (Base64)

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
