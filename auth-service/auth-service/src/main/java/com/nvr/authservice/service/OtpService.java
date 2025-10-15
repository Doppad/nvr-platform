package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.OtpAttempt;
import com.nvr.authservice.repo.OtpAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class OtpService {
    private final OtpAttemptRepository otpRepo;

    @Value("${app.otp.ttl-minutes:5}")
    private int otpTtl;

    public String createAndSaveOtp(AppUser user, String target) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        String hash = BCrypt.hashpw(code, BCrypt.gensalt());    // хэширует BCrypt

        OtpAttempt attempt = OtpAttempt.builder()
                .userId(user != null ? user.getId() : null)
                .target(target)
                .codeHash(hash)
                .expiresAt(OffsetDateTime.now().plusMinutes(otpTtl))
                .build();

        otpRepo.save(attempt);
        return code;
    }

    public boolean verify(String target, String code) {
        return otpRepo.findTopByTargetAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                target, OffsetDateTime.now()
        ).map(tryMe -> {
            boolean ok = BCrypt.checkpw(code,tryMe.getCodeHash());
            if (ok) { tryMe.setIsUsed(true); otpRepo.save(tryMe); }
            return ok;
        }).orElse(false);
    }
}
