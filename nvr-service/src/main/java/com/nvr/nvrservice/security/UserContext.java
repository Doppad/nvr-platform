// com.nvr.nvrservice.security.UserContext.java
package com.nvr.nvrservice.security;

/**
 * Контекст аутентифицированного пользователя, который мы вытаскиваем из JWT.
 *
 * role        — роль пользователя (USER, SUPER_ADMIN и т.п.)
 * plan        — тарифный план подписки (FREE, PRO, ...)
 * maxCameras  — лимит камер, допускаемый подпиской (может быть null для "без лимита")
 * archiveDays — глубина архива
 */
public record UserContext(
        Long userId,
        String role,
        String plan,
        Integer maxCameras,
        Integer archiveDays
) {}
