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

    @Column(name = "full_name")
    private String fullName; // ФИО пользователя

    /**
     * ID адреса из nvr-service, привязанного к пользователю.
     * Может быть установлен при регистрации или позже.
     */
    @Column(name = "address_id")
    private Long addressId;

    private String passHash; // на будущее (пока не юзаю)
    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * Роль пользователя в системе.
     * По умолчанию обычный пользователь: USER.
     * Для супер-админа в БД можно установить значение SUPER_ADMIN.
     */
    @Column(nullable = false)
    @Builder.Default
    private String role = "USER";

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (isActive == null) isActive = true;
        if (role == null) role = "USER";
    }
}
