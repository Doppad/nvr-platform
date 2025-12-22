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

        // Достаём viewer: сначала пытаемся найти user_default, если нет - используем user_admin
        NvrDeviceUser viewer = deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_default")
                .or(() -> deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_admin"))
                .orElseThrow(() -> new IllegalStateException(
                        "Viewer user (role=user_default or user_admin) not configured for device " + dev.getId()
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
        long used = repo.countByOwnerId(ctx.userId());
        Integer max = ctx.maxCameras(); // может быть null
        // int max = ctx.maxCameras() != null ? ctx.maxCameras() : 1;

//        if (used >= max) {
//            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Достигнуто максимальное кол-во камер: " + max);
//        }

        if (max != null && used >= max) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Max cameras reached: " + max
            );
        }

        // Разрешаем addressId -> Address (если прислали)
        //    Здесь мы выполняем бизнес-правило:
        //    "нельзя привязать NVR к чужому адресу"
        var address = req.getAddressId() != null
                ? addressRepo.findById(req.getAddressId())
                .filter(a -> a.getOwnerId().equals(ctx.userId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Адрес не найден или не принадлежит пользователю"
                ))
                : null;

        int camerasCount = req.getCamerasCount() != null ? req.getCamerasCount() : 0;
        String timezone = (req.getTimezone() == null || req.getTimezone().isBlank()) ? "UTC" : req.getTimezone();

        // Создаём NvrDevice с привязкой к адресу (если есть)
        var dev = repo.save(NvrDevice.builder()
                .ownerId(ctx.userId())
                .name(req.getName())
                .ip(req.getIp())
                .port(req.getPort())
                .httpPort(req.getHttpPort())    // HTTP порт для API запросов
                .address(req.getAddress())      // legacy-строка, можно будет выпилить позже
                .vendor(req.getVendor())
                .timezone(timezone)
                .addressEntity(address)         //поле связи с Address
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
            page = repo.findByOwnerId(ctx.userId(), pageable);
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
            device = repo.findByIdAndOwnerId(id, ctx.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
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
     * Заменяет user_default на user_admin для указанного устройства.
     * Сохраняет username и password, меняет только role.
     * 
     * @param deviceId ID устройства
     * @return true если замена выполнена успешно, false если user_default не найден
     */
    @Transactional
    public boolean replaceUserDefaultWithAdmin(Long deviceId) {
        // Находим user_default
        var userDefault = deviceUsers.findByDeviceIdAndRole(deviceId, "user_default");
        
        if (userDefault.isEmpty()) {
            log.warn("No user_default found for device {}", deviceId);
            return false;
        }
        
        var user = userDefault.get();
        log.info("Found user_default for device {}: username={}", deviceId, user.getUsername());
        
        // Проверяем, не существует ли уже user_admin
        var existingAdmin = deviceUsers.findByDeviceIdAndRole(deviceId, "user_admin");
        if (existingAdmin.isPresent()) {
            log.warn("user_admin already exists for device {}, deleting user_default only", deviceId);
            deviceUsers.delete(user);
            return true;
        }
        
        // Сохраняем данные
        String username = user.getUsername();
        String passwordEnc = user.getPasswordEnc();
        var createdAt = user.getCreatedAt();
        var device = user.getDevice();
        
        // Удаляем user_default
        deviceUsers.delete(user);
        log.debug("Deleted user_default for device {}", deviceId);
        
        // Создаём user_admin с теми же данными
        var adminUser = NvrDeviceUser.builder()
                .device(device)
                .role("user_admin")
                .username(username)
                .passwordEnc(passwordEnc)
                .createdAt(createdAt)
                .build();
        
        deviceUsers.save(adminUser);
        log.info("Created user_admin for device {}: username={}", deviceId, username);
        
        return true;
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
                .map(camera -> {
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
                    // Если статус UNKNOWN, но has_camera=true, не считаем как ошибку (RTSP проверка еще не выполнена)
                    boolean isActive = "ONLINE".equals(uiStatus) || ("UNKNOWN".equals(uiStatus) && hasCamera);
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
                })
                .toList();
    }

    /**
     * Вычисляет UI статус канала на основе has_camera, nvr_status и rtsp_status.
     * 
     * Правила:
     * - Если has_camera == false → HIDDEN
     * - Если has_camera == true:
     *   - если nvr_status==ONLINE и rtsp_status==OK → ONLINE
     *   - если nvr_status==ONLINE и rtsp_status!=OK → ONLINE_NO_STREAM
     *   - если nvr_status==OFFLINE → OFFLINE
     *   - если nvr_status==UNKNOWN и rtsp_status==OK → ONLINE (защитное поведение: RTSP работает)
     *   - если nvr_status==UNKNOWN и rtsp_status==FAIL → OFFLINE (защитное поведение: RTSP не работает)
     *   - иначе → UNKNOWN
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

        if (isSuperAdmin(ctx)) {
            device = repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        } else {
            device = repo.findByIdAndOwnerId(id, ctx.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
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
