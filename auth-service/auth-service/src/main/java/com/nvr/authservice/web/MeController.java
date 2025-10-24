package com.nvr.authservice.web;

import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class MeController {     // Возвращает профиль текущего аутентифицированного пользователя, исходя из токена

    private final AppUserRepository userRepo;
    private final SubscriptionService subscriptionService;

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Long userId = Long.valueOf(auth.getPrincipal().toString());     // Из Authentication достаётся principal, кладется userId
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).build();

        // получаем реальные параметры из подписки
//        Map<String, Object> claims = subscriptionService.claimsForUser(userId);
        Map<String, Object> claims = (auth.getDetails() instanceof Map)
                ? (Map<String, Object>) auth.getDetails()
                : Map.of();

        String planCode = (String) claims.getOrDefault("plan", "FREE");
        int archiveDays = ((Number) claims.getOrDefault("archiveDays", 14)).intValue();
        int maxCameras  = ((Number) claims.getOrDefault("maxCameras", 1)).intValue();

        var resp = new MeResponse(      // Формируется DTO
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                new Plan(planCode, archiveDays, maxCameras)
//                new Plan(
//                        (String) claims.get("plan"),
//                        (Integer) claims.get("archiveDays"),
//                        (Integer) claims.get("maxCameras")
//                )
        );
        return ResponseEntity.ok(resp);
    }

    public record Plan(String code, int archiveDays, int maxCameras) {}
    public record MeResponse(Long id, String email, String phone, Plan plan) {}
}
