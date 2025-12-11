-- Добавление пользователя к устройству 103 (Тест: 81.23.151.25)
-- Проблема: устройство создано без пользователей, поэтому синхронизация не работает

-- 1. Проверка текущего состояния устройства
SELECT id, name, ip, port, http_port, vendor 
FROM nvr_device 
WHERE id = 103;

-- 2. Проверка существующих пользователей для устройства 103
SELECT id, device_id, role, username 
FROM nvr_device_user 
WHERE device_id = 103;

-- 3. Обновление http_port для устройства 103 (если нужно)
UPDATE nvr_device 
SET http_port = 8082 
WHERE id = 103 AND (http_port IS NULL OR http_port != 8082);

-- 4. РЕШЕНИЕ: Добавить пользователя через API (РЕКОМЕНДУЕТСЯ)
-- Используйте PUT запрос к /nvr/devices/{id} с обновлением устройства,
-- или создайте новое устройство через POST /nvr/devices с указанием users в запросе.
--
-- Пример через curl (замените JWT_TOKEN на реальный токен):
-- curl -X PUT http://localhost:8082/nvr/devices/103 \
--   -H "Authorization: Bearer JWT_TOKEN" \
--   -H "Content-Type: application/json" \
--   -d '{
--     "name": "Тест",
--     "ip": "81.23.151.25",
--     "port": 554,
--     "httpPort": 8082,
--     "vendor": "Dahua"
--   }'
--
-- Или обновите устройство через админку: http://localhost:8082/admin/admin.html

-- 5. Альтернатива: Добавление пользователя напрямую в БД (НЕ РЕКОМЕНДУЕТСЯ)
-- Пароль должен быть зашифрован через CryptoService.encrypt() с ключом из application.yml.
-- Для правильного шифрования лучше использовать Java код или API.
--
-- Временное решение (только для теста, небезопасно):
-- Можно скопировать зашифрованный пароль из другого устройства с теми же учетными данными.
-- Например, если у устройства 102 есть пользователь с username='admin' и password='Admin1969',
-- можно скопировать его password_enc:
--
-- INSERT INTO nvr_device_user (device_id, role, username, password_enc, created_at)
-- SELECT 
--     103,
--     'user_admin',
--     'admin',
--     (SELECT password_enc FROM nvr_device_user WHERE device_id = 102 AND role = 'user_admin' LIMIT 1),
--     NOW()
-- WHERE NOT EXISTS (
--     SELECT 1 FROM nvr_device_user 
--     WHERE device_id = 103 AND role = 'user_admin'
-- );

-- 6. Проверка после добавления
SELECT id, device_id, role, username 
FROM nvr_device_user 
WHERE device_id = 103;

-- 7. Финальная проверка
SELECT 
    d.id, 
    d.name, 
    d.ip, 
    d.port, 
    d.http_port, 
    d.vendor,
    COUNT(u.id) as users_count
FROM nvr_device d
LEFT JOIN nvr_device_user u ON u.device_id = d.id
WHERE d.id = 103
GROUP BY d.id, d.name, d.ip, d.port, d.http_port, d.vendor;

