package com.nvr.authservice.web;

import com.nvr.authservice.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

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

    // ---- DTO ----
    @Data public static class OtpRequest { public String emailOrPhone; }
    @Data public static class OtpVerify { public String emailOrPhone; public String code; }

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
