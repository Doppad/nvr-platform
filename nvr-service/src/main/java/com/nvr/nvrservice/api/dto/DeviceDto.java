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
    String vendor;
    OffsetDateTime createdAt;

    // Камеры
    int camerasCount;

    // Viewer-учётка
    String viewerLogin;
    String viewerPassword;

    // Новый блок: адрес
    Long addressId;
    String addressLabel;
}