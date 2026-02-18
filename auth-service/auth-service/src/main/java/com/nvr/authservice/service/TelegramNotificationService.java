package com.nvr.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Сервис отправки OTP в Telegram (для отладки).
 * Активен только при app.telegram.enabled=true И app.email.enabled=false И app.sms.enabled=false.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${app.email.enabled:true} && !${app.sms.enabled:false}")
@RequiredArgsConstructor
public class TelegramNotificationService implements NotificationService {

    private final RestTemplate restTemplate;

    @Value("${app.telegram.bot-token:}") private String botToken;
    @Value("${app.telegram.chat-id:}") private String chatId;

    @Override
    public void sendOtp(String target, String code) {
        String text = "OTP для " + target + ": " + code;
        log.info("Sending OTP to Telegram: target={}, code={}", target, code);

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("chat_id", chatId, "text", text);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("OTP sent to Telegram");
            } else {
                log.warn("Telegram sendMessage non-2xx: {}", resp.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to send OTP to Telegram: {}", e.getMessage());
        }
    }
}
