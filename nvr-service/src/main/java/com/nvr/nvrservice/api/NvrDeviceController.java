package com.nvr.nvrservice.api;

import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.service.NvrDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/nvr/devices")
@RequiredArgsConstructor
public class NvrDeviceController {

    private final NvrDeviceService svc;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto create(@RequestHeader(value = "X-Debug-UserId", required = false) Long ownerId,
                            @Valid @RequestBody CreateDeviceReq req) {
        if (ownerId == null) ownerId = 1L; // временно для теста без JWT
        return svc.create(ownerId, req);
    }
}
