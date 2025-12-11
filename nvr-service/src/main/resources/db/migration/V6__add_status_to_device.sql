-- Добавление поля status для отслеживания статуса устройства (ONLINE / OFFLINE / UNKNOWN)
ALTER TABLE nvr_device
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'UNKNOWN';

-- Устанавливаем значение по умолчанию для существующих записей
UPDATE nvr_device
SET status = 'UNKNOWN'
WHERE status IS NULL;



