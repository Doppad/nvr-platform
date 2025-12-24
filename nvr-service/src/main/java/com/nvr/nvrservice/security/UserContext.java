// com.nvr.nvrservice.security.UserContext.java
package com.nvr.nvrservice.security;

/**
 * Контекст аутентифицированного пользователя, который мы вытаскиваем из JWT.
 *
 * userId      — ID пользователя
 * role        — роль пользователя (USER, SUPER_ADMIN и т.п.)
 * plan        — тарифный план подписки (FREE, PRO, ...)
 * maxCameras  — лимит камер, допускаемый подпиской (может быть null для "без лимита")
 * archiveDays — глубина архива
 * addressId   — ID адреса пользователя (глобальный Address, не привязан к ownerId)
 *              Пользователь видит все NVR/камеры, привязанные к этому addressId.
 *              Может быть null для обратной совместимости (fallback на ownerId).
 */
public record UserContext(
        Long userId,
        String role,
        String plan,
        Integer maxCameras,
        Integer archiveDays,
        Long addressId
) {}
