package com.nvr.nvrservice.api.dto;

import lombok.Data;

@Data
public class UpdateDeviceReq {
    private String name;
    private String ip;
    private Integer port;
    private String address;
    private String vendor;
}
