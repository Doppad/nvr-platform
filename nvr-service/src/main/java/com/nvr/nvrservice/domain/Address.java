package com.nvr.nvrservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "address")
@Getter
@Setter
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Владелец адреса = userId из JWT (метаданные, nullable, deprecated).
     * 
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - ownerId оставлен как metadata для обратной совместимости
     * - ownerId больше НЕ используется как ограничение доступа
     * - Пользователь имеет один активный addressId (хранится в User.addressId)
     */
    @Deprecated
    @Column(name = "owner_id", nullable = true)
    private Long ownerId;

    // чтобы фронт мог показывать “Офис / Дом / Склады” без сборки строки из улица/дом.
    @Column(nullable = false)
    private String label;

    private String city;
    private String street;
    private String house;
    private String apartment;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // Обратная связь: адрес -> список девайсов
    @OneToMany(mappedBy = "addressEntity")
    private List<NvrDevice> devices;
}
