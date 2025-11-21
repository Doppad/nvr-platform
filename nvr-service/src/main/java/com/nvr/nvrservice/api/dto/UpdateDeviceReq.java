package com.nvr.nvrservice.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDeviceReq {
    private String name;
    private String ip;
    private Integer port;
    private String address;
    private String vendor;

    @Min(0)
    private Integer camerasCount;

    @Size(max = 64)
    private String timezone;
}
