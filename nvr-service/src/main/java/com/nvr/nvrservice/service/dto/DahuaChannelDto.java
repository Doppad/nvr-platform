package com.nvr.nvrservice.service.dto;

/**
 * DTO для представления канала Dahua, полученного из API.
 */
public record DahuaChannelDto(
        int channelNo,
        String ipAddress,
        String deviceName,
        String channelName,
        String protocol,
        String type
) {
}

