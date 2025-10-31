package com.nvr.nvrservice.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity @Table(name = "nvr_device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NvrDevice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(nullable = false) private Long ownerId;
    @Column(nullable = false) private String name;

    // В БД это inet: оставляем строкой, но фиксируем тип
    @Convert(converter = com.nvr.nvrservice.config.InetStringConverter.class)
    @JdbcTypeCode(SqlTypes.OTHER)                 // <– ключевая строка
    @Column(nullable = false, columnDefinition = "inet")
    private String ip;

    @Column(nullable = false) private Integer port;
    private String address;
    private String vendor;

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
