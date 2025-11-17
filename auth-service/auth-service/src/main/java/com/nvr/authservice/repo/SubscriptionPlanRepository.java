package com.nvr.authservice.repo;

import com.nvr.authservice.domain.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    // найти план по его текущему уникальному коду
    Optional<SubscriptionPlan> findByCode(String code);
}
