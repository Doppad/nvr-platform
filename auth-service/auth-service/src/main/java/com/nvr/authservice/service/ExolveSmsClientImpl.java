package com.nvr.authservice.service;

import com.nvr.authservice.exception.SmsSendException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Реализация клиента MTS Exolve для SMS.
 * Создаётся только при app.sms.enabled=true.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.sms", name = "enabled", havingValue = "true")
public class ExolveSmsClientImpl implements ExolveSmsClient {

    private final WebClient webClient;
    private final String sender;

    public ExolveSmsClientImpl(WebClient.Builder webClientBuilder,
                               @Value("${exolve.base-url:https://api.exolve.ru}") String baseUrl,
                               @Value("${exolve.api-key:}") String apiKey,
                               @Value("${exolve.sender:}") String sender) {
        this.sender = sender;
        if (apiKey == null || apiKey.isBlank() || sender == null || sender.isBlank()) {
            throw new IllegalStateException("Exolve config required when app.sms.enabled=true: exolve.api-key and exolve.sender");
        }
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public void sendSms(String destination, String text) {
        if (destination == null || destination.isBlank()) {
            throw new SmsSendException("Phone number is empty");
        }
        if (text == null || text.isBlank()) {
            throw new SmsSendException("SMS text is empty");
        }

        Map<String, Object> body = Map.of(
                "number", sender,
                "destination", destination,
                "text", text
        );

        try {
            webClient.post()
                    .uri("/messaging/v1/SendSMS")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            response -> response.createException().flatMap(ex -> {
                                int code = response.statusCode().value();
                                log.warn("Exolve SMS API error: HTTP {} for phone {}", code, maskPhone(destination));
                                return Mono.error(new SmsSendException("Exolve SMS API returned HTTP " + code, ex));
                            })
                    )
                    .toBodilessEntity()
                    .block();

            log.info("SMS sent via Exolve to {}", maskPhone(destination));
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send SMS via Exolve to {}: {}", maskPhone(destination), e.getMessage());
            throw new SmsSendException("Failed to send SMS via Exolve", e);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 2) + "***" + phone.substring(phone.length() - 2);
    }
}
