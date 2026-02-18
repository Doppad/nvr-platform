package com.nvr.authservice.service;

import com.nvr.authservice.exception.SmsSendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Сервис отправки OTP по email через JavaMailSender.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EmailNotificationService implements NotificationService {

    private final JavaMailSender mailSender;
    private final EmailValidationService emailValidationService;

    @Override
    public void sendOtp(String target, String code) {
        if (target == null || target.isBlank()) {
            throw new SmsSendException("OTP target (email) is empty");
        }
        if (code == null || code.isBlank()) {
            throw new SmsSendException("OTP code is empty");
        }

        String normalizedEmail = emailValidationService.validateAndNormalize(target);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("no-reply@okodoma-tp.ru");
        message.setTo(normalizedEmail);
        message.setSubject("Код подтверждения");
        message.setText("Ваш код подтверждения: " + code);

        try {
            mailSender.send(message);
            log.info("OTP sent via email to {}", maskEmail(normalizedEmail));
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", maskEmail(normalizedEmail), e.getMessage());
            throw new SmsSendException("Failed to send OTP email", e);
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }
}
