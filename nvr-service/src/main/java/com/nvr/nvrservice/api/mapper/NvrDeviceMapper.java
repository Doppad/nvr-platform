package com.nvr.nvrservice.api.mapper;

import com.nvr.nvrservice.api.dto.NvrDeviceDto;
import com.nvr.nvrservice.domain.NvrDevice;

public class NvrDeviceMapper {
    public static NvrDeviceDto toDto(NvrDevice e) {
        if (e == null) return null;
        return NvrDeviceDto.builder()
                .id(e.getId())
                .name(e.getName())
                .ip(e.getIp())
                .port(e.getPort())
                .address(e.getAddress())
                .vendor(e.getVendor())
                .timezone(e.getTimezone())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
