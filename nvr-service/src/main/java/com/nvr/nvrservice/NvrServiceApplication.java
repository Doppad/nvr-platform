package com.nvr.nvrservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NvrServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NvrServiceApplication.class, args);
    }
}
