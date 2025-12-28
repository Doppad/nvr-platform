package com.nvr.authservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnExpression("!${app.sms.enabled:false} && !${app.telegram.enabled:false}")
public class NoopNotificationService implements NotificationService {
    @Override 
    public void sendOtp(String target, String code) {
        // В dev режиме логируем только факт отправки, но НЕ сам OTP код
        log.info("OTP (noop) sent to {}", maskPhone(target));
    }

    /**
     * Маскирует номер телефона для безопасного логирования.
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        int visibleStart = Math.min(4, phone.length() - 7);
        int visibleEnd = Math.max(phone.length() - 4, visibleStart + 3);
        return phone.substring(0, visibleStart) + "***" + phone.substring(visibleEnd);
    }
}
