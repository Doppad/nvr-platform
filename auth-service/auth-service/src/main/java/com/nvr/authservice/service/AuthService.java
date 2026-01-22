package com.nvr.authservice.service;

import com.nvr.authservice.domain.RefreshToken;
import com.nvr.authservice.exception.InvalidOtpException;
import com.nvr.authservice.exception.SmsSendException;
import com.nvr.authservice.exception.UserNotRegisteredException;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.web.AuthController;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nvr.authservice.web.AuthController.TokenPairResp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AppUserRepository userRepo;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final SubscriptionService subscriptionService;
    private final RefreshTokenService refreshTokenService;
    private final PhoneValidationService phoneValidationService;
    private final AddressValidationService addressValidationService;

    @Value("${app.jwt.ttl-minutes}")
    private long accessTtlMinutes;

    @Transactional
    public String requestOtp(String emailOrPhone) {
        // Валидируем номер телефона (формат, длина, только РФ)
        phoneValidationService.validateRussianPhone(emailOrPhone);
        String normalizedPhone = phoneValidationService.normalizePhone(emailOrPhone);
        
        // НЕ проверяем регистрацию при запросе OTP
        // Проверка будет при verifyOtp - там вернется USER_NOT_REGISTERED если пользователь не зарегистрирован
        // OTP создаётся без userId, только по target (телефону)
        try {
            String code = otpService.createAndSaveOtp(null, normalizedPhone);
            // Отправка SMS/Telegram происходит через NotificationService (OtpService.sendOtp)
            // Если app.sms.enabled=true -> отправляется реальное SMS
            // Если app.telegram.enabled=true -> отправляется в Telegram
            // Иначе -> логируется (dev режим)
            return "OTP sent";
        } catch (SmsSendException e) {
            // Если SMS не отправилось - пробрасываем исключение для обработки в контроллере
            // Контроллер вернет 503 Service Unavailable
            throw e;
        }
    }

    @Transactional
    public TokenPairResp verifyOtp(String emailOrPhone, String code, String userAgent, String ip) {
        // 1. Валидируем и нормализуем номер телефона
        phoneValidationService.validateRussianPhone(emailOrPhone);
        String normalizedPhone = phoneValidationService.normalizePhone(emailOrPhone);
        
        // 2. Проверяем OTP (используем нормализованный номер)
        boolean ok = otpService.verify(normalizedPhone, code);
        if (!ok) {
            throw new InvalidOtpException("Invalid or expired OTP");
        }

        // 3. Ищем пользователя по телефону (используем нормализованный номер)
        AppUser user = userRepo.findByPhone(normalizedPhone)
                .orElseThrow(() -> new UserNotRegisteredException("User with phone " + normalizedPhone + " is not registered. Please register first."));

        // 4. Генерим access-token с реальными клеймами из подписки
        Map<String, Object> claims = subscriptionService.claimsForUser(user);
        String accessToken = jwtService.issueToken(user.getId(), claims);

        // 5. Создаем refresh-token
        RefreshToken refresh = refreshTokenService.createToken(
                user.getId(),
                userAgent,
                ip
        );

        // 6. TTL access токена (в секундах)
        long expiresIn = TimeUnit.MINUTES.toSeconds(accessTtlMinutes);

        // 7. Возвращаем пару токенов
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
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - Если addressId не передан - можно назначить позже через админку
     * - При регистрации рекомендуется передавать addressId явно
     *
     * @param phone телефон (обязательный)
     * @param firstName имя
     * @param lastName фамилия
     * @param middleName отчество
     * @param addressId ID адреса (опциональный, можно привязать позже через админку)
     * @return данные зарегистрированного пользователя
     */
    @Transactional
    public RegisterResponse register(String phone, String firstName, String lastName, String middleName, Long addressId) {
        // Валидируем и нормализуем номер телефона
        phoneValidationService.validateRussianPhone(phone);
        String normalizedPhone = phoneValidationService.normalizePhone(phone);
        
        // Проверяем существование адреса ПЕРЕД созданием пользователя
        // Если адрес не найден - возвращаем ошибку, пользователь НЕ создаётся
        if (addressId != null) {
            addressValidationService.validateAddressExists(addressId);
        }
        
        Optional<AppUser> existing = userRepo.findByPhone(normalizedPhone);
        
        AppUser user;
        if (existing.isPresent()) {
            user = existing.get();
            // Если пользователь существует, но не зарегистрирован (firstName/lastName == null)
            if (user.getFirstName() == null && user.getLastName() == null) {
                // Обновляем данные пользователя
                // Проверка адреса уже выполнена выше (строка 141), поэтому здесь просто присваиваем
                String fullName = buildFullName(firstName, lastName, middleName);
                user.setFullName(fullName);
                user.setFirstName(firstName);
                user.setLastName(lastName);
                user.setMiddleName(middleName);
                if (addressId != null) {
                    user.setAddressId(addressId);
                }
                user = userRepo.save(user);
            } else {
                // Пользователь уже зарегистрирован
                throw new IllegalArgumentException("User with phone " + normalizedPhone + " already exists and is registered");
            }
        } else {
            // Создаем нового пользователя
            String fullName = buildFullName(firstName, lastName, middleName);
            user = AppUser.builder()
                    .phone(normalizedPhone)
                    .email(null) // email не используется
                    .fullName(fullName) // legacy поле
                    .firstName(firstName)
                    .lastName(lastName)
                    .middleName(middleName)
                    .addressId(addressId) // сохраняем addressId при регистрации
                    .build();
            user = userRepo.save(user);
        }

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

    /**
     * @deprecated Больше не используется для логина. Оставлен только для внутренних целей, если понадобится.
     * Для логина используйте явную проверку существования пользователя.
     */
    @Deprecated
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
