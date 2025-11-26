package com.nvr.nvrservice.web;

import com.nvr.nvrservice.api.dto.AddressDto;
import com.nvr.nvrservice.api.dto.CreateAddressRequest;
import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
import com.nvr.nvrservice.service.AddressService;
import com.nvr.nvrservice.service.NvrDeviceService;
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

@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminController {

    private final AddressService addressService;
    private final NvrDeviceService deviceService;

    // --- utils ---
    private Long getAdminUserId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String val = attrs.getRequest().getHeader("X-Admin-User");
        if (val == null) throw new RuntimeException("Missing header X-Admin-User");
        return Long.valueOf(val);
    }

    private void attachAdminUser(Long userId) {
        var ctx = new UserContext(
                userId,
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
                        a.getId(),
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
                a.getId(),
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
}
