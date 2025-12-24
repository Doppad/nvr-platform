package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.AddressDto;
import com.nvr.nvrservice.api.dto.ChannelDto;
import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
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
import java.util.List;

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

        // Достаём viewer
        NvrDeviceUser viewer = deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_default")
                .orElseThrow(() -> new IllegalStateException(
                        "Viewer user (role=user_default) not configured for device " + dev.getId()
                ));

        String decryptedPass = crypto.decrypt(viewer.getPasswordEnc());

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
                viewer.getUsername(),
                decryptedPass,

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
            ctx = new UserContext(userId, null, null, null, 14);
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
        
        // НОВАЯ МОДЕЛЬ: проверяем лимит камер по addressId (если есть)
        Long userAddressId = getUserAddressId(ctx.userId());
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
            // Пытаемся получить addressId пользователя
            addressIdToUse = getUserAddressId(ctx.userId());
            if (addressIdToUse == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "addressId is required. NVR must be attached to an Address. " +
                        "Either provide addressId in request or ensure user has an address."
                );
            }
        }
        
        // Делаем final для использования в лямбда-выражении
        final Long finalAddressId = addressIdToUse;
        
        // Получаем Address и проверяем доступ
        var address = addressRepo.findById(finalAddressId)
                .filter(a -> isSuperAdmin(ctx) || a.getOwnerId().equals(ctx.userId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Address not found or does not belong to user: " + finalAddressId
                ));

        int camerasCount = req.getCamerasCount() != null ? req.getCamerasCount() : 0;
        String timezone = (req.getTimezone() == null || req.getTimezone().isBlank()) ? "UTC" : req.getTimezone();

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

        // Создаём NvrDevice с привязкой к адресу (обязательно)
        var dev = repo.save(NvrDevice.builder()
                .ownerId(ownerIdForDb)  // HOTFIX: гарантированно не null для совместимости с БД
                .name(req.getName())
                .ip(req.getIp())
                .port(req.getPort())
                .httpPort(req.getHttpPort())    // HTTP порт для API запросов
                .address(req.getAddress())      // legacy-строка, можно будет выпилить позже
                .vendor(req.getVendor())
                .timezone(timezone)
                .addressEntity(address)         // ОБЯЗАТЕЛЬНО: поле связи с Address
                .camerasCount(camerasCount)
                .build());

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
            Long deviceId = dev.getId();
            log.debug("Scheduling immediate sync for newly created Dahua device {} (id={})", 
                    dev.getName(), deviceId);
            // Запускаем синхронизацию в отдельном потоке после завершения транзакции
            // Используем простой Thread, так как @Async требует дополнительной настройки
            new Thread(() -> {
                try {
                    // Небольшая задержка, чтобы транзакция точно завершилась
                    Thread.sleep(500);
                    log.debug("Starting sync for device {} (id={})", dev.getName(), deviceId);
                    syncService.syncDeviceChannels(deviceId);
                    log.debug("Sync completed for device {} (id={})", dev.getName(), deviceId);
                } catch (Exception e) {
                    log.error("Failed to sync device {} (id={}): {}", 
                            dev.getName(), deviceId, e.getMessage(), e);
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

        // НОВАЯ МОДЕЛЬ: получаем устройства через addressId пользователя
        Long userAddressId = getUserAddressId(ctx.userId());
        if (userAddressId != null) {
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

        if (isSuperAdmin(ctx)) {
            page = repo.findAll(pageable);
        } else {
            // НОВАЯ МОДЕЛЬ: получаем устройства через addressId пользователя
            Long userAddressId = getUserAddressId(ctx.userId());
            if (userAddressId != null) {
                // Если есть addressId - показываем устройства по этому адресу
                log.debug("User {} has addressId={}, fetching devices by address", ctx.userId(), userAddressId);
                page = repo.findByAddressEntity_Id(userAddressId, pageable);
                log.debug("Found {} devices for user {} by addressId={}", page.getTotalElements(), ctx.userId(), userAddressId);
                
                // Если по адресу устройств нет, используем fallback на ownerId
                if (page.getTotalElements() == 0) {
                    log.debug("No devices found by addressId={} for user {}, falling back to ownerId", userAddressId, ctx.userId());
                    page = repo.findByOwnerId(ctx.userId(), pageable);
                    log.debug("Found {} devices for user {} by ownerId (fallback)", page.getTotalElements(), ctx.userId());
                }
            } else {
                // Fallback: если addressId нет, используем ownerId для всех устройств пользователя
                // Это включает устройства, созданные с ownerId = userId, независимо от того,
                // привязаны ли они к адресам или нет
                log.debug("User {} has no addressId, using ownerId-based lookup (includes devices with/without addresses)", ctx.userId());
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

    @Transactional
    public DeviceDto update(Long ownerIdIgnored, Long id, UpdateDeviceReq req) {
        var ctx = userCtxOrThrow();
        NvrDevice device;

        if (isSuperAdmin(ctx)) {
            device = repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            // НОВАЯ МОДЕЛЬ: получаем устройства через addressId пользователя
            Long userAddressId = getUserAddressId(ctx.userId());
            if (userAddressId != null) {
                device = repo.findByIdAndAddressEntity_Id(id, userAddressId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            } else {
                // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
                device = repo.findByIdAndOwnerId(id, ctx.userId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
            }
        }

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

        if (req.getAddressId() != null) {
            var newAddress = addressRepo.findById(req.getAddressId())
                    .filter(a -> isSuperAdmin(ctx) || a.getOwnerId().equals(ctx.userId()))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Address not found or does not belong to user"
                    ));
            device.setAddressEntity(newAddress);
        }

        repo.save(device);
        return toDto(device);
    }

    /**
     * Получает addressId пользователя из JWT claims, БД или fallback на ownerId.
     * Приоритет: JWT claims > первый адрес из БД по userId > null (fallback на ownerId адреса).
     */
    private Long getUserAddressId(Long userId) {
        if (userId == null) {
            return null;
        }
        
        // 1. Пытаемся получить из JWT claims (если есть)
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getDetails() instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) auth.getDetails();
                    Object addrId = details.get("addressId");
                    if (addrId instanceof Number) {
                        Long addressId = ((Number) addrId).longValue();
                        log.debug("Got addressId={} from JWT claims for userId={}", addressId, userId);
                        return addressId;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not get addressId from JWT claims for userId={}: {}", userId, e.getMessage());
        }
        
        // 2. Если нет в claims, ищем первый адрес пользователя в БД
        try {
            var addresses = addressRepo.findByOwnerId(userId);
            if (!addresses.isEmpty()) {
                Long addressId = addresses.get(0).getId();
                log.debug("Got addressId={} from DB (first address) for userId={}", addressId, userId);
                return addressId;
            }
        } catch (Exception e) {
            log.warn("Could not get addressId from DB for userId={}: {}", userId, e.getMessage());
        }
        
        // 3. Fallback: возвращаем null (будет проверка через ownerId адреса)
        log.debug("No addressId found for userId={}, using fallback (ownerId check)", userId);
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
            // НОВАЯ МОДЕЛЬ: получаем камеры через addressId пользователя
            Long userAddressId = getUserAddressId(ctx.userId());
            if (userAddressId != null) {
                cameras = cameraRepo.findByDeviceAddressId(userAddressId);
            } else {
                // Fallback: если addressId нет, используем старую логику через ownerId (для обратной совместимости)
                log.warn("User {} has no addressId, using deprecated ownerId-based lookup for cameras", ctx.userId());
                cameras = cameraRepo.findByDeviceOwnerId(ctx.userId());
            }
        }

        return cameras.stream()
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
        return cameras.stream()
                .sorted((a, b) -> Integer.compare(a.getChannelNo(), b.getChannelNo()))
                .map(this::cameraToDto)
                .toList();
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
    private String computeUiStatus(Boolean hasCamera, String nvrStatus, String rtspStatus) {
        if (hasCamera == null || !hasCamera) {
            return "HIDDEN";
        }
        
        if ("ONLINE".equals(nvrStatus)) {
            if ("OK".equals(rtspStatus)) {
                return "ONLINE";
            } else {
                return "ONLINE_NO_STREAM";
            }
        } else if ("OFFLINE".equals(nvrStatus)) {
            return "OFFLINE";
        } else if ("UNKNOWN".equals(nvrStatus)) {
            // Защитное поведение: если nvr_status неизвестен, используем rtsp_status
            if ("OK".equals(rtspStatus)) {
                return "ONLINE"; // RTSP работает - считаем камеру онлайн
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
            // НОВАЯ МОДЕЛЬ: получаем устройства через addressId пользователя
            Long userAddressId = getUserAddressId(ctx.userId());
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
