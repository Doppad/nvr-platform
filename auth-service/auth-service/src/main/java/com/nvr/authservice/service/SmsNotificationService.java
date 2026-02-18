package com.nvr.authservice.service;

import com.nvr.authservice.exception.InvalidPhoneException;
import com.nvr.authservice.exception.SmsSendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Сервис отправки OTP по SMS через MTS Exolve.
 * Активен только при app.sms.enabled=true И app.email.enabled=false.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${app.email.enabled:true}")
@RequiredArgsConstructor
public class SmsNotificationService implements NotificationService {

    private final ExolveSmsClient exolveSmsClient;
    private final PhoneValidationService phoneValidationService;

    @Value("${app.otp.sms-template:OKO: Код подтверждения %s}")
    private String smsTemplate;

    @Override
    public void sendOtp(String target, String code) {
        if (target == null || target.isBlank()) {
            throw new InvalidPhoneException("Invalid phone number format");
        }
        if (code == null || code.isBlank()) {
            throw new SmsSendException("OTP code is empty");
        }

        try {
            phoneValidationService.validateRussianPhone(target);
        } catch (InvalidPhoneException e) {
            throw e;
        }
        String normalizedPhone = normalizePhoneForExolve(target);
        if (normalizedPhone == null) {
            throw new InvalidPhoneException("Invalid phone number format");
        }

        String text = String.format(smsTemplate, code);
        exolveSmsClient.sendSms(normalizedPhone, text);
    }

    private String normalizePhoneForExolve(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String normalized = phoneValidationService.normalizePhone(phone);
        String digitsOnly = normalized.replaceAll("[^0-9]", "");
        if (digitsOnly.length() == 11 && digitsOnly.startsWith("7")) return digitsOnly;
        if (digitsOnly.length() == 11 && digitsOnly.startsWith("8")) return "7" + digitsOnly.substring(1);
        if (normalized.startsWith("+7") && digitsOnly.length() == 11) return digitsOnly;
        return null;
    }
}
