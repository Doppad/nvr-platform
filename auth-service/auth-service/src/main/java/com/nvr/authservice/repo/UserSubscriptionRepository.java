package com.nvr.authservice.repo;

import com.nvr.authservice.domain.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {
    @Query("""
        select us from UserSubscription us
        join fetch us.plan p
        where us.userId = :userId
          and us.isActive = true
          and us.startsAt <= :now and us.endsAt > :now
        order by us.endsAt desc
        """)
    Optional<UserSubscription> findActive(long userId, OffsetDateTime now);     // чтобы искать активную подписку пользователя на текущий момент
}
