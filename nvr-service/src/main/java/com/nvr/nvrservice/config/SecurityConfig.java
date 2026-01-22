package com.nvr.nvrservice.config;

import com.nvr.nvrservice.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${app.admin.auth.username:admin}")
    private String adminUsername;

    @Value("${app.admin.auth.password:changeme}")
    private String adminPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Используем NoOpPasswordEncoder, так как пароль уже в plain text из ENV
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    public UserDetailsService adminUserDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(adminPassword)  // Пароль уже в plain text из ENV
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    /**
     * SecurityFilterChain для админки с Basic Auth.
     * Order(1) - обрабатывается первым.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(HttpSecurity http, UserDetailsService adminUserDetailsService) throws Exception {
        http
                .securityMatcher("/admin/**", "/admin/api/**", "/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("ADMIN")
                )
                .userDetailsService(adminUserDetailsService);

        return http.build();
    }

    /**
     * SecurityFilterChain для остальных эндпоинтов с JWT.
     * Order(2) - обрабатывается вторым, если первый не подошёл.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",                    // системные эндпоинты
                                "/swagger-ui/**",                 // Swagger UI
                                "/v3/api-docs/**",                // OpenAPI
                                "/nvr/addresses/*/exists"         // публичный эндпоинт для проверки существования адреса (используется при регистрации)
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
