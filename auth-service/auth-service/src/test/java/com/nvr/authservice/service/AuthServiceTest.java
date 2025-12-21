package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.RefreshToken;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.web.AuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AppUserRepository userRepo;
    private OtpService otpService;
    private JwtService jwtService;
    private SubscriptionService subscriptionService;
    private RefreshTokenService refreshTokenService;
    private PhoneValidationService phoneValidationService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        otpService = mock(OtpService.class);
        jwtService = mock(JwtService.class);
        subscriptionService = mock(SubscriptionService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        phoneValidationService = mock(PhoneValidationService.class);

        authService = new AuthService(
                userRepo,
                otpService,
                jwtService,
                subscriptionService,
                refreshTokenService,
                phoneValidationService
        );

        ReflectionTestUtils.setField(authService, "accessTtlMinutes", 60L);
    }

    @Test
    void verifyOtp_happyPath_returnsTokenPairAndUsesDependencies() {
        // given
        String target = "+79995553311";
        String code = "123456";
        String userAgent = "JUnit";
        String ip = "127.0.0.1";

        // Мокируем валидацию и нормализацию телефона
        doNothing().when(phoneValidationService).validateRussianPhone(target);
        when(phoneValidationService.normalizePhone(target)).thenReturn(target);

        when(otpService.verify(target, code)).thenReturn(true);
        AppUser user = new AppUser();
        user.setId(1L);
        user.setPhone(target);

        when(userRepo.findByPhone(target)).thenReturn(Optional.of(user));
        Map<String, Object> claims = Map.of(
                "plan", "CAM_1",
                "archiveDays", 30
        );
        when(subscriptionService.claimsForUser(user)).thenReturn(claims);
        when(jwtService.issueToken(1L, claims)).thenReturn("jwt-token-123");

        RefreshToken refreshToken = RefreshToken.builder()
                .token("refresh-token-xyz")
                .userId(1L)
                .build();
        when(refreshTokenService.createToken(1L, userAgent, ip)).thenReturn(refreshToken);

        // when
        AuthController.TokenPairResp resp = authService.verifyOtp(target, code, userAgent, ip);

        // then
        assertThat(resp.getAccessToken()).isEqualTo("jwt-token-123");
        assertThat(resp.getRefreshToken()).isEqualTo("refresh-token-xyz");
        assertThat(resp.getExpiresIn()).isEqualTo(60L * 60L);
        assertThat(resp.getJwt()).isEqualTo("jwt-token-123");

        verify(phoneValidationService).validateRussianPhone(target);
        verify(phoneValidationService).normalizePhone(target);
        verify(otpService).verify(target, code);
        verify(subscriptionService).claimsForUser(user);
        verify(jwtService).issueToken(1L, claims);
        verify(refreshTokenService).createToken(1L, userAgent, ip);
        verifyNoMoreInteractions(jwtService, subscriptionService, otpService, refreshTokenService, phoneValidationService);
        verify(userRepo, never()).save(any());
    }
}
