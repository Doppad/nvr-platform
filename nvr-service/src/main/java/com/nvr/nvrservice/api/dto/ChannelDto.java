package com.nvr.nvrservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для представления канала NVR в REST API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelDto {
    private Integer channelNumber;  // 1..16
    private String name;            // название канала
    private String rtspUrl;         // RTSP URL
    private Boolean active;         // активен ли канал (online/offline)
    
    // Дополнительные поля (опционально, для обратной совместимости)
    private Long id;
    private Integer channelNo;      // alias для channelNumber
    private String status;
    private String ipAddress;
    private Integer port;
    private String deviceName;
    private String channelName;
    private String protocol;
    private String type;
    private Boolean isActive;        // alias для active
}

