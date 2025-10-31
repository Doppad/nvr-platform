package com.nvr.nvrservice;

import com.nvr.nvrservice.security.CryptoService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NvrServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NvrServiceApplication.class, args);
    }
}
