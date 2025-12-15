package com.nvr.nvrservice.api.dto;

import lombok.Data;

@Data
public class UpdateDeviceReq {
    private String name;
    private String ip;
    private Integer port;
    private Integer httpPort; // HTTP порт для API запросов
    private String address;
    private String vendor;
    private String timezone;
    private Integer camerasCount;
    private Long addressId;
}
