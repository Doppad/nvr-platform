-- Обновление http_port для устройства 104 (Тест: 81.23.151.25)
-- Проблема: httpPort = null, поэтому используется дефолтный порт 80 вместо 8082

-- 1. Проверка текущего состояния
SELECT id, name, ip, port, http_port, vendor 
FROM nvr_device 
WHERE id = 104;

-- 2. Обновление http_port на 8082
UPDATE nvr_device 
SET http_port = 8082 
WHERE id = 104;

-- 3. Проверка после обновления
SELECT id, name, ip, port, http_port, vendor 
FROM nvr_device 
WHERE id = 104;

