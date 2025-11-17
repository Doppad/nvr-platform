package com.nvr.authservice.repo;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    @Query("""
        select us from UserSubscription us
        join fetch us.plan p
        where us.user.id = :userId
          and us.active = true
          and us.startsAt <= :now
          and us.endsAt > :now
        order by us.endsAt desc
        """)
    Optional<UserSubscription> findActive(@Param("userId") long userId,
                                        @Param("now") OffsetDateTime now
        );

    // Метод возвращает список всех активных подписок пользователя.
    List<UserSubscription> findByUserAndActiveIsTrueAndEndsAtAfter(
            AppUser user,
            Instant now
    );
}