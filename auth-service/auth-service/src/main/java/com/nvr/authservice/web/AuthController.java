package com.nvr.authservice.web;

import com.nvr.authservice.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {       // выдача токена
    private final AuthService authService;

    @PostMapping("/otp/request") // Запрос кода, отдаёт {message}
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest req) {
        String msg = authService.requestOtp(req.emailOrPhone);
        return ResponseEntity.ok(new Msg(msg));
    }

    @PostMapping("/otp/verify") // Подтверждение кода и выдача токенов (access + refresh)
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerify req,
                                       HttpServletRequest request) {

        String userAgent = request.getHeader("User-Agent");
        String ip = request.getRemoteAddr();

        TokenPairResp tokens = authService.verifyOtp(
                req.emailOrPhone,
                req.code,
                userAgent,
                ip
        );

        return ResponseEntity.ok(tokens);
    }

    // ---- NEW: refresh endpoint ----
    @PostMapping("/token/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshReq req) {
        TokenPairResp tokens = authService.refreshAccessToken(req.refreshToken);
        return ResponseEntity.ok(tokens);
    }

    /**
     * Регистрация нового пользователя.
     * Создает пользователя с телефоном и ФИО.
     * addressId опциональный - можно привязать адрес позже.
     */
    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ResponseEntity<?> register(@jakarta.validation.Valid @RequestBody RegisterRequest req) {
        var response = authService.register(
                req.phone,
                req.firstName,
                req.lastName,
                req.middleName,
                req.addressId
        );
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    // ---- DTO ----
    @Data public static class OtpRequest { public String emailOrPhone; }
    @Data public static class OtpVerify { public String emailOrPhone; public String code; }
    
    @Data
    public static class RegisterRequest {
        @NotBlank
        private String phone;
        private String firstName;
        private String lastName;
        private String middleName;
        private Long addressId; // опциональный, можно привязать адрес позже
    }

    // старый класс JwtResp можно удалить, он больше не нужен
    // @Data public static class JwtResp { private final String jwt; }

    @Data public static class Msg { private final String message; }

    @Data
    public static class TokenPairResp {
        private final String accessToken;
        private final String refreshToken;
        private final String tokenType = "Bearer";
        private final long expiresIn; // секунд до истечения access-токена

        // для обратной совместимости (старый фронт, который ждёт jwt)
        public String getJwt() {
            return accessToken;
        }
    }

    @Data
    public static class RefreshReq {
        public String refreshToken;
    }
}
