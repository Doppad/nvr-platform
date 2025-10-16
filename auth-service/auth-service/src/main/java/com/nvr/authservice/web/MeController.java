package com.nvr.authservice.web;

import com.nvr.authservice.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final AppUserRepository userRepo;

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return ResponseEntity.status(401).build();
        }
        Long userId = Long.valueOf(auth.getPrincipal().toString());
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).build();

        var resp = new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                new Plan("FREE", 14, 1)
        );
        return ResponseEntity.ok(resp);
    }

    public record Plan(String code, int archiveDays, int maxCameras) {}
    public record MeResponse(Long id, String email, String phone, Plan plan) {}
}
