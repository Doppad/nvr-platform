package com.nvr.authservice.repo;

import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.subscription.UserSubscriptionCamera;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserSubscriptionCameraRepository extends JpaRepository<UserSubscriptionCamera, Long> {

    // Нахожу все связи по набору подписок
    List<UserSubscriptionCamera> findByUserSubscriptionIn(Iterable<UserSubscription> subscriptions);

    // Все камеры, привязанные к одной подписке
    List<UserSubscriptionCamera> findByUserSubscriptionId(Long userSubscriptionId);
}