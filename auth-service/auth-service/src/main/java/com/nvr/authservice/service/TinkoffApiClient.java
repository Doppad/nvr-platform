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
     * @param email email покупателя (для Receipt)
     * @param phone телефон покупателя (для Receipt, опционально)
     * @param itemName название товара (для Receipt)
     * @return результат создания платежа
     */
    public TinkoffInitResponse initPayment(
            Long amountMinor,
            String orderId,
            String successUrl,
            String failUrl,
            String description,
            String email,
            String phone,
            String itemName
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

        // Формируем Receipt для фискализации
        Map<String, Object> receipt = new HashMap<>();
        
        // Email или Phone покупателя (обязательно хотя бы одно для Receipt)
        if (email != null && !email.isBlank()) {
            receipt.put("Email", email);
        }
        if (phone != null && !phone.isBlank()) {
            receipt.put("Phone", phone);
        }
        
        // Обязательное поле Taxation (система налогообложения)
        receipt.put("Taxation", "usn_income");
        
        // Items (список товаров)
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("Name", itemName != null && !itemName.isBlank() ? itemName : description);
        item.put("Price", amountMinor);
        item.put("Quantity", 1);
        item.put("Amount", amountMinor);
        item.put("Tax", "none"); // НДС не облагается (или "vat10", "vat20" в зависимости от вашего случая)
        items.add(item);
        receipt.put("Items", items);
        
        requestData.put("Receipt", receipt);

        // Формируем подпись (Token)
        String token = generateToken(requestData);
        requestData.put("Token", token);

        try {
            String url = API_BASE_URL + "/Init";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Логируем запрос перед отправкой
            log.info("TINKOFF INIT REQUEST: {}", objectMapper.writeValueAsString(requestData));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestData, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            String responseBody = response.getBody();
            
            // Логируем ответ после получения
            log.info("TINKOFF INIT RESPONSE: {}", responseBody);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Tinkoff Init failed: status={}, body={}", response.getStatusCode(), responseBody);
                throw new RuntimeException("Failed to create payment: " + response.getStatusCode());
            }

            JsonNode jsonResponse = objectMapper.readTree(responseBody);
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
     * 
     * Алгоритм согласно документации Тинькофф:
     * 1. Берем только top-level поля запроса
     * 2. Исключаем поля "Token" и "Receipt" из расчета
     * 3. Сортируем поля по ключу (алфавитно)
     * 4. Склеиваем значения в строку
     * 5. Добавляем Password в конец
     * 6. Вычисляем SHA-256 от итоговой строки
     *
     * @param data данные запроса (может содержать Token и Receipt, они будут исключены)
     * @return подпись в hex формате
     */
    private String generateToken(Map<String, Object> data) {
        // Создаем копию данных без Token и Receipt
        Map<String, Object> dataForToken = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            // Исключаем Token и Receipt из расчета подписи
            if (!"Token".equals(key) && !"Receipt".equals(key)) {
                Object value = entry.getValue();
                // Убеждаемся, что числовые значения (Amount, Price) представлены как Long (integer)
                // Это важно для корректной конкатенации
                if (value instanceof Number) {
                    // Преобразуем Number в Long для единообразия (Amount всегда в копейках - integer)
                    if (value instanceof Long) {
                        dataForToken.put(key, value);
                    } else if (value instanceof Integer) {
                        dataForToken.put(key, ((Integer) value).longValue());
                    } else {
                        // Для других Number типов (Double, BigDecimal) - преобразуем в Long
                        // Это важно, так как Amount должен быть integer в копейках
                        log.warn("Converting Number {} to Long for token calculation: key={}, value={}", 
                                value.getClass().getSimpleName(), key, value);
                        dataForToken.put(key, ((Number) value).longValue());
                    }
                } else {
                    dataForToken.put(key, value);
                }
            }
        }
        
        // Добавляем Password в конец (после сортировки)
        // Сортируем по ключу (алфавитно) - TreeMap автоматически сортирует
        Map<String, Object> sortedData = new TreeMap<>(dataForToken);
        sortedData.put("Password", password);

        // Конкатенируем все значения в порядке сортировки ключей
        StringBuilder sb = new StringBuilder();
        for (Object value : sortedData.values()) {
            if (value != null) {
                sb.append(value);
            }
        }

        // Вычисляем SHA-256
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Выполняет возврат средств по платежу.
     * Использует API Tinkoff /v2/Refund.
     *
     * @param paymentId идентификатор платежа в системе Тинькофф
     * @param amount сумма возврата в минимальных единицах (копейки). Если null - полный возврат
     * @return результат возврата средств
     */
    public TinkoffRefundResponse refundPayment(String paymentId, Long amount) {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("TerminalKey", terminalKey);
        requestData.put("PaymentId", paymentId);
        if (amount != null && amount > 0) {
            requestData.put("Amount", amount);
        }

        // Формируем подпись (Token)
        String token = generateToken(requestData);
        requestData.put("Token", token);

        try {
            String url = API_BASE_URL + "/Cancel";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestData, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Tinkoff Refund failed: status={}, body={}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to refund payment: " + response.getStatusCode());
            }

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            boolean success = jsonResponse.get("Success").asBoolean();
            String errorCode = jsonResponse.has("ErrorCode") ? jsonResponse.get("ErrorCode").asText() : null;
            String message = jsonResponse.has("Message") ? jsonResponse.get("Message").asText() : null;
            String status = jsonResponse.has("Status") ? jsonResponse.get("Status").asText() : null;
            Long refundAmount = jsonResponse.has("Amount") ? jsonResponse.get("Amount").asLong() : null;

            if (!success) {
                log.error("Tinkoff Refund error: ErrorCode={}, Message={}", errorCode, message);
                throw new RuntimeException("Tinkoff refund failed: " + message);
            }

            log.info("Tinkoff refund successful: PaymentId={}, Amount={}, Status={}", paymentId, refundAmount, status);

            return new TinkoffRefundResponse(success, paymentId, status, refundAmount, message);

        } catch (Exception e) {
            log.error("Error calling Tinkoff Refund API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to refund Tinkoff payment: " + e.getMessage(), e);
        }
    }

    /**
     * Проверяет подпись (Token) в webhook от Тинькофф.
     * Алгоритм такой же, как при генерации: все поля кроме Token и Receipt + Password, сортировка, конкатенация, SHA256.
     *
     * @param data данные из webhook (включая Token, может включать Receipt)
     * @param receivedToken токен, полученный в webhook
     * @return true, если подпись валидна
     */
    public boolean verifyToken(Map<String, Object> data, String receivedToken) {
        if (receivedToken == null || receivedToken.isBlank()) {
            log.warn("Received empty token in webhook");
            return false;
        }

        // generateToken уже исключает Token и Receipt, поэтому просто передаем все данные
        // Генерируем ожидаемый токен (Token и Receipt будут автоматически исключены)
        String expectedToken = generateToken(data);

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

    /**
     * Ответ от метода Refund.
     */
    public record TinkoffRefundResponse(
            boolean success,
            String paymentId,
            String status,
            Long amount,
            String message
    ) {}
}



