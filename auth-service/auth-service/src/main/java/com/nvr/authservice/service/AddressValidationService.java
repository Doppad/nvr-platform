package com.nvr.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Сервис для проверки существования адреса через nvr-service.
 * Используется при регистрации пользователя.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressValidationService {

    private final RestTemplate restTemplate;

    @Value("${app.nvr-service.base-url:http://localhost:8082}")
    private String nvrServiceBaseUrl;

    /**
     * Проверяет существование адреса по ID.
     * 
     * @param addressId ID адреса для проверки
     * @throws ResponseStatusException если адрес не найден
     */
    public void validateAddressExists(Long addressId) {
        if (addressId == null) {
            return; // null addressId разрешён (можно назначить позже)
        }

        try {
            // Используем публичный эндпоинт для проверки существования адреса
            String url = nvrServiceBaseUrl + "/nvr/addresses/" + addressId + "/exists";
            
            log.debug("Validating address existence: AddressId={}", addressId);
            
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                
                if (response != null && Boolean.TRUE.equals(response.get("exists"))) {
                    log.debug("Address exists: AddressId={}", addressId);
                } else {
                    log.warn("Address not found: AddressId={}", addressId);
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Адрес с таким ID не найден. Проверьте корректность ID адреса."
                    );
                }
            } catch (HttpClientErrorException.NotFound e) {
                log.warn("Address not found: AddressId={}", addressId);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Адрес с таким ID не найден. Проверьте корректность ID адреса."
                );
            }
        } catch (ResponseStatusException e) {
            throw e; // Пробрасываем наши исключения
        } catch (Exception e) {
            log.error("Error validating address {}: {}", addressId, e.getMessage(), e);
            // В случае ошибки подключения к nvr-service - блокируем регистрацию
            // Это важно для безопасности: лучше не создать пользователя, чем создать с невалидным адресом
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось проверить адрес. Попробуйте позже."
            );
        }
    }
}
