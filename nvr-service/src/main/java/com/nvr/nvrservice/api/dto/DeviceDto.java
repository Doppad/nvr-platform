package com.nvr.nvrservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

@Data @AllArgsConstructor
public class DeviceDto {
    Long id;
    String name;
    String ip;
    Integer port;
    Integer httpPort; // HTTP порт для API запросов
    String vendor;
    String timezone;
    OffsetDateTime createdAt;

    // Камеры
    int camerasCount;
    Integer maxChannels; // Максимальное количество каналов устройства (16/32/64)

    // Viewer-учётка
    String viewerLogin;
    String viewerPassword;

    // Новый блок: адрес
    AddressDto address;

    // Простой статус доступности устройства (ONLINE / OFFLINE / UNKNOWN)
    String status;
}