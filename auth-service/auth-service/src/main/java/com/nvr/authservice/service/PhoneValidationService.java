package com.nvr.authservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Сервис для валидации российских номеров телефонов.
 * Проверяет формат +7XXXXXXXXXX (только РФ, не Казахстан).
 */
@Slf4j
@Service
public class PhoneValidationService {

    /**
     * Паттерн для российских номеров телефонов:
     * +7 - код страны
     * затем 10 цифр (3 цифры код оператора + 7 цифр номера)
     * 
     * Российские мобильные номера начинаются с 9 после +7 (9XX XXX-XX-XX)
     * Городские могут начинаться с 3, 4, 5, 8 и т.д.
     * 
     * Исключаем казахстанские номера: они тоже +7, но имеют другие коды операторов.
     * Для простоты проверяем, что номер начинается с +7 и имеет правильную длину.
     * Более строгая проверка может быть добавлена позже по кодам операторов.
     */
    private static final Pattern RUSSIAN_PHONE_PATTERN = Pattern.compile(
            "^\\+7[0-9]{10}$"
    );

    /**
     * Российские коды операторов (мобильные начинаются с 9):
     * 9XX - мобильные операторы (900-999)
     * 3XX - городские (300-399)
     * 4XX, 5XX, 8XX - другие коды
     * 
     * Казахстанские номера имеют коды 7XX (700-799), поэтому исключаем их.
     */
    private static final Pattern RUSSIAN_MOBILE_PATTERN = Pattern.compile(
            "^\\+79[0-9]{9}$"  // +7 + 9XX + 7 цифр
    );

    /**
     * Валидирует российский номер телефона.
     * 
     * @param phone номер телефона в формате +7XXXXXXXXXX
     * @return true если номер валиден (российский, правильной длины)
     * @throws IllegalArgumentException если номер невалиден
     */
    public void validateRussianPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Номер телефона не может быть пустым");
        }

        // Убираем пробелы, дефисы и скобки для нормализации
        String normalized = phone.replaceAll("[\\s\\-\\(\\)]", "");

        // Проверяем базовый формат: +7 и 10 цифр
        if (!RUSSIAN_PHONE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    String.format("Неверный формат номера телефона. Ожидается: +7XXXXXXXXXX (11 цифр после +7), получено: %s", phone)
            );
        }

        // Проверяем, что это не казахстанский номер (исключаем коды 7XX)
        // Российские номера обычно начинаются с +79 (мобильные) или +73, +74, +75, +78 и т.д.
        // Казахстанские: +77XX...
        String afterCountryCode = normalized.substring(2); // убираем +7
        char firstDigit = afterCountryCode.charAt(0);
        
        // Казахстанские номера начинаются с 7 после +7 (т.е. +77XX...)
        if (firstDigit == '7') {
            throw new IllegalArgumentException(
                    "Номер телефона не является российским. Поддерживаются только номера РФ (+7, но не начинающиеся с +77)"
            );
        }

        log.debug("Phone validation passed: {}", normalized);
    }

    /**
     * Нормализует номер телефона (убирает пробелы, дефисы, скобки).
     * 
     * @param phone исходный номер
     * @return нормализованный номер
     */
    public String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        return phone.replaceAll("[\\s\\-\\(\\)]", "");
    }
}







