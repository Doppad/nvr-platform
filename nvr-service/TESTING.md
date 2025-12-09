# Инструкция по тестированию интеграции с Dahua

## 1. Подготовка базы данных

### Проверка устройства в БД

Выполните SQL-запросы для проверки:

```sql
-- Проверка устройства
SELECT id, name, ip, port, http_port, vendor, cameras_count 
FROM nvr_device 
WHERE ip = '81.23.151.25';

-- Проверка учётной записи
SELECT du.id, du.device_id, du.role, du.username, du.password_enc
FROM nvr_device_user du
JOIN nvr_device d ON d.id = du.device_id
WHERE d.ip = '81.23.151.25';
```

### Если устройство отсутствует, создайте его:

```sql
-- Вставка устройства (замените owner_id на реальный ID пользователя)
INSERT INTO nvr_device (owner_id, name, ip, port, http_port, vendor, cameras_count, timezone, created_at)
VALUES (1, 'Test Dahua NVR', '81.23.151.25', 554, 8082, 'Dahua', 0, 'UTC', NOW())
RETURNING id;

-- Вставка учётной записи (замените device_id на ID из предыдущего запроса)
-- Пароль 'Admin1969' нужно зашифровать через CryptoService
-- Временно можно использовать простой Base64 (небезопасно, только для теста)
INSERT INTO nvr_device_user (device_id, role, username, password_enc, created_at)
VALUES (1, 'user_admin', 'admin', 'QWRtaW4xOTY5', NOW());
```

**Важно:** Пароль должен быть зашифрован через `CryptoService.encrypt()`. Для теста можно использовать временный Base64, но лучше создать через REST API.

## 2. Запуск приложения

```bash
cd nvr-service
mvn spring-boot:run
```

Или через IDE запустите `NvrServiceApplication`.

## 3. Проверка логов

После запуска проверьте логи:

### Автоматическая синхронизация (каждые 5 минут)

Ищите в логах:
```
Starting synchronization of all Dahua devices
Found X Dahua devices to sync
Syncing channels for device X (name: ip)
Fetching channels from: http://81.23.151.25:8082/cgi-bin/devVideoInput.cgi?action=getCollect
Fetched X channels from device X
Successfully synced X channels for device X
```

### Ошибки (если есть):

```
Failed to fetch channels from Dahua device at http://81.23.151.25:8082
Empty response from Dahua device
Received HTML instead of INI from Dahua device
```

## 4. Ручная синхронизация через REST API

### Запуск синхронизации вручную:

```bash
# Замените {deviceId} на реальный ID устройства
# Замените {userId} на ID пользователя (для заголовка X-Admin-User)

curl -X POST http://localhost:8082/admin/api/devices/{deviceId}/sync \
  -H "X-Admin-User: {userId}" \
  -H "Content-Type: application/json"
```

Пример:
```bash
curl -X POST http://localhost:8082/admin/api/devices/1/sync \
  -H "X-Admin-User: 1" \
  -H "Content-Type: application/json"
```

Ожидаемый ответ:
```json
"Synchronization started"
```

## 5. Проверка каналов через REST API

### Получение списка каналов:

```bash
curl -X GET http://localhost:8082/admin/api/devices/{deviceId}/channels \
  -H "X-Admin-User: {userId}"
```

Пример:
```bash
curl -X GET http://localhost:8082/admin/api/devices/1/channels \
  -H "X-Admin-User: 1"
```

Ожидаемый ответ (JSON):
```json
[
  {
    "id": 1,
    "channelNo": 1,
    "name": "Channel 1",
    "status": "ONLINE",
    "ipAddress": "192.168.1.100",
    "port": null,
    "deviceName": "Camera 1",
    "channelName": "Main Stream",
    "protocol": "ONVIF",
    "type": "IP",
    "rtspUrl": "rtsp://admin:Admin1969@81.23.151.25:554/cam/realmonitor?channel=1&subtype=0",
    "isActive": true,
    "statusUpdatedAt": "2024-01-01T12:00:00Z",
    "createdAt": "2024-01-01T12:00:00Z"
  }
]
```

## 6. Проверка данных в БД

### Проверка каналов в БД:

```sql
-- Список всех каналов для устройства
SELECT 
    c.id,
    c.channel_no,
    c.name,
    c.status,
    c.ip_address,
    c.device_name,
    c.channel_name,
    c.protocol,
    c.type,
    c.rtsp_url,
    c.is_active,
    c.status_updated_at
FROM nvr_camera c
JOIN nvr_device d ON d.id = c.device_id
WHERE d.ip = '81.23.151.25'
ORDER BY c.channel_no;
```

### Проверка обновления количества камер:

```sql
SELECT id, name, ip, cameras_count 
FROM nvr_device 
WHERE ip = '81.23.151.25';
```

## 7. Проверка через админку

Откройте в браузере:
```
http://localhost:8082/admin/admin.html
```

1. Войдите с учётными данными
2. Найдите устройство с IP `81.23.151.25`
3. Нажмите кнопку "Каналы"
4. Должен отобразиться список каналов с их статусами

## 8. Отладка проблем

### Если каналы не синхронизируются:

1. **Проверьте доступность устройства:**
   ```bash
   curl -v http://81.23.151.25:8082/cgi-bin/devVideoInput.cgi?action=getCollect
   ```
   Должен вернуть 401 Unauthorized (это нормально, нужна Digest-аутентификация)

2. **Проверьте логи на ошибки:**
   - `Failed to fetch channels` - проблема с подключением или аутентификацией
   - `Empty response` - устройство вернуло пустой ответ
   - `Received HTML instead of INI` - устройство вернуло HTML (возможно, неправильный эндпоинт)

3. **Проверьте учётные данные:**
   - Убедитесь, что пароль правильно зашифрован
   - Проверьте, что роль пользователя `user_admin` или `user_default`

4. **Проверьте http_port:**
   ```sql
   SELECT id, ip, http_port FROM nvr_device WHERE ip = '81.23.151.25';
   ```
   Должно быть `http_port = 8082`

### Если синхронизация работает, но каналов нет:

1. Проверьте, что устройство действительно возвращает каналы:
   - Попробуйте открыть в браузере (с авторизацией):
     `http://81.23.151.25:8082/cgi-bin/devVideoInput.cgi?action=getCollect`

2. Проверьте формат ответа в логах:
   - Должны быть строки вида: `table.VideoInput[0].Name=...`

## 9. Тестирование Digest-аутентификации

Для проверки Digest-аутентификации можно использовать curl:

```bash
# Первый запрос (должен вернуть 401)
curl -v http://81.23.151.25:8082/cgi-bin/devVideoInput.cgi?action=getCollect

# В ответе должен быть заголовок:
# WWW-Authenticate: Digest realm="...", nonce="...", qop="auth", opaque="..."
```

## 10. Проверка расписания синхронизации

Синхронизация запускается автоматически каждые 5 минут. Чтобы проверить:

1. Запустите приложение
2. Подождите 5 минут
3. Проверьте логи на наличие сообщений о синхронизации
4. Проверьте БД на наличие обновлённых каналов

Для более быстрой проверки можно временно изменить интервал в `NvrSyncService`:
```java
@Scheduled(fixedRate = 60000) // 1 минута вместо 5
```

