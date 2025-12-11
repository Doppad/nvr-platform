-- Удаление устройств с IP 81.23.151.25 и всех связанных данных
-- Выполните этот SQL в БД для немедленного удаления

-- Сначала удаляем камеры
DELETE FROM nvr_camera 
WHERE device_id IN (
    SELECT id FROM nvr_device WHERE ip = '81.23.151.25'::inet
);

-- Затем удаляем пользователей устройств
DELETE FROM nvr_device_user 
WHERE device_id IN (
    SELECT id FROM nvr_device WHERE ip = '81.23.151.25'::inet
);

-- Наконец удаляем сами устройства
DELETE FROM nvr_device 
WHERE ip = '81.23.151.25'::inet;

-- Проверка: сколько устройств осталось с этим IP (должно быть 0)
SELECT COUNT(*) as remaining_devices FROM nvr_device WHERE ip = '81.23.151.25'::inet;


