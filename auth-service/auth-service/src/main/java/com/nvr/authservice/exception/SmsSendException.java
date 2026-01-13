package com.nvr.authservice.exception;

/**
 * Исключение, выбрасываемое при ошибке отправки SMS.
 * Используется для предотвращения создания OTP, если SMS не отправилось.
 */
public class SmsSendException extends RuntimeException {
    public SmsSendException(String message) {
        super(message);
    }

    public SmsSendException(String message, Throwable cause) {
        super(message, cause);
    }
}





