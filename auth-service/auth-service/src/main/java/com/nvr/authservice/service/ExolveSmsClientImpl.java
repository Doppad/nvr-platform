package com.nvr.authservice.service;

import com.nvr.authservice.exception.SmsSendException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;

/**
 * Реализация клиента MTS Exolve на основе WebClient.
 *
 * Требует настроек:
 *   exolve.base-url
 *   exolve.api-key
 *   exolve.sender
 */
@Slf4j
@Service
public class ExolveSmsClientImpl implements ExolveSmsClient {

    private final WebClient.Builder webClientBuilder;
    private WebClient webClient;

    @Value("${exolve.base-url:https://api.exolve.ru}")
    private String baseUrl;

    @Value("${exolve.api-key}")
    private String apiKey;

    @Value("${exolve.sender}")
    private String sender;

    public ExolveSmsClientImpl(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        // Жёсткая проверка конфигурации, чтобы сервис не стартовал с некорректными настройками
        if (apiKey == null || apiKey.isBlank()
                || sender == null || sender.isBlank()
                || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Exolve config is missing: base-url, api-key and sender must be set");
        }

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);

        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
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

