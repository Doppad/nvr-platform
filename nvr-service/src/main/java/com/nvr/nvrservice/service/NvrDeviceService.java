package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.domain.NvrDeviceUser;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.repo.NvrDeviceUserRepo;
import com.nvr.nvrservice.security.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NvrDeviceService {

    private final NvrDeviceRepo devices;
    private final NvrDeviceUserRepo deviceUsers;
    private final CryptoService crypto;

    // ⚠️ пока без JWT: читаем ownerId из заголовка X-Debug-UserId (или ставим 1).
    // На следующем шаге заменим на @AuthenticationPrincipal с твоим NvrPrincipal.
    @Transactional
    public DeviceDto create(Long ownerId, CreateDeviceReq req) {
        // Лимит по подписке: пока жёстко 5 устройств (заменим на клейм из JWT)
        long cnt = devices.countByOwnerId(ownerId);
        if (cnt >= 5) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device limit reached");

        var dev = devices.save(NvrDevice.builder()
                .ownerId(ownerId)
                .name(req.getName())
                .ip(req.getIp())
                .port(req.getPort())
                .address(req.getAddress())
                .vendor(req.getVendor())
                .build());

        // Роли/аккаунты → шифруем пароль и сохраняем
        for (var u : req.getUsers()) {
            var enc = crypto.encrypt(u.getPassword());
            deviceUsers.save(NvrDeviceUser.builder()
                    .device(dev)
                    .role(u.getRole())
                    .username(u.getUsername())
                    .passwordEnc(enc)
                    .build());
        }

        return new DeviceDto(
                dev.getId(), dev.getName(), dev.getIp(), dev.getPort(),
                dev.getAddress(), dev.getVendor(), dev.getCreatedAt()
        );
    }
}
