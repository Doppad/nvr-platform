package com.nvr.authservice.repo;

import com.nvr.authservice.domain.OtpAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface OtpAttemptRepository extends JpaRepository<OtpAttempt, Long> {
    Optional<OtpAttempt> findTopByTargetAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String target, OffsetDateTime now);

    long countByTargetAndCreatedAtAfter(String target, OffsetDateTime after);
}
