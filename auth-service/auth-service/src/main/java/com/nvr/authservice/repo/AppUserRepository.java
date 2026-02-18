package com.nvr.authservice.repo;

import com.nvr.authservice.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);
    Optional<AppUser> findByPhone(String phone);
    boolean existsByEmail(String email);
}
