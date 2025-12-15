package com.nvr.authservice.web;

import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import com.nvr.authservice.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class MeController {     // Возвращает профиль текущего аутентифицированного пользователя, исходя из токена

    private final AppUserRepository userRepo;
    private final SubscriptionService subscriptionService;
    private final UserSubscriptionRepository userSubscriptionRepo;

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Long userId = Long.valueOf(auth.getPrincipal().toString());     // Из Authentication достаётся principal, кладется userId
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).build();

        // Получаем список всех активных подписок пользователя с загруженными планами
        List<UserSubscription> activeSubscriptions = userSubscriptionRepo
                .findByUserAndActiveIsTrueAndEndsAtAfterWithPlan(user, Instant.now());
        
        // получаем реальные параметры из подписки
        Map<String, Object> claims = subscriptionService.claimsForUser(userId);

        String planCode = (String) claims.getOrDefault("plan", "FREE");
        int archiveDays = ((Number) claims.getOrDefault("archiveDays", 14)).intValue();
        
        List<SubscriptionInfo> subscriptions = activeSubscriptions.stream()
                .map(sub -> new SubscriptionInfo(
                        sub.getId(),
                        sub.getPlan().getCode(),
                        sub.getPlan().getTitle(),
                        sub.getPlan().getArchiveDays(),
                        sub.getPlan().getCameraQuota() != null ? sub.getPlan().getCameraQuota() : 0,
                        sub.getStartsAt(),
                        sub.getEndsAt(),
                        sub.isActive()
                ))
                .collect(Collectors.toList());

        // maxCameras берем из активных подписок, если есть, иначе 1
        int maxCameras = subscriptions.isEmpty() ? 1 : 
            subscriptions.stream()
                .mapToInt(SubscriptionInfo::cameraQuota)
                .sum();

        var resp = new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getFirstName(),
                user.getLastName(),
                user.getMiddleName(),
                user.getAddressId(), // возвращаем сохраненный addressId
                new Plan(planCode, archiveDays, maxCameras),
                subscriptions
        );
        return ResponseEntity.ok(resp);
    }

    public record Plan(String code, int archiveDays, int maxCameras) {}
    
    public record SubscriptionInfo(
            Long id,
            String planCode,
            String planTitle,
            int archiveDays,
            int cameraQuota,
            Instant startsAt,
            Instant endsAt,
            boolean active
    ) {}
    
    public record MeResponse(
            Long id,
            String email,
            String phone,
            String firstName,
            String lastName,
            String middleName,
            Long addressId, // ID адреса, сохраненный при регистрации
            Plan plan,
            List<SubscriptionInfo> subscriptions
    ) {}
}
