package com.nvr.authservice.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "app_user") // имя таблицы в Postgres
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser { // таблица юзеров
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    @Column(unique = true)
    private String phone;

    private String passHash; // на будущее (пока не юзаю)
    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (isActive == null) isActive = true;
    }
}
