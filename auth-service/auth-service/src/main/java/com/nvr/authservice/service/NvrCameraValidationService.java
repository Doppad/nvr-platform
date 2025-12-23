package com.nvr.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сервис для проверки принадлежности камер пользователю через nvr-service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NvrCameraValidationService {

    private final RestTemplate restTemplate;

    @Value("${app.nvr-service.base-url:http://localhost:8082}")
    private String nvrServiceBaseUrl;

    /**
     * Проверяет, что все камеры принадлежат указанному пользователю.
     *
     * @param userId ID пользователя
     * @param cameraIds список ID камер для проверки
     * @throws ResponseStatusException если какие-то камеры не принадлежат пользователю
     */
    public void validateCameraOwnership(Long userId, List<Long> cameraIds) {
        if (cameraIds == null || cameraIds.isEmpty()) {
            return; // Пустой список - валидация не нужна
        }

        try {
            String url = nvrServiceBaseUrl + "/api/cameras/validate-ownership";
            ValidateOwnershipRequest request = new ValidateOwnershipRequest(userId, cameraIds);

            log.debug("Validating camera ownership: UserId={}, CameraIds={}", userId, cameraIds);
            ValidateOwnershipResponse response = restTemplate.postForObject(
                    url, request, ValidateOwnershipResponse.class
            );

            if (response == null) {
                log.error("Null response from nvr-service for camera validation: UserId={}, CameraIds={}", userId, cameraIds);
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to validate camera ownership: null response from nvr-service"
                );
            }

            if (!response.valid) {
                String invalidIds = response.invalidIds != null && !response.invalidIds.isEmpty()
                        ? response.invalidIds.toString()
                        : "unknown";
                log.warn("Camera ownership validation failed: UserId={}, InvalidCameraIds={}", userId, invalidIds);
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Some cameras do not belong to user. Invalid camera IDs: " + invalidIds
                );
            }

            log.debug("Camera ownership validation successful: UserId={}, CameraIds={}", userId, cameraIds);
        } catch (ResponseStatusException e) {
            throw e; // Пробрасываем наши исключения
        } catch (Exception e) {
            log.error("Error validating camera ownership: UserId={}, CameraIds={}, Error={}",
                    userId, cameraIds, e.getMessage(), e);
            // TODO: RISK - временно разрешаем, если nvr-service недоступен
            // В продакшене это должно быть строгой проверкой
            log.warn("RISK: Allowing camera purchase without validation due to nvr-service error. " +
                    "This should be fixed before production!");
            // throw new ResponseStatusException(
            //         HttpStatus.INTERNAL_SERVER_ERROR,
            //         "Failed to validate camera ownership: " + e.getMessage()
            // );
        }
    }

    // DTO для запроса
    public record ValidateOwnershipRequest(Long userId, List<Long> cameraIds) {}

    // DTO для ответа
    public record ValidateOwnershipResponse(Boolean valid, List<Long> invalidIds) {}
}





