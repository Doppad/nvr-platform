package com.nvr.authservice.config;

import com.nvr.authservice.exception.InvalidOtpException;
import com.nvr.authservice.exception.InvalidPhoneException;
import com.nvr.authservice.exception.SmsSendException;
import com.nvr.authservice.exception.UserNotRegisteredException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "BAD_REQUEST",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> tooMany(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "TOO_MANY_REQUESTS",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<?> invalidOtp(InvalidOtpException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "INVALID_OTP",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(UserNotRegisteredException.class)
    public ResponseEntity<?> userNotRegistered(UserNotRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "USER_NOT_REGISTERED",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InvalidPhoneException.class)
    public ResponseEntity<?> invalidPhone(InvalidPhoneException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "INVALID_PHONE",
                "message", "Неверный формат номера телефона"
        ));
    }

    @ExceptionHandler(SmsSendException.class)
    public ResponseEntity<?> smsSendFailed(SmsSendException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "SMS_UNAVAILABLE",
                "message", "Служба SMS временно недоступна. Попробуйте позже."
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> responseStatus(ResponseStatusException ex) {
        // Определяем код ошибки на основе HTTP статуса
        String code;
        if (ex.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
            code = "BAD_REQUEST";
        } else if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            code = "NOT_FOUND";
        } else if (ex.getStatusCode().value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            code = "INTERNAL_ERROR";
        } else {
            code = "ERROR";
        }
        
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", code,
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> server(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "INTERNAL_ERROR",
                "message", "Произошла ошибка. Попробуйте позже."
        ));
    }
}
