package com.nvr.authservice.exception;

/**
 * Ошибка некорректного формата номера телефона при запросе OTP/SMS.
 *
 * Используется в слое нотификаций, чтобы отделить ошибки валидации
 * номера (400 INVALID_PHONE) от ошибок отправки SMS (502 SMS_UNAVAILABLE).
 */
public class InvalidPhoneException extends RuntimeException {
    public InvalidPhoneException(String message) {
        super(message);
    }
}

