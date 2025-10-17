package com.nvr.authservice.web;

import com.nvr.authservice.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/otp/verify") // Подтверждение кода и выдача токена, отдаёт {jwt}
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerify req) {
        String jwt = authService.verifyOtp(req.emailOrPhone, req.code);
        return ResponseEntity.ok(new JwtResp(jwt));
    }

    @Data public static class OtpRequest { public String emailOrPhone; }
    @Data public static class OtpVerify { public String emailOrPhone; public String code; }
    @Data public static class JwtResp { private final String jwt; }
    @Data public static class Msg { private final String message; }
}
