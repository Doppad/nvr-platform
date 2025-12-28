package com.nvr.nvrservice.api;

import com.nvr.nvrservice.api.dto.ChannelDto;
import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrCameraRepo;
import com.nvr.nvrservice.service.NvrDeviceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Контроллер для работы с камерами.
 */
@Slf4j
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final NvrCameraRepo cameraRepo;
    private final NvrDeviceService deviceService;
    private final AddressRepo addressRepo;

    /**
     * Получает все камеры текущего пользователя.
     * Возвращает массив всех камер (каналов) всех устройств пользователя.
     *
     * @return список всех камер пользователя
     */
    @GetMapping
    public ResponseEntity<List<ChannelDto>> getAllCameras() {
        List<ChannelDto> cameras = deviceService.getAllCameras();
        return ResponseEntity.ok(cameras);
    }

    /**
     * Проверяет принадлежность камер пользователю.
     * Используется auth-service для валидации перед созданием платежа.
     *
     * @param request запрос с userId и списком cameraIds
     * @return ответ с результатом проверки
     */
    @PostMapping("/validate-ownership")
    public ResponseEntity<ValidateOwnershipResponse> validateOwnership(
            @RequestBody ValidateOwnershipRequest request
    ) {
        log.debug("Validating camera ownership: UserId={}, AddressId={}, CameraIds={}", 
                request.userId, request.addressId, request.cameraIds);

        if (request.userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "userId is required"
            );
        }

        if (request.cameraIds == null || request.cameraIds.isEmpty()) {
            return ResponseEntity.ok(new ValidateOwnershipResponse(true, List.of()));
        }

        // Используем явно переданный addressId (работает в webhook-контексте без SecurityContext)
        // Если addressId не передан - пытаемся получить через fallback (для обратной совместимости)
        Long userAddressId = request.addressId;
        if (userAddressId == null) {
            // FALLBACK: получаем addressId из БД (для обратной совместимости)
            try {
                List<com.nvr.nvrservice.domain.Address> userAddresses = addressRepo.findByOwnerId(request.userId);
                if (!userAddresses.isEmpty()) {
                    userAddressId = userAddresses.get(0).getId();
                    log.debug("Fallback: Found addressId={} for userId={}", userAddressId, request.userId);
                }
            } catch (Exception e) {
                log.debug("Could not get addressId for userId={}: {}", request.userId, e.getMessage());
            }
        } else {
            log.debug("Using explicit addressId={} from request for userId={}", userAddressId, request.userId);
        }

        // Проверяем, что все камеры принадлежат указанному пользователю
        // Камера принадлежит пользователю, если:
        // 1. (НОВАЯ МОДЕЛЬ) device.addressEntity.id == userAddressId (если userAddressId есть)
        // 2. (FALLBACK) device.ownerId == userId (для обратной совместимости)
        final Long finalUserAddressId = userAddressId;
        List<Long> invalidIds = request.cameraIds.stream()
                .filter(cameraId -> {
                    return cameraRepo.findById(cameraId)
                            .map(camera -> {
                                var device = camera.getDevice();
                                boolean isValid = false;
                                
                                // Приоритет: проверка через addressEntity (новая модель)
                                if (finalUserAddressId != null && device.getAddressEntity() != null) {
                                    Long deviceAddressId = device.getAddressEntity().getId();
                                    isValid = finalUserAddressId.equals(deviceAddressId);
                                    if (!isValid) {
                                        log.debug("Camera {} device addressId={} does not match user addressId={}", 
                                                cameraId, deviceAddressId, finalUserAddressId);
                                    }
                                }
                                
                                // Fallback: проверка через ownerId (для обратной совместимости)
                                if (!isValid) {
                                    Long ownerId = device.getOwnerId();
                                    if (ownerId != null) {
                                        isValid = ownerId.equals(request.userId);
                                        if (!isValid) {
                                            log.debug("Camera {} belongs to user {}, but requested user is {}", 
                                                    cameraId, ownerId, request.userId);
                                        }
                                    } else {
                                        // Если ownerId null и addressId не совпал - камера не принадлежит пользователю
                                        log.debug("Camera {} device has no ownerId and addressId mismatch", cameraId);
                                        isValid = false;
                                    }
                                }
                                
                                return !isValid; // Оставляем только невалидные
                            })
                            .orElse(true); // Если камера не найдена - считаем невалидной
                })
                .collect(Collectors.toList());

        // Если есть невалидные - возвращаем их список
        if (!invalidIds.isEmpty()) {
            log.warn("Camera ownership validation failed: UserId={}, InvalidCameraIds={}", 
                    request.userId, invalidIds);
            return ResponseEntity.ok(new ValidateOwnershipResponse(false, invalidIds));
        }

        log.debug("Camera ownership validation successful: UserId={}, CameraIds={}", 
                request.userId, request.cameraIds);
        return ResponseEntity.ok(new ValidateOwnershipResponse(true, List.of()));
    }

    @Data
    public static class ValidateOwnershipRequest {
        private Long userId;
        private Long addressId; // Явный addressId для работы в webhook-контексте без SecurityContext
        private List<Long> cameraIds;
    }

    @Data
    public static class ValidateOwnershipResponse {
        private Boolean valid;
        private List<Long> invalidIds;

        public ValidateOwnershipResponse(Boolean valid, List<Long> invalidIds) {
            this.valid = valid;
            this.invalidIds = invalidIds;
        }
    }
}

