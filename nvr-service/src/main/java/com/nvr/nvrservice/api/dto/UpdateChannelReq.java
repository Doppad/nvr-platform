package com.nvr.nvrservice.api.dto;

import lombok.Data;

/**
 * DTO для обновления канала NVR.
 */
@Data
public class UpdateChannelReq {
    /**
     * Название канала.
     */
    private String name;
    
    /**
     * RTSP URL для канала.
     */
    private String rtspUrl;
    
    /**
     * Активен ли канал (enabled).
     */
    private Boolean enabled;
}

