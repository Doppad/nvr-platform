// com.nvr.nvrservice.service.NvrDeviceService
package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.domain.NvrDeviceUser;
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
    private final CryptoService crypto;

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
            ctx = new UserContext(userId, null, 1, 14);
        }

        if (ctx.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user in context");
        return ctx;
    }

    @Transactional
    public DeviceDto create(Long ownerIdIgnored, CreateDeviceReq req) {
        var ctx = userCtxOrThrow();
        long used = repo.countByOwnerId(ctx.userId());
        int max = ctx.maxCameras() != null ? ctx.maxCameras() : 1;

        if (used >= max) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Max cameras reached: " + max);
        }

        var dev = repo.save(NvrDevice.builder()
                .ownerId(ctx.userId())
                .name(req.getName())
                .ip(req.getIp())
                .port(req.getPort())
                .address(req.getAddress())
                .vendor(req.getVendor())
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

        return new DeviceDto(
                dev.getId(), dev.getName(), dev.getIp(), dev.getPort(),
                dev.getAddress(), dev.getVendor(), dev.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public NvrDevice get(Long ownerIdIgnored, Long id) {
        var ctx = userCtxOrThrow();
        return repo.findByIdAndOwnerId(id, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    @Transactional(readOnly = true)
    public Page<NvrDevice> list(Long ownerIdIgnored, Pageable pageable) {
        var ctx = userCtxOrThrow();
        return repo.findByOwnerId(ctx.userId(), pageable);
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
}
