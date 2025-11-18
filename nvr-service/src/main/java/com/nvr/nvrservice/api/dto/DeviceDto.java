package com.nvr.nvrservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

@Data @AllArgsConstructor
public class DeviceDto {
    private Long id;
    private String name;
    private String ip;
    private int port;
    private String address;
    private String vendor;
    private OffsetDateTime createdAt;

    private Integer camerasCount;
    private String username;
    private String password;
}
