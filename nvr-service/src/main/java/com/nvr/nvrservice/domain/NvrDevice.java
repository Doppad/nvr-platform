package com.nvr.nvrservice.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "nvr_device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NvrDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String name;

    // В БД это inet: оставляем строкой, но фиксируем тип
    @Convert(converter = com.nvr.nvrservice.config.InetStringConverter.class)
    @JdbcTypeCode(SqlTypes.OTHER)
    @Column(nullable = false, columnDefinition = "inet")
    private String ip;

    @Column(nullable = false)
    private Integer port;

    // HTTP порт для API запросов (обычно 80 или 8080-8082)
    @Column(name = "http_port")
    private Integer httpPort;

    // legacy-строка, которую можно будет выпилить, когда фронт переедет на Address
    private String address;

    private String vendor;

    @Column(length = 64, nullable = false)
    @Builder.Default
    private String timezone = "UTC";

    // НОВОЕ: ссылка на Address через address_id
    @ManyToOne
    @JoinColumn(name = "address_id")
    private Address addressEntity;

    @Column(name = "cameras_count", nullable = false)
    @Builder.Default
    private Integer camerasCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
