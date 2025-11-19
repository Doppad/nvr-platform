package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NvrDeviceService {

    private final NvrDeviceRepo repo;
    private final NvrDeviceUserRepo deviceUsers;
    private final NvrCameraRepo cameraRepo;
    private final AddressRepo addressRepo;
    private final CryptoService crypto;

    private DeviceDto toDto(NvrDevice dev) {

        // Достаём viewer
        NvrDeviceUser viewer = deviceUsers.findByDeviceIdAndRole(dev.getId(), "user_default")
                .orElseThrow(() -> new IllegalStateException(
                        "Viewer user (role=user_default) not configured for device " + dev.getId()
                ));

        String decryptedPass = crypto.decrypt(viewer.getPasswordEnc());

        int camerasCount = cameraRepo.findByDeviceId(dev.getId()).size();

        // Новый блок: адрес
        Long addressId = null;
        String addressLabel = null;

        if (dev.getAddressEntity() != null) {
            addressId = dev.getAddressEntity().getId();
            addressLabel = dev.getAddressEntity().getLabel();
        }

        return new DeviceDto(
                dev.getId(),
                dev.getName(),
                dev.getIp(),
                dev.getPort(),
                dev.getVendor(),
                dev.getCreatedAt(),

                camerasCount,
                viewer.getUsername(),
                decryptedPass,

                addressId,
                addressLabel
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
            ctx = new UserContext(userId, null, null, 14);
        }

        if (ctx.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user in context");
        return ctx;
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

        // Создаём NvrDevice с привязкой к адресу (если есть)
        var dev = repo.save(NvrDevice.builder()
                .ownerId(ctx.userId())
                .name(req.getName())
                .ip(req.getIp())
                .port(req.getPort())
                .address(req.getAddress())      // legacy-строка, можно будет выпилить позже
                .vendor(req.getVendor())
                .addressEntity(address)         //поле связи с Address
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
        return repo.findByIdAndOwnerId(id, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    @Transactional(readOnly = true)
    public Page<DeviceDto> list(Long ownerIdIgnored, Pageable pageable) {
        var ctx = userCtxOrThrow();
        Page<NvrDevice> page = repo.findByOwnerId(ctx.userId(), pageable);
        return page.map(this::toDto);
    }

    @Transactional
    public NvrDevice update(Long ownerIdIgnored, Long id, UpdateDeviceReq req) {
        var ctx = userCtxOrThrow();
        NvrDevice device = repo.findByIdAndOwnerId(id, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));

        if (req.getName() != null) device.setName(req.getName());
        if (req.getIp() != null) device.setIp(req.getIp());
        if (req.getPort() != null) device.setPort(req.getPort());
        if (req.getAddress() != null) device.setAddress(req.getAddress());
        if (req.getVendor() != null) device.setVendor(req.getVendor());

        return repo.save(device);
    }

    @Transactional
    public void delete(Long ownerIdIgnored, Long id) {
        var ctx = userCtxOrThrow();
        NvrDevice device = repo.findByIdAndOwnerId(id, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        repo.delete(device);
    }

    @Transactional(readOnly = true)
    public Page<DeviceDto> listByAddress(Long addressId, Pageable pageable) {
        var ctx = userCtxOrThrow();

        // 1) Проверяем, что адрес принадлежит этому пользователю
        addressRepo.findById(addressId)
                .filter(a -> a.getOwnerId().equals(ctx.userId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        // 2) Берём все NVR по адресу
        Page<NvrDevice> page = repo.findByOwnerIdAndAddressEntity_Id(ctx.userId(), addressId, pageable);

        // 3) Конвертируем в DTO
        return page.map(this::toDto);
    }

}
