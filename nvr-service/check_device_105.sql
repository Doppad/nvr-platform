-- Проверка устройства 105 и исправление проблем

-- 1. Проверка устройства 105
SELECT 
    id, 
    name, 
    ip, 
    port, 
    http_port, 
    vendor, 
    cameras_count,
    owner_id,
    created_at
FROM nvr_device 
WHERE id = 105;

-- 2. Проверка пользователей для устройства 105
SELECT 
    id, 
    device_id, 
    role, 
    username, 
    created_at
FROM nvr_device_user 
WHERE device_id = 105;

-- 3. Если vendor не установлен или не "Dahua" - исправить
UPDATE nvr_device 
SET vendor = 'Dahua'
WHERE id = 105 AND (vendor IS NULL OR vendor != 'Dahua');

-- 4. Если нет пользователей - нужно добавить через API или скопировать из другого устройства
-- Проверка других устройств с тем же IP для копирования пользователей
SELECT 
    d.id as device_id,
    d.name,
    u.role,
    u.username
FROM nvr_device d
JOIN nvr_device_user u ON u.device_id = d.id
WHERE d.ip = '81.23.151.25'::inet
  AND d.id != 105
ORDER BY d.id, u.role;

-- 5. Финальная проверка
SELECT 
    d.id, 
    d.name, 
    d.vendor, 
    d.ip,
    COUNT(u.id) as users_count,
    STRING_AGG(u.role, ', ') as roles
FROM nvr_device d
LEFT JOIN nvr_device_user u ON u.device_id = d.id
WHERE d.id = 105
GROUP BY d.id, d.name, d.vendor, d.ip;

