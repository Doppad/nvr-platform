package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "otp_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpAttempt { // таблица попыток входа
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(nullable = false)
    private String target; //  куда отправляли (email или тел)

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isUsed = false;

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (isUsed == null) isUsed = false;
    }
}
