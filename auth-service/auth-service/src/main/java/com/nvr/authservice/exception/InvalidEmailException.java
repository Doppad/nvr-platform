package com.nvr.authservice.exception;

/**
 * Ошибка некорректного формата email при запросе OTP.
 */
public class InvalidEmailException extends RuntimeException {
    public InvalidEmailException(String message) {
        super(message);
    }
}
