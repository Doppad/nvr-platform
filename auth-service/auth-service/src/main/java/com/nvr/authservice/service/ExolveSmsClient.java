package com.nvr.authservice.service;

/**
 * Клиент для отправки SMS через MTS Exolve.
 * Используется только при app.sms.enabled=true.
 */
public interface ExolveSmsClient {
    void sendSms(String destination, String text);
}
