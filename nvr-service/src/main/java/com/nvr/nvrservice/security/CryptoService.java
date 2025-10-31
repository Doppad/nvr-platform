package com.nvr.nvrservice.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Service
public class CryptoService {

    // читаем из application.yml по пути nvr.crypto.masterKeyBase64
    @Value("${nvr.crypto.masterKeyBase64}")
    private String masterKeyBase64;

    private SecretKeySpec secretKey;

    @PostConstruct
    private void init() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            // если ключ меньше 32 байт — дополним нулями
            if (keyBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
                keyBytes = padded;
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Invalid master key configuration", e);
        }
    }

    public String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(plain.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Encrypt error: " + e.getMessage(), e);
        }
    }

    public String decrypt(String enc) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decoded = Base64.getDecoder().decode(enc);
            return new String(cipher.doFinal(decoded));
        } catch (Exception e) {
            throw new RuntimeException("Decrypt error: " + e.getMessage(), e);
        }
    }
}
