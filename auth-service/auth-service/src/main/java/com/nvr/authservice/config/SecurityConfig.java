package com.nvr.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(reg -> reg
                .requestMatchers("/auth/**", "/actuator/**").permitAll()
                .anyRequest().authenticated()
        );
        http.httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
