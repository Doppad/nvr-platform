package com.nvr.authservice.service;

import com.nvr.authservice.repo.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SubscriptionService {
    private final UserSubscriptionRepository repo;

    public Map<String,Object> claimsForUser(long userId) {
        var now = OffsetDateTime.now();
        return repo.findActive(userId, now)
                .<Map<String,Object>>map(us -> Map.of(
                        "plan", us.getPlan().getCode(),
                        "archiveDays", us.getPlan().getArchiveDays(),
                        "maxCameras", us.getPlan().getMaxCameras()))
                .orElseGet(() -> Map.of(
                        "plan", "FREE",
                        "archiveDays", 14,
                        "maxCameras", 1));
    }
}