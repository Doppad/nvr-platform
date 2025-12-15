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



    /**
     * Регистрация нового пользователя.
     *
     * @param phone телефон (обязательный)
     * @param firstName имя
     * @param lastName фамилия
     * @param middleName отчество
     * @param addressId ID адреса (опциональный, можно привязать позже)
     * @return данные зарегистрированного пользователя
     */
    @Transactional
    public RegisterResponse register(String phone, String firstName, String lastName, String middleName, Long addressId) {
        // Проверяем, не существует ли уже пользователь с таким телефоном
        if (userRepo.findByPhone(phone).isPresent()) {
            throw new IllegalArgumentException("User with phone " + phone + " already exists");
        }

        // Формируем fullName из частей (для совместимости)
        String fullName = buildFullName(firstName, lastName, middleName);

        // Создаем пользователя с сохранением всех полей
        AppUser user = AppUser.builder()
                .phone(phone)
                .email(null) // email не используется
                .fullName(fullName) // legacy поле
                .firstName(firstName)
                .lastName(lastName)
                .middleName(middleName)
                .addressId(addressId) // сохраняем addressId при регистрации
                .build();

        user = userRepo.save(user);

        return new RegisterResponse(
                user.getId(),
                user.getPhone(),
                user.getFirstName(),
                user.getLastName(),
                user.getMiddleName(),
                user.getAddressId()
        );
    }

    /**
     * Собирает fullName из firstName, lastName, middleName.
     */
    private String buildFullName(String firstName, String lastName, String middleName) {
        StringBuilder sb = new StringBuilder();
        
        if (lastName != null && !lastName.isBlank()) {
            sb.append(lastName);
        }
        if (firstName != null && !firstName.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(firstName);
        }
        if (middleName != null && !middleName.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(middleName);
        }
        
        return sb.length() > 0 ? sb.toString() : null;
    }

    private AppUser findOrCreate(String emailOrPhone) {
        // Поддержка только телефона (email убран)
        // Если передан email - все равно считаем это телефоном
        String phone = emailOrPhone;
        return userRepo.findByPhone(phone)
                .orElseGet(() -> userRepo.save(
                        AppUser.builder()
                                .phone(phone)
                                .email(null) // email не используется
                                .build()
                ));
    }

    /**
     * Обновляет addressId для пользователя.
     *
     * @param userId ID пользователя
     * @param addressId ID адреса
     */
    @Transactional
    public void updateAddressId(Long userId, Long addressId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setAddressId(addressId);
        userRepo.save(user);
    }

    /**
     * Ответ на регистрацию.
     */
    public record RegisterResponse(
            Long userId,
            String phone,
            String firstName,
            String lastName,
            String middleName,
            Long addressId  // возвращаем сохраненный addressId
    ) {}
}
