package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.SubscriptionPlan;
import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.SubscriptionPlanRepository;
import com.nvr.authservice.repo.UserSubscriptionCameraRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import com.nvr.authservice.subscription.UserSubscriptionCamera;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
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
    private final NvrCameraValidationService cameraValidationService;

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

    /**
     * Обрабатывает успешный платеж и создает подписку.
     * Идемпотентный метод: проверяет, что у пользователя нет активных подписок на эти камеры.
     *
     * @param userId ID пользователя
     * @param planCode код плана (CAM_1 или CAM_3)
     * @param cameraIds список ID камер для подписки
     * @throws ResponseStatusException с HTTP статусами:
     *         - 404 NOT_FOUND если пользователь или план не найден
     *         - 409 CONFLICT для неконсистентных данных (неверное количество камер, уже есть подписка и т.д.)
     */
    @Transactional
    public void handleSuccessfulPayment(Long userId, String planCode, List<Long> cameraIds) {
        log.info("handleSuccessfulPayment called: UserId={}, PlanCode={}, CameraIds={}", userId, planCode, cameraIds);
        
        AppUser user = appUserRepo.findById(userId)
                .orElseThrow(() -> {
                    log.error("User not found: {}", userId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "User not found: " + userId
                    );
                });

        SubscriptionPlan plan = planRepo.findByCode(planCode)
                .orElseThrow(() -> {
                    log.error("Plan not found: PlanCode={}", planCode);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Plan not found: " + planCode
                    );
                });

        // СТРОГАЯ ВАЛИДАЦИЯ: проверяем размер списка камер в соответствии с планом
        Integer expectedCameraCount = plan.getCameraQuota();
        if (expectedCameraCount == null) {
            log.error("Plan {} has no cameraQuota configured", plan.getCode());
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Plan " + plan.getCode() + " has no cameraQuota configured"
            );
        }

        if (cameraIds == null || cameraIds.isEmpty()) {
            log.error("CameraIds is empty for user {}", userId);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "CameraIds is required and cannot be empty"
            );
        }

        if (cameraIds.size() != expectedCameraCount) {
            log.error("Incorrect camera count. Expected: {}, Actual: {}, CameraIds: {}", 
                    expectedCameraCount, cameraIds.size(), cameraIds);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    String.format("Plan %s requires exactly %d camera(s), but %d provided", 
                            plan.getCode(), expectedCameraCount, cameraIds.size())
            );
        }

        // СТРОГАЯ ВАЛИДАЦИЯ: проверяем принадлежность камер пользователю
        Long userAddressId = user.getAddressId();
        try {
            cameraValidationService.validateCameraOwnership(userId, userAddressId, cameraIds);
            log.info("Camera ownership validated for user {} (addressId={}): CameraIds={}", 
                    userId, userAddressId, cameraIds);
        } catch (ResponseStatusException e) {
            log.error("Camera ownership validation failed for user {} (addressId={}): CameraIds={}, Error: {}", 
                    userId, userAddressId, cameraIds, e.getMessage());
            throw e;
        }

        Instant now = Instant.now();
        Instant endsAt = now.plus(30, ChronoUnit.DAYS); // подписка на 30 дней

        // Проверяем, что у этого пользователя нет активных подписок на эти камеры
        for (Long cameraId : cameraIds) {
            List<UserSubscriptionCamera> existingCameras = userSubscriptionCameraRepo
                    .findActiveByCameraIdAndUserId(cameraId, userId, now);

            if (!existingCameras.isEmpty()) {
                String existingSubscriptionIds = existingCameras.stream()
                        .map(usc -> usc.getUserSubscription().getId().toString())
                        .collect(Collectors.joining(", "));
                log.error("User {} already has active subscription(s) for camera {}: SubscriptionIds={}", 
                        userId, cameraId, existingSubscriptionIds);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        String.format("User already has an active subscription for camera %d. Subscription IDs: %s", 
                                cameraId, existingSubscriptionIds)
                );
            }
        }

        // Создаем подписку
        log.info("Creating subscription: UserId={}, PlanCode={}, StartsAt={}, EndsAt={}", 
                userId, plan.getCode(), now, endsAt);

        UserSubscription subscription = UserSubscription.builder()
                .user(user)
                .plan(plan)
                .startsAt(now)
                .endsAt(endsAt)
                .active(true)
                .build();

        subscription = userSubscriptionRepo.save(subscription);
        log.info("Subscription saved: SubscriptionId={}, UserId={}, PlanCode={}, Active={}, EndsAt={}", 
                subscription.getId(), userId, plan.getCode(), subscription.isActive(), subscription.getEndsAt());

        // Сохраняем subscriptionId в final переменную для использования в lambda
        final Long subscriptionId = subscription.getId();

        // Создаем записи UserSubscriptionCamera для каждой камеры
        int createdCount = 0;
        for (Long cameraId : cameraIds) {
            // Проверяем, не создана ли уже запись (защита от дублей)
            boolean alreadyExists = userSubscriptionCameraRepo.findAll().stream()
                    .anyMatch(usc -> usc.getUserSubscription().getId().equals(subscriptionId)
                            && usc.getCameraId().equals(cameraId));

            if (alreadyExists) {
                log.warn("UserSubscriptionCamera already exists: SubscriptionId={}, CameraId={}", 
                        subscription.getId(), cameraId);
                continue;
            }

            UserSubscriptionCamera subscriptionCamera = UserSubscriptionCamera.builder()
                    .userSubscription(subscription)
                    .cameraId(cameraId)
                    .build();

            userSubscriptionCameraRepo.save(subscriptionCamera);
            createdCount++;
            log.debug("Created UserSubscriptionCamera: SubscriptionId={}, CameraId={}", 
                    subscription.getId(), cameraId);
        }

        log.info("Created {} camera subscription(s) for user {}: Plan={}, SubscriptionId={}, CameraIds={}",
                createdCount, userId, plan.getCode(), subscription.getId(), cameraIds);
    }

    /**
     * Отменяет активную подписку пользователя, связанную с указанным планом.
     * Деактивирует подписку и устанавливает endsAt = now().
     *
     * @param userId ID пользователя
     * @param planCode код плана (CAM_1 или CAM_3)
     * @throws ResponseStatusException если активная подписка не найдена
     */
    @Transactional
    public void cancelSubscription(Long userId, String planCode) {
        log.info("cancelSubscription called: UserId={}, PlanCode={}", userId, planCode);

        AppUser user = appUserRepo.findById(userId)
                .orElseThrow(() -> {
                    log.error("User not found: {}", userId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "User not found: " + userId
                    );
                });

        Instant now = Instant.now();

        // Находим активную подписку пользователя с указанным планом
        List<UserSubscription> activeSubscriptions = userSubscriptionRepo
                .findByUserAndActiveIsTrueAndEndsAtAfterWithPlan(user, now);

        UserSubscription subscriptionToCancel = activeSubscriptions.stream()
                .filter(sub -> planCode.equals(sub.getPlan().getCode()))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("Active subscription not found for user {} with plan {}", userId, planCode);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Active subscription not found for user " + userId + " with plan " + planCode
                    );
                });

        // Деактивируем подписку
        subscriptionToCancel.setActive(false);
        subscriptionToCancel.setEndsAt(now);
        userSubscriptionRepo.save(subscriptionToCancel);

        log.info("Subscription cancelled: SubscriptionId={}, UserId={}, PlanCode={}, EndedAt={}",
                subscriptionToCancel.getId(), userId, planCode, now);
    }
}