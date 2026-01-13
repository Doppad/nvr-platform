package com.nvr.authservice.service;

/**
 * Клиент для отправки SMS через MTS Exolve.
 *
 * Инкапсулирует низкоуровневый HTTP-вызов.
 */
public interface ExolveSmsClient {

    /**
     * Отправляет SMS на указанный номер.
     *
     * @param destination нормализованный номер телефона (без '+', если так требует API, или в нужном формате)
     * @param text        текст сообщения (может содержать OTP)
     * @throws com.nvr.authservice.exception.SmsSendException при любой ошибке отправки
     */
    void sendSms(String destination, String text);
}

