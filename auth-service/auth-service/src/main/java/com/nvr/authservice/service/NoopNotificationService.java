package com.nvr.authservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Noop-реализация NotificationService (только логирование).
 * Активна когда app.email.enabled=false AND app.sms.enabled=false AND app.telegram.enabled=false.
 */
@Slf4j
@Service
@ConditionalOnExpression("!${app.email.enabled:true} && !${app.sms.enabled:false} && !${app.telegram.enabled:false}")
public class NoopNotificationService implements NotificationService {
    @Override
    public void sendOtp(String target, String code) {
        log.info("OTP (noop) sent to {}", maskTarget(target));
    }

    private String maskTarget(String target) {
        if (target == null || target.isBlank()) return "***";
        if (target.contains("@")) {
            int at = target.indexOf('@');
            String local = target.substring(0, at);
            return (local.length() <= 2 ? "***" : local.substring(0, 2) + "***") + target.substring(at);
        }
        if (target.length() < 7) return "***";
        return target.substring(0, 2) + "***" + target.substring(target.length() - 2);
    }
}
