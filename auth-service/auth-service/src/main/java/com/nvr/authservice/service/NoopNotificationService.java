package com.nvr.authservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopNotificationService implements NotificationService {
    @Override public void sendOtp(String target, String code) {
        log.info("OTP (noop) for {}: {}", target, code);
    }
}
