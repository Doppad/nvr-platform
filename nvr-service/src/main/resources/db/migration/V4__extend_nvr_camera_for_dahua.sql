-- Расширение таблицы nvr_camera для поддержки Dahua API
ALTER TABLE nvr_camera
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45),
    ADD COLUMN IF NOT EXISTS port INTEGER,
    ADD COLUMN IF NOT EXISTS device_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS channel_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS protocol VARCHAR(32),
    ADD COLUMN IF NOT EXISTS type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rtsp_url TEXT,
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS status_updated_at TIMESTAMPTZ;

-- Индекс для быстрого поиска активных каналов
CREATE INDEX IF NOT EXISTS idx_nvr_camera_device_active ON nvr_camera(device_id, is_active);
CREATE INDEX IF NOT EXISTS idx_nvr_camera_status ON nvr_camera(status, status_updated_at);


