package com.nvr.authservice.service;

import com.nvr.authservice.domain.RefreshToken;
import com.nvr.authservice.repo.RefreshTokenRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepo repo;

    @Value("${app.jwt.refresh-ttl-days:30}")
    private int refreshTtlDays;

    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshToken createToken(Long userId, String userAgent, String ip) {
        String token = generateRandomToken();

        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .token(token)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .userAgent(userAgent)
                .ipAddress(ip)
                .build();

        return repo.save(entity);
    }

    public RefreshToken validate(String token) {
        RefreshToken rt = repo.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (!rt.isActive()) {
            throw new IllegalStateException("Refresh token expired or revoked");
        }
        return rt;
    }

    public void revoke(String token) {
        repo.findByToken(token).ifPresent(rt -> {
            rt.setRevokedAt(OffsetDateTime.now());
            repo.save(rt);
        });
    }

    public void revokeAllForUser(Long userId) {
        repo.deleteByUserId(userId);
    }

    private String generateRandomToken() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
