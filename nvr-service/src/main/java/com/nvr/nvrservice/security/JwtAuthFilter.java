package com.nvr.nvrservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Value("${app.jwt.secret}")
    private String secret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Нормализуем заголовок: убираем лишние пробелы и проверяем регистр
        String normalizedHeader = authHeader != null ? authHeader.trim() : null;

        if (normalizedHeader != null && normalizedHeader.length() > 7 
                && normalizedHeader.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            final String jwt = normalizedHeader.substring(7).trim();
            try {
                var key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(key)
                        .setAllowedClockSkewSeconds(60) // терпим ±60 сек
                        .build()
                        .parseClaimsJws(jwt)
                        .getBody();

                log.debug("JWT OK. sub={}, plan={}, maxCameras={}, exp={}", 
                        claims.getSubject(), claims.get("plan"), 
                        claims.get("maxCameras"), claims.getExpiration());

                // --- извлекаем userId: sub -> userId -> uid
                Long userId = null;
                String sub = claims.getSubject();
                if (sub != null && !sub.isBlank()) {
                    try { userId = Long.valueOf(sub); } catch (NumberFormatException ignored) {}
                }
                if (userId == null) {
                    Object u1 = claims.get("userId");
                    if (u1 instanceof Number n) userId = n.longValue();
                    else if (u1 instanceof String s) try { userId = Long.valueOf(s); } catch (NumberFormatException ignored) {}
                }
                if (userId == null) {
                    Object u2 = claims.get("uid");
                    if (u2 instanceof Number n) userId = n.longValue();
                    else if (u2 instanceof String s) try { userId = Long.valueOf(s); } catch (NumberFormatException ignored) {}
                }

                String plan = claims.get("plan", String.class);
                Integer maxCameras = claims.get("maxCameras", Integer.class);
                Integer archiveDays = claims.get("archiveDays", Integer.class);
                String role = claims.get("role", String.class);
                // Извлекаем addressId из JWT claims (переход к глобальным Address)
                Long addressId = null;
                Object addressIdObj = claims.get("addressId");
                if (addressIdObj instanceof Number n) {
                    addressId = n.longValue();
                } else if (addressIdObj instanceof String s) {
                    try {
                        addressId = Long.valueOf(s);
                    } catch (NumberFormatException ignored) {}
                }
                // if (maxCameras == null) maxCameras = null; // или просто не трогать
                if (archiveDays == null) archiveDays = 14;

                if (userId != null) {
                    var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    // Не трогаем WebAuthenticationDetailsSource, чтобы не перезатирать.
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Кладём наш контекст в request-атрибут (забираем его в сервисах при необходимости)
                    // addressId теперь включен в UserContext для доступа к глобальным Address
                    request.setAttribute("userContext", new UserContext(userId, role, plan, maxCameras, archiveDays, addressId));
                } else {
                    log.debug("JWT parsed but userId is NULL (sub={})", claims.getSubject());
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.warn("JWT validation failed for {}: {} - {}", 
                        request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage());
                SecurityContextHolder.clearContext();
            }
        } else if (authHeader != null) {
            // Если заголовок Authorization есть, но не в формате Bearer - логируем для отладки
            log.debug("Authorization header found but not in Bearer format for {}: {}", 
                    request.getRequestURI(), authHeader.substring(0, Math.min(20, authHeader.length())));
        }

        chain.doFilter(request, response);
    }
}
