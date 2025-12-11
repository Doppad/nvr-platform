-- Проверка и обновление http_port для устройства 81.23.151.25
-- Проблема: устройство использует порт 8082, но в БД может быть NULL или 80

-- 1. Проверка текущего значения
SELECT id, name, ip, port, http_port, vendor, cameras_count 
FROM nvr_device 
WHERE ip = '81.23.151.25';

-- 2. Обновление http_port на 8082 для устройства 81.23.151.25
-- (если http_port IS NULL или не равен 8082)
UPDATE nvr_device 
SET http_port = 8082 
WHERE ip = '81.23.151.25'::inet 
  AND (http_port IS NULL OR http_port != 8082);

-- 3. Проверка после обновления
SELECT id, name, ip, port, http_port, vendor, cameras_count 
FROM nvr_device 
WHERE ip = '81.23.151.25';

