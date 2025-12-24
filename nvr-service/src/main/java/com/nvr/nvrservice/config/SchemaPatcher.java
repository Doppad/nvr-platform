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

        // Запасной вариант для новых полей статусов камер (если миграция не сработала)
        ensureCameraColumn("has_camera", "BOOLEAN DEFAULT false");
        ensureCameraColumn("nvr_status", "VARCHAR(16) DEFAULT 'UNKNOWN'");
        ensureCameraColumn("rtsp_status", "VARCHAR(16) DEFAULT 'NONE'");
        ensureCameraColumn("nvr_status_updated_at", "TIMESTAMPTZ");
        ensureCameraColumn("rtsp_status_updated_at", "TIMESTAMPTZ");
    }

    private void ensureColumn(String ddl, String columnName) {
        try {
            jdbcTemplate.execute(ddl);
            log.debug("Ensured nvr_device.{} column exists", columnName);
        } catch (Exception ex) {
            log.warn("Failed to ensure nvr_device.{} column: {}", columnName, ex.getMessage());
        }
    }

    private void ensureCameraColumn(String columnName, String columnType) {
        try {
            String ddl = String.format(
                    "ALTER TABLE nvr_camera ADD COLUMN IF NOT EXISTS %s %s",
                    columnName, columnType
            );
            jdbcTemplate.execute(ddl);
            log.debug("Ensured nvr_camera.{} column exists", columnName);
            
            // Если это has_camera, заполняем значениями
            if ("has_camera".equals(columnName)) {
                jdbcTemplate.update("""
                    UPDATE nvr_camera
                    SET has_camera = CASE
                        WHEN ip_address IS NOT NULL AND ip_address != '' THEN true
                        WHEN device_name IS NOT NULL AND device_name != '' THEN true
                        WHEN channel_name IS NOT NULL AND channel_name != '' THEN true
                        WHEN name IS NOT NULL AND name != '' AND name !~ '^Channel\\s*\\d+$' THEN true
                        ELSE false
                    END
                    WHERE has_camera IS NULL
                    """);
                
                // Устанавливаем дефолтное значение для оставшихся NULL
                jdbcTemplate.update("UPDATE nvr_camera SET has_camera = false WHERE has_camera IS NULL");
                
                // Пытаемся сделать NOT NULL
                try {
                    jdbcTemplate.execute("ALTER TABLE nvr_camera ALTER COLUMN has_camera SET NOT NULL");
                    jdbcTemplate.execute("ALTER TABLE nvr_camera ALTER COLUMN has_camera SET DEFAULT false");
                } catch (Exception e) {
                    log.debug("Could not set has_camera as NOT NULL, leaving as nullable: {}", e.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to ensure nvr_camera.{} column: {}", columnName, ex.getMessage());
        }
    }
}

