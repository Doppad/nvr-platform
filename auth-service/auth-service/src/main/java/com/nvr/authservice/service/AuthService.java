package com.nvr.authservice.service;

import com.nvr.authservice.domain.RefreshToken;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.web.AuthController;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nvr.authservice.web.AuthController.TokenPairResp;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AppUserRepository userRepo;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final SubscriptionService subscriptionService;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.jwt.ttl-minutes}")
    private long accessTtlMinutes;

    @Transactional
    public String requestOtp(String emailOrPhone) {
        AppUser user = findOrCreate(emailOrPhone); // проверяет, есть ли такой пользователь в таблице app_user, если нет - создает нового
        String code = otpService.createAndSaveOtp(user, emailOrPhone); // OtpService сгенерирует 6-значный код, чтобы захэшировать и сохранить в таблицу otp_attempt с дедлайном
        System.out.println("OTP для " + emailOrPhone + ": " + code); // код на этом MVP печатается в лог (в проде полагаю надо SMS/email)
        return "OTP sent (check server log)";
    }

    @Transactional
    public TokenPairResp verifyOtp(String emailOrPhone, String code, String userAgent, String ip) {

        // 1. Проверяем OTP
        boolean ok = otpService.verify(emailOrPhone, code);
        if (!ok) throw new IllegalArgumentException("Invalid or expired OTP");

        // 2. Находим или создаём пользователя
        AppUser user = findOrCreate(emailOrPhone);

        // 3. Генерим access-token с реальными клеймами из подписки
        Map<String, Object> claims = subscriptionService.claimsForUser(user);
        String accessToken = jwtService.issueToken(user.getId(), claims);

        // 4. Создаем refresh-token
        RefreshToken refresh = refreshTokenService.createToken(
                user.getId(),
                userAgent,
                ip
        );

        // 5. TTL access токена (в секундах)
        long expiresIn = TimeUnit.MINUTES.toSeconds(accessTtlMinutes);

        // 6. Возвращаем пару токенов
        return new TokenPairResp(accessToken, refresh.getToken(), expiresIn);
    }

    @Transactional
    public AuthController.TokenPairResp refreshAccessToken(String refreshToken) {

        // 1. Проверяем refresh токен
        RefreshToken stored = refreshTokenService.validate(refreshToken);

        // 2. Находим пользователя
        AppUser user = userRepo.findById(stored.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // 3. Создаем новый access-token с клеймами
        Map<String, Object> claims = subscriptionService.claimsForUser(user);
        String newAccess = jwtService.issueToken(user.getId(), claims);

        long expiresIn = TimeUnit.MINUTES.toSeconds(accessTtlMinutes);

        // 4. Возвращаем новую пару токенов
        return new AuthController.TokenPairResp(
                newAccess,
                refreshToken, // refresh не меняем, он долгоживущий
                expiresIn
        );
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
