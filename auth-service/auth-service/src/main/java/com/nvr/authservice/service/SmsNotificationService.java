package com.nvr.authservice.service;

import com.nvr.authservice.exception.SmsSendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Сервис для отправки SMS через МТС Exolve API.
 * Используется для отправки OTP кодов пользователям.
 * 
 * Документация: https://docs.exolve.ru/docs/ru/integration-instructions/
 * 
 * ⚠️ ВАЖНО: Exolve SendSMS API требует apifonica_token (короткий токен вида aut*****),
 * а НЕ SSO JWT токен. JWT токены будут отклонены с ошибкой 401.
 * 
 * Приоритет активации:
 * - Если app.sms.enabled=true -> используется этот сервис
 * - Иначе используется TelegramNotificationService или NoopNotificationService
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmsNotificationService implements NotificationService {

    private final RestTemplate restTemplate;

    @Value("${app.sms.api-key}")
    private String apiKey;

    @Value("${app.sms.api-url:https://api.exolve.ru/messaging/v1/SendSMS}")
    private String apiUrl;

    @Value("${app.sms.sender-name:Okodoma}")
    private String senderName;

    /**
     * Инициализация и валидация API ключа при создании бина.
     * Проверяет, что ключ не является JWT токеном.
     */
    @jakarta.annotation.PostConstruct
    public void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("SMS API key is not set!");
            throw new IllegalStateException("SMS API key is not configured");
        }

        String trimmedKey = apiKey.trim();
        
        // Проверка на JWT токен
        if (isJwtToken(trimmedKey)) {
            String keyInfo = formatKeyForLogging(trimmedKey);
            String error = String.format("""
                ⚠️  КРИТИЧЕСКАЯ ОШИБКА КОНФИГУРАЦИИ  ⚠️
                
                Обнаружен JWT токен в APP_SMS_API_KEY, но Exolve SendSMS API требует apifonica_token!
                
                Текущий ключ: %s
                
                Exolve SendSMS API требует apifonica_token (короткий токен вида aut*****),
                а НЕ SSO JWT токен. JWT токены будут отклонены с ошибкой 401.
                
                Для исправления:
                1. Зайдите в личный кабинет Exolve: https://exolve.ru/
                2. Перейдите в раздел "Messaging API" или "API ключи"
                3. Скопируйте apifonica_token (короткий токен, НЕ JWT)
                4. Установите: APP_SMS_API_KEY=ваш-apifonica-token
                
                Приложение НЕ ЗАПУСТИТСЯ до исправления конфигурации.
                """, keyInfo);
            log.error(error);
            throw new IllegalStateException(
                    "Exolve SendSMS requires apifonica_token, not SSO JWT. " +
                    "Please use Messaging API token from Exolve cabinet."
            );
        }

        // Обновляем apiKey на trimmed версию
        this.apiKey = trimmedKey;
        
        // Логируем информацию о ключе (безопасно)
        String keyInfo = formatKeyForLogging(trimmedKey);
        log.info("SMS API key initialized: {}", keyInfo);
    }

    @Override
    public void sendOtp(String target, String code) {
        if (target == null || target.isBlank()) {
            log.warn("Cannot send SMS: target phone number is empty");
            throw new SmsSendException("Phone number is empty");
        }

        if (code == null || code.isBlank()) {
            log.warn("Cannot send SMS: OTP code is empty");
            throw new SmsSendException("OTP code is empty");
        }

        // Сохраняем исходный телефон для логирования
        String rawPhone = target;

        try {
            // Нормализуем телефон в формат E.164 для Exolve API
            String normalizedPhone = normalizePhoneForExolve(target);
            if (normalizedPhone == null) {
                log.warn("Invalid phone format for SMS: {}", maskPhone(rawPhone));
                throw new SmsSendException("Invalid phone number format: " + maskPhone(rawPhone));
            }

            // Формируем текст сообщения
            String message = String.format("Ваш код подтверждения: %s", code);

            // Формат запроса для МТС Exolve API согласно документации
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Используем уже валидированный и trimmed apiKey
            headers.set("Authorization", "Bearer " + apiKey);

            // JSON body строго по документации Exolve:
            // { "number": <sender>, "destination": <recipient>, "text": <message> }
            Map<String, Object> requestBody = Map.of(
                    "number", senderName,        // отправитель (альфа-имя или номер)
                    "destination", normalizedPhone, // получатель (нормализованный телефон)
                    "text", message              // текст с OTP
            );

            log.debug("Sending SMS to {} via Exolve API", maskPhone(normalizedPhone));

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("SMS sent successfully to {}", maskPhone(normalizedPhone));
            } else {
                // Логируем статус и response body (без OTP и без ключа)
                String responseBody = response.getBody() != null ? response.getBody() : "(empty)";
                String sanitizedBody = sanitizeResponseBody(responseBody, code, apiKey);
                log.warn("SMS API returned non-2xx status: {} for phone {}. Response body: {}", 
                        response.getStatusCode(), maskPhone(normalizedPhone), sanitizedBody);
                // Бросаем SmsSendException чтобы API вернул 503
                throw new SmsSendException(
                        String.format("SMS API returned non-2xx status: %s", response.getStatusCode())
                );
            }

        } catch (SmsSendException e) {
            // Пробрасываем наши исключения
            throw e;
        } catch (Exception e) {
            // Оборачиваем другие исключения в SmsSendException
            log.error("Failed to send SMS to {}: {}", maskPhone(rawPhone), e.getMessage());
            throw new SmsSendException("Failed to send SMS: " + e.getMessage(), e);
        }
    }

    /**
     * Нормализует номер телефона в формат E.164 для Exolve API.
     * 
     * Правила нормализации:
     * - Убирает все символы кроме цифр и +
     * - Если начинается с 8XXXXXXXXXX -> заменяет на +7XXXXXXXXXX
     * - Если начинается с 7XXXXXXXXXX -> заменяет на +7XXXXXXXXXX
     * - Если уже +7XXXXXXXXXX -> оставляет как есть
     * - Иначе возвращает null (ошибка)
     * 
     * @param phone исходный номер телефона
     * @return нормализованный номер в формате E.164 (+7XXXXXXXXXX) или null при ошибке
     */
    private String normalizePhoneForExolve(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }

        // Убираем все символы кроме цифр и +
        String digitsOnly = phone.replaceAll("[^0-9+]", "");

        if (digitsOnly.startsWith("+7")) {
            // Уже в формате +7XXXXXXXXXX
            if (digitsOnly.length() == 12) { // +7 + 10 цифр
                return digitsOnly;
            }
            return null; // Неправильная длина
        } else if (digitsOnly.startsWith("8") && digitsOnly.length() == 11) {
            // 8XXXXXXXXXX -> +7XXXXXXXXXX
            return "+7" + digitsOnly.substring(1);
        } else if (digitsOnly.startsWith("7") && digitsOnly.length() == 11) {
            // 7XXXXXXXXXX -> +7XXXXXXXXXX
            return "+7" + digitsOnly.substring(1);
        } else {
            // Неизвестный формат
            return null;
        }
    }

    /**
     * Маскирует номер телефона для безопасного логирования.
     * Пример: +79001234567 -> +7900***4567
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        int visibleStart = Math.min(4, phone.length() - 7);
        int visibleEnd = Math.max(phone.length() - 4, visibleStart + 3);
        return phone.substring(0, visibleStart) + "***" + phone.substring(visibleEnd);
    }

    /**
     * Очищает response body от чувствительных данных (OTP и API ключа) перед логированием.
     * 
     * @param responseBody исходный response body
     * @param otpCode OTP код для удаления
     * @param apiKey API ключ для удаления
     * @return очищенный response body
     */
    private String sanitizeResponseBody(String responseBody, String otpCode, String apiKey) {
        if (responseBody == null || responseBody.isBlank()) {
            return responseBody;
        }
        
        String sanitized = responseBody;
        
        // Удаляем OTP код, если он присутствует
        if (otpCode != null && !otpCode.isBlank()) {
            sanitized = sanitized.replace(otpCode, "***");
        }
        
        // Удаляем API ключ, если он присутствует
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "***");
            // Также маскируем части ключа, если он разбит на части
            if (apiKey.length() > 8) {
                String keyPrefix = apiKey.substring(0, Math.min(4, apiKey.length()));
                String keySuffix = apiKey.substring(Math.max(0, apiKey.length() - 4));
                sanitized = sanitized.replace(keyPrefix, "****");
                sanitized = sanitized.replace(keySuffix, "****");
            }
        }
        
        return sanitized;
    }

    /**
     * Проверяет, является ли строка JWT токеном.
     * JWT токены начинаются с "eyJ" (base64-encoded {") и содержат точки.
     * 
     * @param key строка для проверки
     * @return true если это похоже на JWT токен
     */
    private boolean isJwtToken(String key) {
        if (key == null || key.length() < 10) {
            return false;
        }
        // JWT токены начинаются с "eyJ" (base64 для {"alg":...) и содержат минимум 2 точки
        String trimmed = key.trim();
        return trimmed.startsWith("eyJ") && trimmed.contains(".") && 
               trimmed.split("\\.").length >= 3; // JWT имеет 3 части: header.payload.signature
    }

    /**
     * Форматирует ключ для безопасного логирования.
     * Показывает только тип, длину, первые 4 и последние 4 символа.
     * 
     * @param key API ключ
     * @return безопасная строка для логирования
     */
    private String formatKeyForLogging(String key) {
        if (key == null || key.isBlank()) {
            return "empty";
        }
        String trimmed = key.trim();
        String type = isJwtToken(trimmed) ? "JWT" : "API token";
        int length = trimmed.length();
        
        if (length <= 8) {
            // Слишком короткий - показываем только маску
            return String.format("%s, length=%d, value=****", type, length);
        } else {
            // Показываем первые 4 и последние 4 символа
            String prefix = trimmed.substring(0, 4);
            String suffix = trimmed.substring(length - 4);
            return String.format("%s, length=%d, value=%s...%s", type, length, prefix, suffix);
        }
    }
}

