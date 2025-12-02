package com.nvr.authservice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());      // CSRF нужен для браузерных форм при cookie-сессиях. В API на JWT чаще всего стейтлесс (без сессий), и CSRF не нужен -> отключаем
        http.authorizeHttpRequests(reg -> reg
                .requestMatchers("/auth/otp/**", "/auth/token/refresh", "/actuator/**").permitAll()
                .requestMatchers("/auth/me", "/billing/**").authenticated()
                .anyRequest().authenticated()
        );
        http.httpBasic(Customizer.withDefaults()); // временно можно оставить
        http.addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
