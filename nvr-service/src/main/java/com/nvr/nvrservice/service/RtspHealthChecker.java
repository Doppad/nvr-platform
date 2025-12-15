package com.nvr.nvrservice.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Сервис для проверки доступности RTSP потоков.
 */
@Slf4j
@Component
public class RtspHealthChecker {

    private static final int RTSP_CHECK_TIMEOUT_SECONDS = 3;

    /**
     * Проверяет, доступен ли RTSP поток.
     * 
     * @param rtspUrl URL RTSP потока
     * @return true если поток доступен, false в противном случае
     */
    public boolean isOnline(String rtspUrl) {
        if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
            log.debug("RTSP URL is empty, treating as offline");
            return false;
        }

        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(rtspUrl);
            
            // Настраиваем таймауты для быстрой проверки
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("stimeout", String.valueOf(RTSP_CHECK_TIMEOUT_SECONDS * 1000000)); // микросекунды
            grabber.setOption("timeout", String.valueOf(RTSP_CHECK_TIMEOUT_SECONDS * 1000000));
            
            // Пытаемся запустить grabber
            grabber.start();
            
            // Если start() успешен, поток доступен
            log.debug("RTSP online for {}", maskPassword(rtspUrl));
            return true;
            
        } catch (Exception e) {
            log.debug("RTSP offline for {}: {}", maskPassword(rtspUrl), e.getMessage());
            return false;
        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    log.warn("Error releasing FFmpegFrameGrabber for {}: {}", maskPassword(rtspUrl), e.getMessage());
                }
            }
        }
    }

    /**
     * Асинхронная проверка RTSP потока с таймаутом.
     * 
     * @param rtspUrl URL RTSP потока
     * @return CompletableFuture с результатом проверки
     */
    public CompletableFuture<Boolean> isOnlineAsync(String rtspUrl) {
        return CompletableFuture
                .supplyAsync(() -> isOnline(rtspUrl))
                .orTimeout(RTSP_CHECK_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException) {
                        log.debug("RTSP check timeout for {}", maskPassword(rtspUrl));
                    } else {
                        log.debug("RTSP check error for {}: {}", maskPassword(rtspUrl), ex.getMessage());
                    }
                    return false;
                });
    }

    /**
     * Маскирует пароль в RTSP URL для безопасного логирования.
     */
    private String maskPassword(String rtspUrl) {
        if (rtspUrl == null) {
            return null;
        }
        // Маскируем пароль в формате rtsp://user:password@host
        return rtspUrl.replaceAll("rtsp://([^:]+):([^@]+)@", "rtsp://$1:***@");
    }
}

