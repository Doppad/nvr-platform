// Примеры enum'ов для новой архитектуры статусов

package com.nvr.nvrservice.domain;

/**
 * Доступность устройства/камеры через NVR API.
 * Показывает, доступно ли устройство/камера через HTTP API NVR.
 */
public enum DeviceAvailability {
    /**
     * Устройство/камера доступно через API.
     * Для камеры: Dahua API вернул "Connected"
     * Для устройства: TCP connect к IP:port успешен
     */
    ONLINE,
    
    /**
     * Устройство/камера недоступно через API.
     * Для камеры: Dahua API вернул "Unconnect" / "Disconnected"
     * Для устройства: TCP connect к IP:port неуспешен
     */
    OFFLINE,
    
    /**
     * Статус неизвестен.
     * Для камеры: API не ответил, вернул "Connecting" / "UnInited" / "Hibernation"
     * Для устройства: проверка не выполнялась или произошла ошибка
     */
    UNKNOWN
}

/**
 * Состояние RTSP потока.
 * Показывает, работает ли RTSP поток для камеры.
 */
public enum StreamState {
    /**
     * RTSP поток доступен и работает.
     * RtspHealthChecker.isOnline() вернул true
     */
    STREAMING,
    
    /**
     * RTSP поток недоступен.
     * RtspHealthChecker.isOnline() вернул false
     * Это НЕ ошибка, если камера ONLINE - это WARNING
     */
    NO_STREAM,
    
    /**
     * RTSP проверка ещё не выполнялась.
     * Используется для новых камер или если проверка была пропущена
     */
    NOT_CHECKED
}

/**
 * Уровень здоровья устройства/камеры.
 * Общий индикатор состояния, показывает, требуется ли вмешательство.
 */
public enum HealthLevel {
    /**
     * Всё работает нормально.
     * Устройство ONLINE, RTSP STREAMING (если применимо)
     */
    OK,
    
    /**
     * Есть проблемы, но не критичные.
     * Примеры:
     * - Камера ONLINE, но RTSP NO_STREAM (можно просматривать через HTTP)
     * - Камера ONLINE, но RTSP NOT_CHECKED (проверка ещё не выполнялась)
     */
    WARNING,
    
    /**
     * Критическая проблема, требуется вмешательство.
     * Примеры:
     * - Устройство/камера OFFLINE
     * - Устройство/камера UNKNOWN + RTSP NO_STREAM
     */
    ERROR
}

