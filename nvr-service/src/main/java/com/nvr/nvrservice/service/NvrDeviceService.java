package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.AddressDto;
import com.nvr.nvrservice.api.dto.ChannelDto;
import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
import com.nvr.nvrservice.api.dto.UpdateChannelReq;
import com.nvr.nvrservice.domain.NvrCamera;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.domain.NvrDeviceUser;
import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrCameraRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.repo.NvrDeviceUserRepo;
import com.nvr.nvrservice.security.CryptoService;
import com.nvr.nvrservice.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NvrDeviceService {

    private final NvrDeviceRepo repo;
    private final NvrDeviceUserRepo deviceUsers;
    private final AddressRepo addressRepo;
    private final NvrCameraRepo cameraRepo;
    private final CryptoService crypto;
    private final NvrSyncService syncService;

    private DeviceDto toDto(NvrDevice dev) {

        // Достаём viewer (может отсутствовать для старых устройств)
        String viewerUsername = null;
        String decryptedPass = null;
        Optional<NvrDeviceUser> viewerOpt = deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_default");
        if (viewerOpt.isPresent()) {
            NvrDeviceUser viewer = viewerOpt.get();
            viewerUsername = viewer.getUsername();
            decryptedPass = crypto.decrypt(viewer.getPasswordEnc());
        } else {
            // Для устройств без viewer-пользователя логируем предупреждение, но не падаем
            log.warn("Viewer user (role=user_default) not configured for device {} (id={}). " +
                    "Device will be shown without viewer credentials.", dev.getName(), dev.getId());
        }

        int camerasCount = dev.getCamerasCount() != null ? dev.getCamerasCount() : 0;

        // Новый блок: адрес - полный объект
        AddressDto address = null;
        if (dev.getAddressEntity() != null) {
            var addr = dev.getAddressEntity();
            address = new AddressDto(
                    String.format("%06d", addr.getId()),
                    addr.getLabel(),
                    addr.getCity(),
                    addr.getStreet(),
                    addr.getHouse(),
                    addr.getApartment(),
                    addr.getComment()
            );
        }

        // Простейшая проверка доступности IP:порта.
        // Это синхронный connect с небольшим таймаутом, подходит как первый MVP.
        String status = computeStatus(dev.getIp(), dev.getPort());

        return new DeviceDto(
                dev.getId(),
                dev.getName(),
                dev.getIp(),
                dev.getPort(),
                dev.getHttpPort(), // HTTP порт для API запросов
                dev.getVendor(),
                dev.getTimezone(),
                dev.getCreatedAt(),

                camerasCount,
                dev.getMaxChannels(), // Максимальное количество каналов
                viewerUsername,      // может быть null, если viewer не настроен
                decryptedPass,       // может быть null, если viewer не настроен

                address,
                status
        );
    }



    private UserContext userCtxOrThrow() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (auth != null && auth.getPrincipal() instanceof Long l) ? l : null;

        // Пытаемся достать UserContext (из request attribute, см. фильтр)
        UserContext ctx = null;
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object uc = attrs.getRequest().getAttribute("userContext");
            if (uc instanceof UserContext u) ctx = u;
        }

        if (ctx == null) {
            // если почему-то нет — соберём минимальный контекст
            ctx = new UserContext(userId, null, null, null, 14, null); // addressId = null для fallback
        }

        if (ctx.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user in context");
        return ctx;
    }

    private boolean isSuperAdmin(UserContext ctx) {
        return ctx.role() != null && "SUPER_ADMIN".equalsIgnoreCase(ctx.role());
    }

    @Transactional
    public DeviceDto create(Long ownerIdIgnored, CreateDeviceReq req) {
        // Берём контекст пользователя (userId, лимит и т.д.)
        var ctx = userCtxOrThrow();

        // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: проверяем лимит камер по addressId (если есть)
        Long userAddressId = getUserAddressId(ctx);
        long used;
        if (userAddressId != null) {
            used = repo.countByAddressEntity_Id(userAddressId);
        } else {
            // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
            log.warn("User {} has no addressId, using deprecated ownerId-based count for limit check", ctx.userId());
            used = repo.countByOwnerId(ctx.userId());
        }
        
        Integer max = ctx.maxCameras(); // может быть null
        if (max != null && used >= max) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Max cameras reached: " + max
            );
        }

        // НОВАЯ МОДЕЛЬ: addressId обязателен при создании NVR
        // Если не передан в запросе, пытаемся получить из пользователя
        Long addressIdToUse = req.getAddressId();
        if (addressIdToUse == null) {
            // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: пытаемся получить addressId пользователя из UserContext
            addressIdToUse = getUserAddressId(ctx);
            if (addressIdToUse == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "addressId is required. NVR must be attached to an Address. " +
                        "Either provide addressId in request or ensure user has an addressId assigned."
                );
            }
        }
        
        // Делаем final для использования в лямбда-выражении
        final Long finalAddressId = addressIdToUse;
        
        // Получаем Address (глобальный, не привязан к ownerId)
        // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: убрана проверка ownerId, Address теперь глобальные
        // Для обычных пользователей: проверяем, что addressId совпадает с addressId пользователя
        var address = addressRepo.findById(finalAddressId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Address not found: " + finalAddressId
                ));
        
        // Проверка доступа: обычный пользователь может использовать только свой addressId
        // Используем уже полученный userAddressId из строки 130
        if (!isSuperAdmin(ctx)) {
            boolean ownsAddress = addressRepo.existsByIdAndOwnerId(finalAddressId, ctx.userId());
            if (!ownsAddress) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Address does not belong to user: " + finalAddressId
                );
            }
        }

        int camerasCount = req.getCamerasCount() != null ? req.getCamerasCount() : 0;
        String timezone = (req.getTimezone() == null || req.getTimezone().isBlank()) ? "UTC" : req.getTimezone();

        // Нормализуем IP и порт для поиска/создания
        String normalizedIp = normalizeIp(req.getIp());
        Integer normalizedPort = normalizePort(req.getPort());

        // FIND-OR-CREATE: ищем существующее устройство по ключу (address_id, ip, port)
        Optional<NvrDevice> existingDevice = repo.findByAddressEntity_IdAndIpAndPort(
                finalAddressId, normalizedIp, normalizedPort);

        NvrDevice dev;
        if (existingDevice.isPresent()) {
            // Обновляем существующее устройство
            dev = existingDevice.get();
            log.info("Found existing device {} (id={}) for address {} with ip={}, port={}. Updating...",
                    dev.getName(), dev.getId(), finalAddressId, normalizedIp, normalizedPort);
            
            // Обновляем поля (кроме ключевых: address_id, ip, port)
            dev.setName(req.getName());
            if (req.getHttpPort() != null) {
                dev.setHttpPort(req.getHttpPort());
            }
            if (req.getVendor() != null && !req.getVendor().isBlank()) {
                dev.setVendor(req.getVendor());
            }
            dev.setTimezone(timezone);
            if (camerasCount > 0) {
                dev.setCamerasCount(camerasCount);
            }
            if (req.getMaxChannels() != null) {
                dev.setMaxChannels(req.getMaxChannels());
            }
            
            dev = repo.save(dev);
        } else {
            // Создаём новое устройство
            // HOTFIX: Гарантируем, что ownerId не будет null (для обратной совместимости с БД)
            // Если миграция V8/V9 ещё не применена, owner_id может быть NOT NULL
            // Приоритет: address.getOwnerId() > ctx.userId() > исключение
            Long ownerIdForDb = address.getOwnerId() != null ? address.getOwnerId() : ctx.userId();
            if (ownerIdForDb == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Cannot determine ownerId: address.ownerId and userContext.userId are both null"
                );
            }

            dev = repo.save(NvrDevice.builder()
                    .ownerId(ownerIdForDb)  // HOTFIX: гарантированно не null для совместимости с БД
                    .name(req.getName())
                    .ip(normalizedIp)       // Используем нормализованный IP
                    .port(normalizedPort)   // Используем нормализованный порт
                    .httpPort(req.getHttpPort())    // HTTP порт для API запросов
                    .address(req.getAddress())      // legacy-строка, можно будет выпилить позже
                    .vendor(req.getVendor())
                    .timezone(timezone)
                    .addressEntity(address)         // ОБЯЗАТЕЛЬНО: поле связи с Address
                    .camerasCount(camerasCount)
                    .maxChannels(req.getMaxChannels())
                    .build());
            
            log.info("Created new device {} (id={}) for address {} with ip={}, port={}",
                    dev.getName(), dev.getId(), finalAddressId, normalizedIp, normalizedPort);
        }

        // Сохраняем учётки, если прислали
        if (req.getUsers() != null && !req.getUsers().isEmpty()) {
            for (var u : req.getUsers()) {
                var enc = crypto.encrypt(u.getPassword());
                deviceUsers.save(NvrDeviceUser.builder()
                        .device(dev)
                        .role(u.getRole())
                        .username(u.getUsername())
                        .passwordEnc(enc)
                        .build());
            }
        } else {
            // Предупреждение: устройство создано без пользователей, синхронизация не будет работать
            log.warn("Device {} (id={}, ip={}) created without users. " +
                    "Synchronization will be skipped until users are added. " +
                    "Add at least one user with role 'user_admin' or 'user_default'.",
                    dev.getName(), dev.getId(), dev.getIp());
        }

        // Запускаем синхронизацию каналов асинхронно для Dahua устройств
        // Используем @TransactionalEventListener или просто запускаем после коммита транзакции
        if ("Dahua".equalsIgnoreCase(dev.getVendor()) && 
            (req.getUsers() != null && !req.getUsers().isEmpty())) {
            // Создаем final переменные для использования в лямбде
            final Long deviceId = dev.getId();
            final String deviceName = dev.getName();
            log.debug("Scheduling immediate sync for newly created Dahua device {} (id={})", 
                    deviceName, deviceId);
            // Запускаем синхронизацию в отдельном потоке после завершения транзакции
            // Используем простой Thread, так как @Async требует дополнительной настройки
            new Thread(() -> {
                try {
                    // Небольшая задержка, чтобы транзакция точно завершилась
                    Thread.sleep(500);
                    log.debug("Starting sync for device {} (id={})", deviceName, deviceId);
                    syncService.syncDeviceChannels(deviceId);
                    log.debug("Sync completed for device {} (id={})", deviceName, deviceId);
                } catch (Exception e) {
                    log.error("Failed to sync device {} (id={}): {}", 
                            deviceName, deviceId, e.getMessage(), e);
                }
            }, "sync-device-" + deviceId).start();
        }

        // Собираем DeviceDto, как раньше
        return toDto(dev);
    }

    @Transactional(readOnly = true)
    public NvrDevice get(Long ownerIdIgnored, Long id) {
        var ctx = userCtxOrThrow();

        if (isSuperAdmin(ctx)) {
            return repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        }

        // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: получаем устройства через addressId пользователя из UserContext
        Long userAddressId = getUserAddressId(ctx);
        if (userAddressId != null) {
            // Пользователь видит все устройства, привязанные к его addressId (глобальный Address)
            return repo.findByIdAndAddressEntity_Id(id, userAddressId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        }
        
        // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
        return repo.findByIdAndOwnerId(id, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    @Transactional(readOnly = true)
    public Page<DeviceDto> list(Long ownerIdIgnored, Pageable pageable) {
        var ctx = userCtxOrThrow();
        Page<NvrDevice> page;

        // Проверяем, является ли запрос админским (из /admin/api)
        // В админке супер-админ должен видеть все устройства, независимо от роли в UserContext
        boolean isAdminRequest = false;
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            // 1. Проверяем явный флаг из request attribute (устанавливается в AdminController)
            Object adminFlag = attrs.getRequest().getAttribute("isAdminRequest");
            if (adminFlag instanceof Boolean && (Boolean) adminFlag) {
                isAdminRequest = true;
            } else {
                // 2. Fallback: проверяем по пути ИЛИ по заголовку X-Admin-User
                String requestPath = attrs.getRequest().getRequestURI();
                String adminUserHeader = attrs.getRequest().getHeader("X-Admin-User");
                if ((requestPath != null && requestPath.startsWith("/admin/api")) || 
                    adminUserHeader != null) {
                    isAdminRequest = true;
                }
            }
        }

        if (isSuperAdmin(ctx) || isAdminRequest) {
            // SUPER_ADMIN или запрос из админки - показываем устройства, привязанные к адресам пользователя
            log.info("Admin request detected: isSuperAdmin={}, isAdminRequest={}, userId={}, showing devices for user's addresses", 
                    isSuperAdmin(ctx), isAdminRequest, ctx.userId());
            // Получаем устройства, привязанные к адресам этого пользователя (addressEntity.ownerId = userId)
            page = repo.findByAddressEntity_OwnerId(ctx.userId(), pageable);
            log.info("Found {} devices for admin user {} (devices attached to user's addresses, page={}, size={})", 
                    page.getTotalElements(), ctx.userId(), pageable.getPageNumber(), pageable.getPageSize());
        } else {
            // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: получаем устройства через addressId пользователя из UserContext
            Long userAddressId = getUserAddressId(ctx);
            if (userAddressId != null) {
                // Пользователь видит все устройства, привязанные к его addressId (глобальный Address)
                log.debug("User {} has addressId={}, fetching devices by address (global Address model)", ctx.userId(), userAddressId);
                page = repo.findByAddressEntity_Id(userAddressId, pageable);
                log.debug("Found {} devices for user {} by addressId={}", page.getTotalElements(), ctx.userId(), userAddressId);
                
                // Если по адресу устройств нет, используем fallback на ownerId (для обратной совместимости)
                if (page.getTotalElements() == 0) {
                    log.debug("No devices found by addressId={} for user {}, falling back to ownerId", userAddressId, ctx.userId());
                    page = repo.findByOwnerId(ctx.userId(), pageable);
                    log.debug("Found {} devices for user {} by ownerId (fallback)", page.getTotalElements(), ctx.userId());
                }
            } else {
                // Fallback: если addressId нет, используем ownerId для всех устройств пользователя
                // Это включает устройства, созданные с ownerId = userId, независимо от того,
                // привязаны ли они к адресам или нет (deprecated, но оставлено для совместимости)
                log.debug("User {} has no addressId, using deprecated ownerId-based lookup (includes devices with/without addresses)", ctx.userId());
                page = repo.findByOwnerId(ctx.userId(), pageable);
                log.debug("Found {} devices for user {} by ownerId", page.getTotalElements(), ctx.userId());
            }
        }

        return page.map(this::toDto);
    }

    /**
     * Простейшая синхронная проверка: пробуем открыть TCP-соединение к ip:port.
     * ONLINE  — если connect прошёл в пределах таймаута,
     * OFFLINE — если получили исключение/таймаут,
     * UNKNOWN — если ip/port не заданы.
     */
    private String computeStatus(String ip, Integer port) {
        if (ip == null || ip.isBlank() || port == null) {
            return "UNKNOWN";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 1500); // 1.5 секунды таймаут
            return "ONLINE";
        } catch (Exception e) {
            return "OFFLINE";
        }
    }

    /**
     * Нормализует IP адрес для использования в качестве ключа поиска.
     * Убирает пробелы, приводит к нижнему регистру, убирает протокол если есть.
     * 
     * @param ip исходный IP адрес
     * @return нормализованный IP адрес
     */
    private String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String normalized = ip.trim().toLowerCase();
        // Убираем протокол если есть (http://, https://)
        if (normalized.startsWith("http://")) {
            normalized = normalized.substring(7);
        } else if (normalized.startsWith("https://")) {
            normalized = normalized.substring(8);
        }
        // Убираем порт если указан в IP (например, 192.168.1.1:8080)
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0 && !normalized.contains("/")) {
            // Проверяем, что это не IPv6
            if (normalized.indexOf(':') == normalized.lastIndexOf(':')) {
                normalized = normalized.substring(0, colonIndex);
            }
        }
        return normalized.trim();
    }

    /**
     * Нормализует порт для использования в качестве ключа поиска.
     * Проверяет валидность диапазона (1-65535).
     * 
     * @param port исходный порт
     * @return нормализованный порт или null если невалидный
     */
    private Integer normalizePort(Integer port) {
        if (port == null) {
            return null;
        }
        // Проверяем валидный диапазон портов
        if (port < 1 || port > 65535) {
            log.warn("Invalid port value: {}. Must be between 1 and 65535", port);
            return null;
        }
        return port;
    }

    @Transactional
    public DeviceDto update(Long ownerIdIgnored, Long id, UpdateDeviceReq req) {
        var ctx = userCtxOrThrow();
        NvrDevice device;

        // Проверяем, является ли запрос админским (из /admin/api)
        boolean isAdminRequest = false;
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String requestPath = attrs.getRequest().getRequestURI();
            if (requestPath != null && requestPath.startsWith("/admin/api")) {
                isAdminRequest = true;
            }
        }

        if (isSuperAdmin(ctx) || isAdminRequest) {
            // SUPER_ADMIN или запрос из админки - разрешаем обновление любого устройства
            device = repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: получаем устройства через addressId пользователя из UserContext
            Long userAddressId = getUserAddressId(ctx);
            if (userAddressId != null) {
                device = repo.findByIdAndAddressEntity_Id(id, userAddressId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            } else {
                // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
                device = repo.findByIdAndOwnerId(id, ctx.userId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            }
        }

        // Сохраняем старые значения для проверки изменений
        String oldIp = device.getIp();
        Integer oldPort = device.getPort();
        Integer oldHttpPort = device.getHttpPort();
        String oldVendor = device.getVendor();
        
        if (req.getName() != null) device.setName(req.getName());
        if (req.getIp() != null) device.setIp(req.getIp());
        if (req.getPort() != null) device.setPort(req.getPort());
        if (req.getHttpPort() != null) device.setHttpPort(req.getHttpPort());
        if (req.getAddress() != null) device.setAddress(req.getAddress());
        if (req.getVendor() != null) device.setVendor(req.getVendor());
        if (req.getCamerasCount() != null) device.setCamerasCount(req.getCamerasCount());
        if (req.getTimezone() != null) {
            String tz = req.getTimezone().isBlank() ? "UTC" : req.getTimezone();
            device.setTimezone(tz);
        }

        // Обновляем адрес, если он передан в запросе
        // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: Address теперь глобальные, не привязаны к ownerId
        // Проверка ownerId убрана (кроме админки для безопасности)
        if (req.getAddressId() != null) {
            var newAddress = addressRepo.findById(req.getAddressId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Address not found"
                    ));
            // Для обычных пользователей: проверяем, что addressId совпадает с addressId пользователя
            // Для SUPER_ADMIN и админки: разрешаем любой addressId
            if (!isSuperAdmin(ctx) && !isAdminRequest) {
                Long userAddressId = getUserAddressId(ctx);
                if (userAddressId == null || !userAddressId.equals(req.getAddressId())) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN,
                            "Address does not belong to user"
                    );
                }
            }
            device.setAddressEntity(newAddress);
        }
        // Если addressId не передан - адрес остается прежним (device.getAddressEntity() не изменяется)

        repo.save(device);
        
        // Проверяем, изменились ли критичные поля, требующие пересинхронизации
        // Критичные поля: IP, порты, vendor - влияют на подключение к устройству
        boolean needsResync = false;
        if (req.getIp() != null && !req.getIp().equals(oldIp)) {
            needsResync = true;
            log.info("Device {} IP changed from {} to {}, triggering resync", device.getId(), oldIp, req.getIp());
        }
        if (req.getPort() != null && !req.getPort().equals(oldPort)) {
            needsResync = true;
            log.info("Device {} port changed from {} to {}, triggering resync", device.getId(), oldPort, req.getPort());
        }
        if (req.getHttpPort() != null && !req.getHttpPort().equals(oldHttpPort)) {
            needsResync = true;
            log.info("Device {} HTTP port changed from {} to {}, triggering resync", device.getId(), oldHttpPort, req.getHttpPort());
        }
        if (req.getVendor() != null && !req.getVendor().equals(oldVendor)) {
            needsResync = true;
            log.info("Device {} vendor changed from {} to {}, triggering resync", device.getId(), oldVendor, req.getVendor());
        }
        
        // Если изменились критичные поля - очищаем старые данные перед синхронизацией
        if (needsResync && "Dahua".equalsIgnoreCase(device.getVendor())) {
            Long deviceId = device.getId();
            
            // Удаляем все старые каналы устройства (они больше не актуальны)
            List<NvrCamera> oldCameras = cameraRepo.findByDeviceId(deviceId);
            if (!oldCameras.isEmpty()) {
                log.info("Deleting {} old cameras for device {} (id={}) before resync after critical field changes", 
                        oldCameras.size(), device.getName(), deviceId);
                cameraRepo.deleteAll(oldCameras);
            }
            
            // Обнуляем количество камер - будет обновлено после синхронизации
            device.setCamerasCount(0);
            repo.save(device);
            log.info("Reset camerasCount to 0 for device {} (id={}) before resync", device.getName(), deviceId);
            
            // Проверяем, есть ли пользователи для синхронизации
            boolean hasUsers = !deviceUsers.findByDeviceId(deviceId).isEmpty();
            if (hasUsers) {
                log.info("Scheduling resync for updated Dahua device {} (id={}) after critical field changes", 
                        device.getName(), deviceId);
                // Запускаем синхронизацию в отдельном потоке после завершения транзакции
                new Thread(() -> {
                    try {
                        // Небольшая задержка, чтобы транзакция точно завершилась
                        Thread.sleep(500);
                        log.info("Starting resync for device {} (id={}) after update", device.getName(), deviceId);
                        syncService.syncDeviceChannels(deviceId);
                        log.info("Resync completed for device {} (id={}) after update", device.getName(), deviceId);
                    } catch (Exception e) {
                        log.error("Failed to resync device {} (id={}) after update: {}", 
                                device.getName(), deviceId, e.getMessage(), e);
                    }
                }, "sync-device-" + deviceId).start();
            } else {
                log.warn("Device {} (id={}, ip={}) updated but has no users. " +
                        "Synchronization will be skipped. " +
                        "Add at least one user with role 'user_admin' or 'user_default'.",
                        device.getName(), device.getId(), device.getIp());
            }
        }
        
        return toDto(device);
    }

    /**
     * Получает addressId пользователя из UserContext (JWT claims) или fallback на старую логику.
     * 
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - Пользователь имеет один активный addressId (хранится в User.addressId и передается в JWT)
     * - Если addressId есть в UserContext - используем его
     * - Если нет - fallback на старую логику через ownerId (для обратной совместимости)
     * 
     * @param ctx UserContext пользователя (содержит addressId из JWT)
     * @return addressId пользователя или null (fallback на ownerId)
     */
    private Long getUserAddressId(UserContext ctx) {
        if (ctx == null) {
            return null;
        }
        
        // 1. Приоритет: addressId из UserContext (JWT claims) - переход к глобальным Address
        if (ctx.addressId() != null) {
            log.debug("Using addressId={} from UserContext (JWT) for userId={}", ctx.addressId(), ctx.userId());
            return ctx.addressId();
        }
        
        // 2. Fallback: старая логика через ownerId (для обратной совместимости со старыми данными)
        // Ищем первый адрес пользователя в БД по ownerId (deprecated, но оставлено для совместимости)
        try {
            var addresses = addressRepo.findByOwnerId(ctx.userId());
            if (!addresses.isEmpty()) {
                Long addressId = addresses.get(0).getId();
                log.debug("Fallback: Got addressId={} from DB (first address by ownerId) for userId={}", addressId, ctx.userId());
                return addressId;
            }
        } catch (Exception e) {
            log.debug("Fallback: Could not get addressId from DB for userId={}: {}", ctx.userId(), e.getMessage());
        }
        
        // 3. Fallback: возвращаем null (будет проверка через ownerId устройства)
        log.debug("No addressId found for userId={}, using fallback (device ownerId check)", ctx.userId());
        return null;
    }

    /**
     * Получает все камеры текущего пользователя.
     * Использует новую модель через addressId или fallback на ownerId.
     *
     * @return список всех камер пользователя
     */
    @Transactional(readOnly = true)
    public List<ChannelDto> getAllCameras() {
        var ctx = userCtxOrThrow();
        List<NvrCamera> cameras;

        if (isSuperAdmin(ctx)) {
            // SUPER_ADMIN видит все камеры
            cameras = cameraRepo.findAll();
        } else {
            // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: получаем камеры через addressId пользователя из UserContext
            Long userAddressId = getUserAddressId(ctx);
            if (userAddressId != null) {
                // Пользователь видит все камеры устройств, привязанных к его addressId (глобальный Address)
                cameras = cameraRepo.findByDeviceAddressId(userAddressId);
            } else {
                // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
                log.debug("User {} has no addressId, using deprecated ownerId-based lookup for cameras", ctx.userId());
                cameras = cameraRepo.findByDeviceOwnerId(ctx.userId());
            }
        }

        // Убираем дубликаты по ID камеры (на случай, если в БД есть дубликаты или запрос вернул дубликаты)
        // Используем LinkedHashMap для сохранения порядка первой встречи
        Map<Long, NvrCamera> uniqueCameras = new LinkedHashMap<>();
        for (NvrCamera camera : cameras) {
            uniqueCameras.putIfAbsent(camera.getId(), camera);
        }
        
        return uniqueCameras.values().stream()
                .sorted((a, b) -> {
                    // Сортируем сначала по deviceId, потом по channelNo
                    int deviceCompare = Long.compare(
                            a.getDevice().getId(),
                            b.getDevice().getId()
                    );
                    if (deviceCompare != 0) return deviceCompare;
                    return Integer.compare(a.getChannelNo(), b.getChannelNo());
                })
                .map(this::cameraToDto)
                .toList();
    }

    /**
     * Преобразует NvrCamera в ChannelDto.
     */
    private ChannelDto cameraToDto(NvrCamera camera) {
        ChannelDto dto = new ChannelDto();
        // Основные поля
        dto.setChannelNumber(camera.getChannelNo());
        dto.setChannelNo(camera.getChannelNo()); // alias
        dto.setName(camera.getName());
        dto.setRtspUrl(camera.getRtspUrl());
        
        // Новые поля статусов
        Boolean hasCamera = camera.getHasCamera() != null ? camera.getHasCamera() : false;
        dto.setHasCamera(hasCamera);
        
        String nvrStatus = camera.getNvrStatus() != null ? camera.getNvrStatus() : "UNKNOWN";
        dto.setNvrStatus(nvrStatus);
        
        String rtspStatus = camera.getRtspStatus() != null ? camera.getRtspStatus() : "NONE";
        dto.setRtspStatus(rtspStatus);
        
        // Вычисляем uiStatus по новым правилам
        String uiStatus = computeUiStatus(hasCamera, nvrStatus, rtspStatus);
        dto.setUiStatus(uiStatus);
        
        // visible = has_camera (пустые каналы всегда скрыты)
        dto.setVisible(hasCamera);
        
        // Legacy поля для обратной совместимости
        // isActive = true если камера ONLINE (включая ONLINE_NO_STREAM - камера работает, просто нет потока)
        // Пустые каналы (HIDDEN) не активны
        boolean isActive = "ONLINE".equals(uiStatus) || "ONLINE_NO_STREAM".equals(uiStatus) || 
                          ("UNKNOWN".equals(uiStatus) && hasCamera);
        dto.setActive(isActive);
        dto.setIsActive(isActive);
        dto.setStatus(camera.getStatus()); // legacy поле
        
        // Дополнительные поля
        dto.setId(camera.getId());
        dto.setIpAddress(camera.getIpAddress());
        dto.setPort(camera.getPort());
        dto.setDeviceName(camera.getDeviceName());
        dto.setChannelName(camera.getChannelName());
        dto.setProtocol(camera.getProtocol());
        dto.setType(camera.getType());
        return dto;
    }

    /**
     * Получает список каналов для устройства.
     *
     * @param ownerIdIgnored игнорируется (используется из контекста)
     * @param deviceId ID устройства
     * @return список каналов
     */
    @Transactional(readOnly = true)
    public List<ChannelDto> getChannels(Long ownerIdIgnored, Long deviceId) {
        var ctx = userCtxOrThrow();

        NvrDevice device;
        if (isSuperAdmin(ctx)) {
            device = repo.findById(deviceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            device = repo.findByIdAndOwnerId(deviceId, ctx.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        }

        List<NvrCamera> cameras = cameraRepo.findByDeviceId(deviceId);
        
        // Дедупликация по ключу (deviceId, channelNo) на случай дубликатов в БД или размножающих JOIN
        // Используем LinkedHashMap для сохранения порядка первой встречи
        Map<String, NvrCamera> uniqueCameras = new LinkedHashMap<>();
        for (NvrCamera camera : cameras) {
            // Ключ: deviceId + channelNo (как в уникальном constraint)
            String key = camera.getDevice().getId() + "_" + camera.getChannelNo();
            uniqueCameras.putIfAbsent(key, camera);
        }
        
        return uniqueCameras.values().stream()
                .sorted((a, b) -> Integer.compare(a.getChannelNo(), b.getChannelNo()))
                .map(this::cameraToDto)
                .toList();
    }

    /**
     * Обновляет канал NVR.
     * 
     * @param ownerIdIgnored игнорируется (используется ctx.userId())
     * @param deviceId ID устройства
     * @param channelId ID канала
     * @param req данные для обновления
     * @return обновленный канал
     */
    @Transactional
    public ChannelDto updateChannel(Long ownerIdIgnored, Long deviceId, Long channelId, UpdateChannelReq req) {
        var ctx = userCtxOrThrow();

        // Проверяем доступ к устройству
        NvrDevice device;
        if (isSuperAdmin(ctx)) {
            device = repo.findById(deviceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            device = repo.findByIdAndOwnerId(deviceId, ctx.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        }

        // Находим канал и проверяем, что он принадлежит этому устройству
        NvrCamera camera = cameraRepo.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        if (!camera.getDevice().getId().equals(deviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel does not belong to this device");
        }

        // Обновляем поля
        if (req.getName() != null) {
            camera.setName(req.getName());
        }
        if (req.getRtspUrl() != null) {
            camera.setRtspUrl(req.getRtspUrl());
        }
        if (req.getEnabled() != null) {
            camera.setEnabled(req.getEnabled());
            camera.setIsActive(req.getEnabled());
        }

        // Сохраняем изменения
        camera = cameraRepo.save(camera);

        log.info("Channel {} updated for device {} by user {}", channelId, deviceId, ctx.userId());

        return cameraToDto(camera);
    }

    /**
     * Вычисляет UI статус канала на основе has_camera, nvr_status и rtsp_status.
     * 
     * Правила:
     * - Если has_camera == false → HIDDEN (пустые каналы не считаются ошибкой)
     * - Если has_camera == true:
     *   - если nvr_status==ONLINE и rtsp_status==OK → ONLINE (зелёный)
     *   - если nvr_status==ONLINE и rtsp_status!=OK → ONLINE_NO_STREAM (жёлтый, WARNING, НЕ ERROR)
     *   - если nvr_status==OFFLINE → OFFLINE (красный, ERROR)
     *   - если nvr_status==UNKNOWN и rtsp_status==OK → ONLINE (защитное поведение: RTSP работает)
     *   - если nvr_status==UNKNOWN и rtsp_status==FAIL → OFFLINE (защитное поведение: RTSP не работает)
     *   - иначе → UNKNOWN
     * 
     * ВАЖНО: ONLINE_NO_STREAM не считается ошибкой - это предупреждение (WARNING).
     * Ошибками считаются только OFFLINE камеры.
     */
    /**
     * Вычисляет UI статус канала на основе has_camera, nvr_status и rtsp_status.
     * 
     * Правила:
     * - Если has_camera == false → HIDDEN (пустые каналы не считаются ошибкой)
     * - Если has_camera == true:
     *   - если nvr_status==ONLINE и rtsp_status==OK → ONLINE (зелёный)
     *   - если nvr_status==ONLINE и rtsp_status==TIMEOUT → ONLINE (зелёный, "поток есть, на проверке")
     *   - если nvr_status==ONLINE и rtsp_status==FAIL → ONLINE_NO_STREAM (жёлтый, WARNING, НЕ ERROR)
     *   - если nvr_status==ONLINE и rtsp_status==NONE/null → ONLINE_NO_STREAM (жёлтый, WARNING)
     *   - если nvr_status==OFFLINE → OFFLINE (красный, ERROR)
     *   - если nvr_status==UNKNOWN и rtsp_status==OK → ONLINE (защитное поведение: RTSP работает)
     *   - если nvr_status==UNKNOWN и rtsp_status==TIMEOUT → ONLINE (защитное поведение: считаем онлайн, проверка не завершена)
     *   - если nvr_status==UNKNOWN и rtsp_status==FAIL → OFFLINE (защитное поведение: RTSP не работает)
     *   - иначе → UNKNOWN
     * 
     * ВАЖНО: 
     * - ONLINE_NO_STREAM не считается ошибкой - это предупреждение (WARNING).
     * - TIMEOUT не считается ошибкой - это означает "не успели проверить", показываем как ONLINE (зелёный).
     * - Ошибками считаются только OFFLINE камеры.
     */
    private String computeUiStatus(Boolean hasCamera, String nvrStatus, String rtspStatus) {
        if (hasCamera == null || !hasCamera) {
            return "HIDDEN";
        }
        
        if ("ONLINE".equals(nvrStatus)) {
            if ("OK".equals(rtspStatus)) {
                return "ONLINE";
            } else if ("TIMEOUT".equals(rtspStatus)) {
                // TIMEOUT = "не успели проверить", но камера ONLINE - показываем как ONLINE (зелёный)
                return "ONLINE";
            } else {
                // FAIL, NONE, null - нет потока
                return "ONLINE_NO_STREAM";
            }
        } else if ("OFFLINE".equals(nvrStatus)) {
            return "OFFLINE";
        } else if ("UNKNOWN".equals(nvrStatus)) {
            // Защитное поведение: если nvr_status неизвестен, используем rtsp_status
            if ("OK".equals(rtspStatus)) {
                return "ONLINE"; // RTSP работает - считаем камеру онлайн
            } else if ("TIMEOUT".equals(rtspStatus)) {
                return "ONLINE"; // TIMEOUT - считаем онлайн, проверка не завершена
            } else if ("FAIL".equals(rtspStatus)) {
                return "OFFLINE"; // RTSP не работает - считаем камеру оффлайн
            } else {
                return "UNKNOWN"; // RTSP статус тоже неизвестен
            }
        } else {
            return "UNKNOWN";
        }
    }

    @Transactional
    public void delete(Long ownerIdIgnored, Long id) {
        var ctx = userCtxOrThrow();
        NvrDevice device;

        // Проверяем, идёт ли запрос из админки - для админа разрешаем удаление любого устройства
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        boolean isAdminRequest = false;
        if (attrs != null) {
            String requestPath = attrs.getRequest().getRequestURI();
            if (requestPath != null && requestPath.startsWith("/admin/api")) {
                isAdminRequest = true;
            }
        }

        if (isSuperAdmin(ctx) || isAdminRequest) {
            // SUPER_ADMIN или запрос из админки - разрешаем удаление любого устройства
            device = repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: получаем устройства через addressId пользователя из UserContext
            Long userAddressId = getUserAddressId(ctx);
            if (userAddressId != null) {
                device = repo.findByIdAndAddressEntity_Id(id, userAddressId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            } else {
                // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
                device = repo.findByIdAndOwnerId(id, ctx.userId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            }
        }
        
        Long deviceId = device.getId();
        
        // Удаляем камеры устройства
        List<NvrCamera> cameras = cameraRepo.findByDeviceId(deviceId);
        if (!cameras.isEmpty()) {
            cameraRepo.deleteAll(cameras);
        }
        
        // Удаляем пользователей устройства
        deviceUsers.deleteByDeviceId(deviceId);
        
        // Удаляем само устройство
        repo.delete(device);
    }

    @Transactional(readOnly = true)
    public Page<DeviceDto> listByAddress(Long addressId, Pageable pageable) {
        var ctx = userCtxOrThrow();

        Page<NvrDevice> page;

        if (isSuperAdmin(ctx)) {
            // супер-админ видит все устройства по адресу, независимо от владельца
            page = repo.findByAddressEntity_Id(addressId, pageable);
        } else {
            // 1) Проверяем, что адрес принадлежит этому пользователю
            addressRepo.findById(addressId)
                    .filter(a -> a.getOwnerId().equals(ctx.userId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

            // 2) Берём все NVR по адресу
            page = repo.findByOwnerIdAndAddressEntity_Id(ctx.userId(), addressId, pageable);
        }

        // 3) Конвертируем в DTO
        return page.map(this::toDto);
    }

}
