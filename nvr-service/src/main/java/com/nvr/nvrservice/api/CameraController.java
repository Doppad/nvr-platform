package com.nvr.nvrservice.api;

import com.nvr.nvrservice.api.dto.ChannelDto;
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
        log.debug("Validating camera ownership: UserId={}, CameraIds={}", 
                request.userId, request.cameraIds);

        if (request.userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "userId is required"
            );
        }

        if (request.cameraIds == null || request.cameraIds.isEmpty()) {
            return ResponseEntity.ok(new ValidateOwnershipResponse(true, List.of()));
        }

        // Проверяем, что все камеры принадлежат указанному пользователю
        // Камера принадлежит пользователю, если device.ownerId == userId
        List<Long> invalidIds = request.cameraIds.stream()
                .filter(cameraId -> {
                    return cameraRepo.findById(cameraId)
                            .map(camera -> {
                                // Загружаем device с ownerId (lazy loading)
                                Long ownerId = camera.getDevice().getOwnerId();
                                boolean isValid = ownerId.equals(request.userId);
                                if (!isValid) {
                                    log.debug("Camera {} belongs to user {}, but requested user is {}", 
                                            cameraId, ownerId, request.userId);
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

    /**
     * Получает все камеры текущего пользователя.
     *
     * @return список всех камер пользователя
     */
    @GetMapping
    public ResponseEntity<List<ChannelDto>> getAllCameras() {
        List<ChannelDto> cameras = deviceService.getAllCameras();
        return ResponseEntity.ok(cameras);
    }

    /**
     * Получает все камеры устройств, привязанных к указанному адресу.
     *
     * @param addressId ID адреса
     * @return список камер по адресу
     */
    @GetMapping("/by-address/{addressId}")
    public ResponseEntity<List<ChannelDto>> getCamerasByAddress(@PathVariable Long addressId) {
        List<ChannelDto> cameras = deviceService.getCamerasByAddress(addressId);
        return ResponseEntity.ok(cameras);
    }

    @Data
    public static class ValidateOwnershipRequest {
        private Long userId;
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

