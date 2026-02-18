package com.nvr.authservice.exception;

/**
 * Ошибка некорректного формата номера телефона (используется SMS-каналом).
 */
public class InvalidPhoneException extends RuntimeException {
    public InvalidPhoneException(String message) {
        super(message);
    }
}
