package com.nvr.authservice.service;

import com.nvr.authservice.exception.InvalidPhoneException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Сервис валидации российских номеров телефонов (для SMS-канала).
 * Создаётся только при app.sms.enabled=true.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "enabled", havingValue = "true")
public class PhoneValidationService {

    private static final Pattern RUSSIAN_PHONE_PATTERN = Pattern.compile("^\\+7[0-9]{10}$");

    public void validateRussianPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new InvalidPhoneException("Номер телефона не может быть пустым");
        }
        String normalized = phone.replaceAll("[\\s\\-\\(\\)]", "");
        if (!RUSSIAN_PHONE_PATTERN.matcher(normalized).matches()) {
            throw new InvalidPhoneException("Неверный формат номера телефона");
        }
        if (normalized.length() >= 4 && normalized.substring(2, 4).equals("77")) {
            throw new InvalidPhoneException("Номер не является российским");
        }
    }

    public String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        return phone.replaceAll("[\\s\\-\\(\\)]", "");
    }
}
