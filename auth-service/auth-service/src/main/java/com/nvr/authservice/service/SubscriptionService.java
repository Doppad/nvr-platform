package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.SubscriptionPlanRepository;
import com.nvr.authservice.repo.UserSubscriptionCameraRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor

// Определяем, какой архив должен быть у пользователя в целом
// нет подписок -> базовый архив = 14 дней
// CAM_1 -> архив = 30 дней на 1 камеру
// CAM_3 -> архив = 30 дням для 3 камер

public class SubscriptionService {
    private final UserSubscriptionRepository userSubscriptionRepo;
    private final SubscriptionPlanRepository planRepo;
    private final UserSubscriptionCameraRepository userSubscriptionCameraRepo;
    private final AppUserRepository appUserRepo;    //чтобы удобно было работать по userId

    /**
     * это основной метод класса: возвращаем клеймы для JWT по пользователю.
     *
     * Логика:
     * - если нет платных подписок -> считаем, то у него план FREE, archiveDays = 14
     * - если есть подписки (CAM_1/CAM_3) -> берем ту, у которой самый большой archiveDays
     * (сейчас это всегда 30), кладем ее в план и archiveDays.
     *
     * Пока не учитываем конкретные камеры - это след. шаг
     */
    public Map<String, Object> claimsForUser(AppUser user) {
        Instant now = Instant.now();

        //Достаем все активные и не истекшие подписки пользователя
        List<UserSubscription> active = userSubscriptionRepo.findByUserAndActiveIsTrueAndEndsAtAfter(user, now);

        // если платных нет - возвр. FREE план
        if (active.isEmpty()) {
            var free = planRepo.findByCode("FREE").orElseThrow(() -> new IllegalStateException("FREE план отсутствует в БД"));

            Map<String, Object> claims = new java.util.HashMap<>();
            claims.put("plan", free.getCode());                 // "FREE"
            claims.put("archiveDays", free.getArchiveDays());   // 14
            claims.put("role", user.getRole() == null ? "USER" : user.getRole());
            // Добавляем addressId пользователя в JWT claims (переход к глобальным Address)
            if (user.getAddressId() != null) {
                claims.put("addressId", user.getAddressId());
            }
            return claims;
        }

        // Если подписок несколько - выбираем самую сильную по глубине архива
        UserSubscription bestSub = active.stream()
                .max(Comparator.comparing(sub -> sub.getPlan().getArchiveDays()))
                .orElseThrow();

        var plan = bestSub.getPlan();

        // Сейчас CAM_1 и CAM_3 оба дают нам 30 дней
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("plan", plan.getCode());
        claims.put("archiveDays", plan.getArchiveDays());
        claims.put("role", user.getRole() == null ? "USER" : user.getRole());
        // Добавляем addressId пользователя в JWT claims (переход к глобальным Address)
        if (user.getAddressId() != null) {
            claims.put("addressId", user.getAddressId());
        }
        return claims;
    }

    public Map<String, Object> claimsForUser(long userId) {
        AppUser user = appUserRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return claimsForUser(user);
    }
}