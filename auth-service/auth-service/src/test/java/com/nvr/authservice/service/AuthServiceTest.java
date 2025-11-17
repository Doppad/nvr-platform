package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AppUserRepository userRepo;
    private OtpService otpService;
    private JwtService jwtService;
    private SubscriptionService subscriptionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        otpService = mock(OtpService.class);
        jwtService = mock(JwtService.class);
        subscriptionService = mock(SubscriptionService.class);

        // ⚠️ тут подставь тот конструктор AuthService, который у тебя реально есть
        authService = new AuthService(
                userRepo,
                otpService,
                jwtService,
                subscriptionService
        );
    }

    @Test
    void verifyOtp_happyPath_returnsJwtAndUsesSubscriptionClaims() {
        // given
        String target = "+79995553311";
        String code = "123456";

        // 1) OTP успешно проходит
        when(otpService.verify(target, code)).thenReturn(true); // или ничего не возвращает, если у тебя метод void с исключениями

        // 2) Пользователь уже существует
        AppUser user = new AppUser();
        user.setId(1L);
        user.setPhone(target);

        when(userRepo.findByPhone(target)).thenReturn(Optional.of(user));
        // если у тебя findOrCreate инкапсулирован, можно замокать его через spy, но давай пока считаем, что поиск идёт так

        // 3) Подписочный сервис вернёт клеймы
        Map<String, Object> claims = Map.of(
                "plan", "CAM_1",
                "archiveDays", 30
        );
        when(subscriptionService.claimsForUser(user)).thenReturn(claims);

        // 4) JwtService вернёт вот такой токен
        when(jwtService.issueToken(1L, claims)).thenReturn("jwt-token-123");

        // when
        String resultJwt = authService.verifyOtp(target, code);

        // then
        assertThat(resultJwt).isEqualTo("jwt-token-123");

        // и проверяем, что все нужные сервисы были вызваны
        verify(otpService).verify(target, code);
        verify(subscriptionService).claimsForUser(user);
        verify(jwtService).issueToken(1L, claims);
        verifyNoMoreInteractions(jwtService, subscriptionService, otpService);
    }
}
