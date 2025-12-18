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
    private Boolean active;         // активен ли канал (online/offline) - legacy, используйте uiStatus
    private Boolean visible;        // должен ли канал отображаться в UI (false для пустых каналов)
    
    // Новое поле для UI статуса (вычисляется на бэке)
    private String uiStatus;        // ONLINE | OFFLINE | HIDDEN | ONLINE_NO_STREAM | UNKNOWN
    
    // Новые поля статусов
    private Boolean hasCamera;      // есть ли реальная камера на канале
    private String nvrStatus;       // ONLINE | OFFLINE | UNKNOWN (только из Dahua API)
    private String rtspStatus;      // OK | FAIL | NONE (только из RTSP проверки)
    
    // Дополнительные поля (опционально, для обратной совместимости)
    private Long id;
    private Integer channelNo;      // alias для channelNumber
    private String status;           // legacy поле
    private String ipAddress;
    private Integer port;
    private String deviceName;
    private String channelName;
    private String protocol;
    private String type;
    private Boolean isActive;        // alias для active
}

