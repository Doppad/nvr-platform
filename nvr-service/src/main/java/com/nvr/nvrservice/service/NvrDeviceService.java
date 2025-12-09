package com.nvr.nvrservice.service;

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

    private DeviceDto toDto(NvrDevice dev) {

        // Достаём viewer
        NvrDeviceUser viewer = deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_default")
                .orElseThrow(() -> new IllegalStateException(
                        "Viewer user (role=user_default) not configured for device " + dev.getId()
                ));

        String decryptedPass = crypto.decrypt(viewer.getPasswordEnc());

        int camerasCount = dev.getCamerasCount() != null ? dev.getCamerasCount() : 0;

        // Новый блок: адрес
        Long addressId = null;
        String addressLabel = null;

        if (dev.getAddressEntity() != null) {
            addressId = dev.getAddressEntity().getId();
            addressLabel = dev.getAddressEntity().getLabel();
        }

        // Простейшая проверка доступности IP:порта.
        // Это синхронный connect с небольшим таймаутом, подходит как первый MVP.
        String status = computeStatus(dev.getIp(), dev.getPort());

        return new DeviceDto(
                dev.getId(),
                dev.getName(),
                dev.getIp(),
                dev.getPort(),
                dev.getVendor(),
                dev.getTimezone(),
                dev.getCreatedAt(),

                camerasCount,
                viewer.getUsername(),
                decryptedPass,

                addressId,
                addressLabel,
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
                    // Маппинг active из isActive (проверяется через RTSP)
                    boolean isActive = camera.getIsActive() != null ? camera.getIsActive() : false;
                    dto.setActive(isActive);
                    dto.setIsActive(isActive); // alias
                    
                    // Вычисляем visible: канал видим, если он активен ИЛИ имеет нестандартное имя
                    // Правила:
                    // - Если канал ONLINE (active = true) → всегда видим
                    // - Если канал OFFLINE (active = false) И имя = "Channel" + номер → скрываем (пустой канал)
                    // - Если канал OFFLINE (active = false) И имя != "Channel" + номер → показываем (реальная камера, просто оффлайн)
                    String channelName = camera.getName();
                    boolean isDefaultName = channelName != null && channelName.matches("Channel\\d+");
                    boolean visible = isActive || !isDefaultName;
                    dto.setVisible(visible);
                    
                    // Дополнительные поля
                    dto.setId(camera.getId());
                    dto.setStatus(camera.getStatus());
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
        deviceUsers.deleteByDeviceId(device.getId());
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
