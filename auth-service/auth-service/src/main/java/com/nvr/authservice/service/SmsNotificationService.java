package com.nvr.authservice.service;

import com.nvr.authservice.exception.InvalidPhoneException;
import com.nvr.authservice.exception.SmsSendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Сервис для отправки OTP по SMS через MTS Exolve.
 *
 * Бизнес-логика OTP находится в OtpService; этот класс только формирует текст и делегирует в ExolveSmsClient.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "enabled", havingValue = "true")
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

        String normalizedPhone;
        try {
            normalizedPhone = normalizePhoneForExolve(target);
        } catch (Exception e) {
            log.warn("Invalid phone format for SMS: {}", maskPhone(target));
            throw new InvalidPhoneException("Invalid phone number format");
        }
        if (normalizedPhone == null) {
            log.warn("Invalid phone format for SMS: {}", maskPhone(target));
            throw new InvalidPhoneException("Invalid phone number format");
        }

        // Не логируем сам OTP-код.
        String text = String.format(smsTemplate, code);

        exolveSmsClient.sendSms(normalizedPhone, text);
    }

    /**
     * Нормализует номер телефона в формат, ожидаемый Exolve API (7XXXXXXXXXX - 11 цифр, начинается с 7).
     *
     * Использует PhoneValidationService.normalizePhone() для базовой нормализации,
     * затем приводит к формату 7XXXXXXXXXX (без '+' и других символов, строго 11 цифр, начинается с 7).
     *
     * @param phone исходный номер телефона
     * @return нормализованный номер в формате 7XXXXXXXXXX или null при ошибке
     */
    private String normalizePhoneForExolve(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }

        // Используем PhoneValidationService для базовой нормализации
        String normalized = phoneValidationService.normalizePhone(phone);

        // Убираем все символы кроме цифр
        String digitsOnly = normalized.replaceAll("[^0-9]", "");

        // Проверяем формат: строго 11 цифр и начинается с 7
        if (digitsOnly.length() == 11 && digitsOnly.startsWith("7")) {
            return digitsOnly;
        }

        // Если начинается с 8 и 11 цифр -> заменяем на 7
        if (digitsOnly.length() == 11 && digitsOnly.startsWith("8")) {
            return "7" + digitsOnly.substring(1);
        }

        // Если начинается с +7 и после нормализации стало 12 символов (включая +) -> убираем +
        if (normalized.startsWith("+7") && digitsOnly.length() == 11) {
            return digitsOnly;
        }

        return null;
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

