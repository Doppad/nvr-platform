package com.nvr.authservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Клиент для работы с API Тинькофф эквайринга.
 * Документация: https://developer.tbank.ru/eacq/intro/
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TinkoffApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.tinkoff.terminal-key}")
    private String terminalKey;

    @Value("${app.tinkoff.password}")
    private String password;

    private static final String API_BASE_URL = "https://securepay.tinkoff.ru/v2";

    /**
     * Создает платежную сессию в Тинькофф.
     *
     * @param amountMinor сумма в минимальных единицах (копейки)
     * @param orderId номер заказа (уникальный в системе)
     * @param successUrl URL для редиректа после успешной оплаты
     * @param failUrl URL для редиректа после неуспешной оплаты
     * @param description описание платежа
     * @return результат создания платежа
     */
    public TinkoffInitResponse initPayment(
            Long amountMinor,
            String orderId,
            String successUrl,
            String failUrl,
            String description
    ) {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("TerminalKey", terminalKey);
        requestData.put("Amount", amountMinor);
        requestData.put("OrderId", orderId);
        requestData.put("SuccessURL", successUrl);
        requestData.put("FailURL", failUrl);
        if (description != null && !description.isBlank()) {
            requestData.put("Description", description);
        }
        requestData.put("PayType", "O"); // O - одностадийная оплата

        // Формируем подпись (Token)
        String token = generateToken(requestData);
        requestData.put("Token", token);

        try {
            String url = API_BASE_URL + "/Init";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestData, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Tinkoff Init failed: status={}, body={}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to create payment: " + response.getStatusCode());
            }

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            boolean success = jsonResponse.get("Success").asBoolean();
            String errorCode = jsonResponse.has("ErrorCode") ? jsonResponse.get("ErrorCode").asText() : null;
            String message = jsonResponse.has("Message") ? jsonResponse.get("Message").asText() : null;

            if (!success) {
                log.error("Tinkoff Init error: ErrorCode={}, Message={}", errorCode, message);
                throw new RuntimeException("Tinkoff payment creation failed: " + message);
            }

            String paymentId = jsonResponse.get("PaymentId").asText();
            String paymentUrl = jsonResponse.has("PaymentURL") ? jsonResponse.get("PaymentURL").asText() : null;
            String status = jsonResponse.has("Status") ? jsonResponse.get("Status").asText() : null;

            log.info("Tinkoff payment created: PaymentId={}, OrderId={}, Status={}", paymentId, orderId, status);

            return new TinkoffInitResponse(success, paymentId, paymentUrl, status, orderId, amountMinor);

        } catch (Exception e) {
            log.error("Error calling Tinkoff Init API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create Tinkoff payment: " + e.getMessage(), e);
        }
    }

    /**
     * Получает статус платежа.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @return статус платежа
     */
    public TinkoffStateResponse getState(String paymentId) {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("TerminalKey", terminalKey);
        requestData.put("PaymentId", paymentId);

        // Формируем подпись
        String token = generateToken(requestData);
        requestData.put("Token", token);

        try {
            String url = API_BASE_URL + "/GetState";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestData, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Tinkoff GetState failed: status={}, body={}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to get payment state: " + response.getStatusCode());
            }

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            boolean success = jsonResponse.get("Success").asBoolean();
            String errorCode = jsonResponse.has("ErrorCode") ? jsonResponse.get("ErrorCode").asText() : null;
            String message = jsonResponse.has("Message") ? jsonResponse.get("Message").asText() : null;

            if (!success) {
                log.error("Tinkoff GetState error: ErrorCode={}, Message={}", errorCode, message);
                throw new RuntimeException("Tinkoff get state failed: " + message);
            }

            String status = jsonResponse.has("Status") ? jsonResponse.get("Status").asText() : null;
            String orderId = jsonResponse.has("OrderId") ? jsonResponse.get("OrderId").asText() : null;
            Long amount = jsonResponse.has("Amount") ? jsonResponse.get("Amount").asLong() : null;

            return new TinkoffStateResponse(success, paymentId, status, orderId, amount, message);

        } catch (Exception e) {
            log.error("Error calling Tinkoff GetState API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get Tinkoff payment state: " + e.getMessage(), e);
        }
    }

    /**
     * Генерирует подпись (Token) для запроса к API Тинькофф.
     * Алгоритм: сортировка полей, конкатенация значений, SHA256.
     *
     * @param data данные запроса
     * @return подпись
     */
    private String generateToken(Map<String, Object> data) {
        // Добавляем Password в конец
        Map<String, Object> dataWithPassword = new TreeMap<>(data);
        dataWithPassword.put("Password", password);

        // Конкатенируем все значения
        StringBuilder sb = new StringBuilder();
        for (Object value : dataWithPassword.values()) {
            if (value != null) {
                sb.append(value);
            }
        }

        // Вычисляем SHA256
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Проверяет подпись (Token) в webhook от Тинькофф.
     * Алгоритм такой же, как при генерации: все поля кроме Token + Password, сортировка, конкатенация, SHA256.
     *
     * @param data данные из webhook (включая Token)
     * @param receivedToken токен, полученный в webhook
     * @return true, если подпись валидна
     */
    public boolean verifyToken(Map<String, Object> data, String receivedToken) {
        if (receivedToken == null || receivedToken.isBlank()) {
            log.warn("Received empty token in webhook");
            return false;
        }

        // Создаем копию данных без Token
        Map<String, Object> dataWithoutToken = new HashMap<>(data);
        dataWithoutToken.remove("Token");

        // Генерируем ожидаемый токен
        String expectedToken = generateToken(dataWithoutToken);

        boolean isValid = expectedToken.equalsIgnoreCase(receivedToken);
        if (!isValid) {
            log.warn("Token verification failed: expected={}, received={}", expectedToken, receivedToken);
        }
        return isValid;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Ответ от метода Init.
     */
    public record TinkoffInitResponse(
            boolean success,
            String paymentId,
            String paymentUrl,
            String status,
            String orderId,
            Long amount
    ) {}

    /**
     * Ответ от метода GetState.
     */
    public record TinkoffStateResponse(
            boolean success,
            String paymentId,
            String status,
            String orderId,
            Long amount,
            String message
    ) {}
}



