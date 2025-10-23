package com.nvr.authservice.service;

public interface NotificationService {
    void sendOtp(String target, String code);
}