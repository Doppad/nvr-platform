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

    // Владелец адреса = userId из JWT
    @Column(name = "owner_id", nullable = false)
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
