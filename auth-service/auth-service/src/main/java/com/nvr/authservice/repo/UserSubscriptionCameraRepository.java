package com.nvr.authservice.repo;

import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.subscription.UserSubscriptionCamera;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserSubscriptionCameraRepository extends JpaRepository<UserSubscriptionCamera, Long> {

    // Нахожу все связи по набору подписок
    List<UserSubscriptionCamera> findByUserSubscriptionIn(Iterable<UserSubscription> subscriptions);

    // Все камеры, привязанные к одной подписке
    List<UserSubscriptionCamera> findByUserSubscriptionId(Long userSubscriptionId);

    // Найти активные подписки на конкретную камеру
    @Query("SELECT usc FROM UserSubscriptionCamera usc " +
           "JOIN usc.userSubscription us " +
           "WHERE usc.cameraId = :cameraId " +
           "AND us.active = true " +
           "AND us.endsAt > :now")
    List<UserSubscriptionCamera> findActiveByCameraId(@Param("cameraId") Long cameraId, @Param("now") Instant now);

    // Найти все камеры с активными подписками для пользователя
    @Query("SELECT DISTINCT usc.cameraId FROM UserSubscriptionCamera usc " +
           "JOIN usc.userSubscription us " +
           "WHERE us.user.id = :userId " +
           "AND us.active = true " +
           "AND us.endsAt > :now")
    List<Long> findActiveCameraIdsByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    // Найти активные подписки на конкретную камеру для конкретного пользователя
    @Query("SELECT usc FROM UserSubscriptionCamera usc " +
           "JOIN usc.userSubscription us " +
           "WHERE usc.cameraId = :cameraId " +
           "AND us.user.id = :userId " +
           "AND us.active = true " +
           "AND us.endsAt > :now")
    List<UserSubscriptionCamera> findActiveByCameraIdAndUserId(
            @Param("cameraId") Long cameraId,
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}