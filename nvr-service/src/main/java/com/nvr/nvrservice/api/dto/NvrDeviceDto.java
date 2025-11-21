package com.nvr.nvrservice.api.dto;

import lombok.*;
import java.time.OffsetDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NvrDeviceDto {
    private Long id;
    private String name;
    private String ip;
    private Integer port;
    private String address;
    private String vendor;
    private String timezone;
    private OffsetDateTime createdAt;
}
