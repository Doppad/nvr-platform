-- Добавление поля http_port для HTTP API запросов к NVR
ALTER TABLE nvr_device
    ADD COLUMN IF NOT EXISTS http_port INTEGER;

-- Устанавливаем значение по умолчанию для существующих записей
-- Если http_port не задан, используем стандартный порт 80
UPDATE nvr_device
SET http_port = 80
WHERE http_port IS NULL;

