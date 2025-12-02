package com.nvr.nvrservice.config;

import com.nvr.nvrservice.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public UserDetailsService adminUserDetailsService(
            @Value("${admin.basic.username:NVR_Admin}") String username,
            @Value("${admin.basic.password:c0Kg6v_BW}") String password
    ) {
        UserDetails admin = User.withUsername(username)
                .password("{noop}" + password) // для простоты: без шифрования пароля
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Отключаем ненужные механизмы
                .csrf(csrf -> csrf.disable())
                // Включаем httpBasic, чтобы защитить /admin/**
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.disable())

                // Настраиваем stateless-сессию (только через JWT)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                )

                // Правила доступа
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",        // системные эндпоинты
                                "/swagger-ui/**",      // Swagger UI
                                "/v3/api-docs/**"      // OpenAPI
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )

                // Подключаем фильтр для JWT
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
