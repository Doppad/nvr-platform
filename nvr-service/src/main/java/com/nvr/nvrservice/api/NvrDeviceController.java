package com.nvr.nvrservice.api;

import com.nvr.nvrservice.api.dto.CreateDeviceReq;
import com.nvr.nvrservice.api.dto.DeviceDto;
import com.nvr.nvrservice.api.dto.NvrDeviceDto;
import com.nvr.nvrservice.api.dto.UpdateDeviceReq;
import com.nvr.nvrservice.api.mapper.NvrDeviceMapper;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.service.NvrDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/nvr/devices")
@RequiredArgsConstructor
public class NvrDeviceController {

    private final NvrDeviceService service;

    /**
     * Получаем userId из SecurityContext.
     * Если токен отсутствует — бросаем 401.
     */
    private Long currentUserIdOrThrow() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing JWT token");
        Object p = auth.getPrincipal();
        if (p instanceof Long l) return l;
        if (p instanceof String s) return Long.valueOf(s);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token principal");
    }

    // CREATE
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto create(@Valid @RequestBody CreateDeviceReq req) {
        Long ownerId = currentUserIdOrThrow();
        return service.create(ownerId, req);
    }

    // LIST
    @GetMapping
    public Page<DeviceDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        Long ownerId = currentUserIdOrThrow();

        Sort sortSpec;
        if (sort.contains(",")) {
            String[] parts = sort.split(",", 2);
            sortSpec = Sort.by(
                    "desc".equalsIgnoreCase(parts[1]) ? Sort.Direction.DESC : Sort.Direction.ASC,
                    parts[0]
            );
        } else {
            sortSpec = Sort.by(Sort.Direction.DESC, sort);
        }

        Pageable pageable = PageRequest.of(page, size, sortSpec);

        // ВАЖНО: теперь Page<DeviceDto>, а не Page<NvrDevice>
        Page<DeviceDto> pg = service.list(ownerId, pageable);

        // Можно просто вернуть, без ручного маппинга
        return pg;
    }

    // GET ONE
    @GetMapping("/{id}")
    public NvrDeviceDto getOne(@PathVariable Long id) {
        Long ownerId = currentUserIdOrThrow();
        NvrDevice entity = service.get(ownerId, id);
        return NvrDeviceMapper.toDto(entity);
    }

    // UPDATE
    @PutMapping("/{id}")
    public NvrDevice update(@PathVariable Long id, @RequestBody UpdateDeviceReq req) {
        Long ownerId = currentUserIdOrThrow();
        return service.update(ownerId, id, req);
    }

    // DELETE
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        Long ownerId = currentUserIdOrThrow();
        service.delete(ownerId, id);
    }
}
