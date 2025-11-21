package com.nvr.nvrservice.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPatcher {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        ensureColumn("""
                ALTER TABLE nvr_device
                ADD COLUMN IF NOT EXISTS cameras_count INTEGER NOT NULL DEFAULT 0
                """, "cameras_count");

        ensureColumn("""
                ALTER TABLE nvr_device
                ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) NOT NULL DEFAULT 'UTC'
                """, "timezone");
    }

    private void ensureColumn(String ddl, String columnName) {
        try {
            jdbcTemplate.execute(ddl);
            log.info("Ensured nvr_device.{} column exists", columnName);
        } catch (Exception ex) {
            log.warn("Failed to ensure nvr_device.{} column: {}", columnName, ex.getMessage());
        }
    }
}

