package com.nvr.authservice.service;

import com.nvr.authservice.exception.InvalidEmailException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Сервис валидации и нормализации email.
 */
@Service
public class EmailValidationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$"
    );

    /**
     * Валидирует и нормализует email (trim + lowercase, проверка формата).
     *
     * @param email адрес email
     * @return нормализованный email
     * @throws InvalidEmailException если формат невалиден
     */
    public String validateAndNormalize(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidEmailException("Email не может быть пустым");
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new InvalidEmailException("Неверный формат email: " + email);
        }
        return normalized;
    }
}
