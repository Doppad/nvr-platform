package com.nvr.nvrservice.web;

import com.nvr.nvrservice.service.NvrSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Контроллер для синхронизации NVR устройств.
 * Предоставляет альтернативный путь /api/admin/nvrs/{id}/sync
 */
@RestController
@RequestMapping("/api/admin/nvrs")
@RequiredArgsConstructor
public class NvrSyncController {

    private final NvrSyncService syncService;

    /**
     * Получает ID пользователя из заголовка X-Admin-User
     */
    private Long getAdminUserId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String val = attrs.getRequest().getHeader("X-Admin-User");
        if (val == null) throw new RuntimeException("Missing header X-Admin-User");
        return Long.valueOf(val);
    }

    /**
     * Прикрепляет пользователя к контексту безопасности
     */
    private void attachAdminUser(Long userId) {
        var auth = new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Синхронизация каналов устройства.
     * Альтернативный путь: POST /api/admin/nvrs/{id}/sync
     * 
     * @param id ID устройства
     * @return результат синхронизации
     */
    @PostMapping("/{id}/sync")
    public ResponseEntity<?> syncDeviceChannels(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        try {
            syncService.syncDeviceChannels(id);
            return ResponseEntity.ok("Synchronization started");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Sync failed: " + e.getMessage());
        }
    }
}

