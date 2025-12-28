package com.nvr.authservice.config;

import com.nvr.authservice.exception.InvalidOtpException;
import com.nvr.authservice.exception.SmsSendException;
import com.nvr.authservice.exception.UserNotRegisteredException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

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

    @ExceptionHandler(SmsSendException.class)
    public ResponseEntity<?> smsSendFailed(SmsSendException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "SMS_UNAVAILABLE",
                "message", "SMS service is temporarily unavailable. Please try again later."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> server(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "INTERNAL_ERROR",
                "message", "Unexpected error"
        ));
    }
}
