package com.nvr.authservice.service;

import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.domain.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AppUserRepository userRepo;
    private final OtpService otpService;
    private final JwtService jwtService;

    // сервис, который достаёт активную подписку пользователя
    private final SubscriptionService subscriptionService;

    @Transactional
    public String requestOtp(String emailOrPhone) {
        AppUser user = findOrCreate(emailOrPhone); // проверяет, есть ли такой пользователь в таблице app_user, если нет - создает нового
        String code = otpService.createAndSaveOtp(user, emailOrPhone); // OtpService сгенерирует 6-значный код, чтобы захэшировать и сохранить в таблицу otp_attempt с дедлайном
        System.out.println("OTP для " + emailOrPhone + ": " + code); // код на этом MVP печатается в лог (в проде полагаю надо SMS/email)
        return "OTP sent (check server log)";
    }

    @Transactional
    public String verifyOtp(String emailOrPhone, String code) {
        boolean ok = otpService.verify(emailOrPhone, code);
        if (!ok) throw new IllegalArgumentException("Invalid or expired OTP");

        AppUser user = findOrCreate(emailOrPhone);
        // берём клеймы (plan, archiveDays, maxCameras) из реальной подписки
        var claims = subscriptionService.claimsForUser(user.getId());
        // выдаём JWT с этими клеймами
        return jwtService.issueToken(user.getId(), claims);
    }

    private AppUser findOrCreate(String emailOrPhone) {
        boolean isEmail = emailOrPhone.contains("@");
        return (isEmail
                ? userRepo.findByEmail(emailOrPhone)
                : userRepo.findByPhone(emailOrPhone))
                .orElseGet(() -> userRepo.save(
                        AppUser.builder()
                                .email(isEmail ? emailOrPhone : null)
                                .phone(isEmail ? null : emailOrPhone)
                                .build()
                ));
    }
}
