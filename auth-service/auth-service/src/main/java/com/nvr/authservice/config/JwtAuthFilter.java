package com.nvr.authservice.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class JwtAuthFilter extends OncePerRequestFilter {   // логика проверки JWT

    @Value("${app.jwt.secret}")     // Аннотация @Value берёт значение из application.yml нашего секрета
    private String secret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,     // request - объект с данными запроса
                                    HttpServletResponse response,   // response - что вернётся клиенту
                                    FilterChain filterChain) throws ServletException, IOException {     // filterChain - цепочка фильтров Spring Security
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseClaimsJws(token)
                        .getBody();

                String sub = claims.getSubject();
                if (sub != null) {
                    var auth = new UsernamePasswordAuthenticationToken(sub, null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // Неверный/просроченный токен — оставляю без аутентификации
            }
        }
        filterChain.doFilter(request, response);
    }
}
