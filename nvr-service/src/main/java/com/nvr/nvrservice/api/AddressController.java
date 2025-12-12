package com.nvr.nvrservice.api;

import com.nvr.nvrservice.api.dto.AddressDto;
import com.nvr.nvrservice.api.dto.CreateAddressRequest;
import com.nvr.nvrservice.domain.Address;
import com.nvr.nvrservice.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/nvr/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService service;

    @GetMapping
    public List<AddressDto> list() {
        Long ownerId = currentUserId();     // достал userId из контекста
        List<Address> addresses = service.getForOwner(ownerId);     // бизнес-логика в сервисе
        return addresses.stream()       // превращаю сущности в DTO
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
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressDto create(@RequestBody CreateAddressRequest req) {
        Long ownerId = currentUserId();             // опять userId из токена
        Address a = service.create(ownerId, req);   // бизнес-логика = сервис
        return new AddressDto(                      // отдаю DTO наружу
                String.format("%06d", a.getId()),
                a.getLabel(),
                a.getCity(),
                a.getStreet(),
                a.getHouse(),
                a.getApartment(),
                a.getComment()
        );
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user");
        }
        return (Long) auth.getPrincipal(); // здесь ты подставляешь свою логику (UserContext, кастомный principal и т.п.)
    }
}
