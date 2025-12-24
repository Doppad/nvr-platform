package com.nvr.nvrservice.web;

import com.nvr.nvrservice.api.dto.AddressDto;
import com.nvr.nvrservice.api.dto.ChannelDto;
import com.nvr.nvrservice.api.dto.CreateAddressRequest;
import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
import com.nvr.nvrservice.api.dto.UpdateChannelReq;
import com.nvr.nvrservice.service.AddressService;
import com.nvr.nvrservice.service.NvrDeviceService;
import com.nvr.nvrservice.service.NvrSyncService;
import com.nvr.nvrservice.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminController {

    private final AddressService addressService;
    private final NvrDeviceService deviceService;
    private final NvrSyncService syncService;

    // --- utils ---
    private Long getAdminUserId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String val = attrs.getRequest().getHeader("X-Admin-User");
        if (val == null) throw new RuntimeException("Missing header X-Admin-User");
        return Long.valueOf(val);
    }

    private void attachAdminUser(Long userId) {
        // Здесь мы намеренно НЕ помечаем пользователя как SUPER_ADMIN в контексте,
        // потому что хотим смотреть систему "как он" (с его правами).
        var ctx = new UserContext(
                userId,
                null,     // роль не задаём — работаем строго от лица пользователя
                "FREE",   // план можно не использовать
                null,     // max cameras = unlimited
                14
        );
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        attrs.getRequest().setAttribute("userContext", ctx);

        var auth = new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // --------------------------------------------------------
    // ADDRESSES
    // --------------------------------------------------------


    @GetMapping("/addresses")
    public ResponseEntity<?> listAddresses() {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        var result = addressService.getForOwner(uid).stream()
                .map(a -> new AddressDto(
                        String.format("%06d", a.getId()),
                        a.getLabel(),
                        a.getCity(),
                        a.getStreet(),
                        a.getHouse(),
                        a.getApartment(),
                        a.getComment()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/addresses")
    public ResponseEntity<?> createAddress(@RequestBody AddressReq req) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        var a = addressService.create(
                uid,
                new CreateAddressRequest(
                        req.label,
                        req.city,
                        req.street,
                        req.house,
                        req.apartment,
                        req.comment
                )
        );
        return ResponseEntity.ok(new AddressDto(
                String.format("%06d", a.getId()),
                a.getLabel(),
                a.getCity(),
                a.getStreet(),
                a.getHouse(),
                a.getApartment(),
                a.getComment()
        ));
    }

    // --------------------------------------------------------
    // DEVICES
    // --------------------------------------------------------

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        Page<DeviceDto> devices = deviceService.list(uid, PageRequest.of(page, size));
        return ResponseEntity.ok(devices.getContent());
    }

    @PostMapping("/devices")
    public ResponseEntity<?> createDevice(@RequestBody CreateDeviceReq req) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        DeviceDto d = deviceService.create(uid, req);
        return ResponseEntity.ok(d);
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<?> updateDevice(@PathVariable Long id, @RequestBody UpdateDeviceReq req) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        var dev = deviceService.update(uid, id, req);
        return ResponseEntity.ok(dev);
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<?> deleteDevice(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        deviceService.delete(uid, id);
        return ResponseEntity.ok("deleted");
    }

    // --------------------------------------------------------
    // CHANNELS
    // --------------------------------------------------------

    @GetMapping("/devices/{id}/channels")
    public ResponseEntity<?> getDeviceChannels(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        List<ChannelDto> channels = deviceService.getChannels(uid, id);
        return ResponseEntity.ok(channels);
    }

    @PutMapping("/devices/{deviceId}/channels/{channelId}")
    public ResponseEntity<?> updateChannel(
            @PathVariable Long deviceId,
            @PathVariable Long channelId,
            @RequestBody UpdateChannelReq req) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        ChannelDto channel = deviceService.updateChannel(uid, deviceId, channelId, req);
        return ResponseEntity.ok(channel);
    }

    @PostMapping("/devices/{id}/sync")
    public ResponseEntity<?> syncDeviceChannels(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        try {
            // Запускаем синхронизацию синхронно (быстро, только структура + nvr_status)
            syncService.syncDeviceChannels(id);
            
            // Возвращаем информацию о синхронизации
            SyncResponse response = new SyncResponse();
            response.deviceId = id;
            response.syncStartedAt = java.time.OffsetDateTime.now();
            response.message = "Synchronization completed";
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Sync failed: " + e.getMessage());
        }
    }

    @PostMapping("/devices/{id}/check-rtsp")
    public ResponseEntity<?> checkRtspHealth(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        try {
            // Запускаем RTSP проверку (может занять время, но выполняется асинхронно внутри)
            syncService.checkRtspHealthForDevice(id);
            
            SyncResponse response = new SyncResponse();
            response.deviceId = id;
            response.syncStartedAt = java.time.OffsetDateTime.now();
            response.message = "RTSP health check started";
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("RTSP check failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<?> deleteAddress(@PathVariable Long id) {
        Long uid = getAdminUserId();
        attachAdminUser(uid);
        addressService.delete(uid, id);
        return ResponseEntity.ok("deleted");
    }

    // --- DTOs ---
    @Data
    public static class AddressReq {
        public String label;
        public String city;
        public String street;
        public String house;
        public String apartment;
        public String comment;
    }

    @Data
    public static class SyncResponse {
        public Long deviceId;
        public java.time.OffsetDateTime syncStartedAt;
        public String message;
    }
}
