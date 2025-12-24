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

    /**
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - Пользователь видит только свой назначенный addressId (из UserContext)
     * - Создание Address доступно только через админку (/admin/api/addresses)
     */
    @GetMapping
    public List<AddressDto> list() {
        // Пользователь видит только свой назначенный addressId (глобальный Address)
        Address address = service.getUserAddress();
        if (address == null) {
            return List.of(); // Пользователь без addressId - возвращаем пустой список
        }
        // Возвращаем только один адрес пользователя
        return List.of(new AddressDto(
                String.format("%06d", address.getId()),
                address.getLabel(),
                address.getCity(),
                address.getStreet(),
                address.getHouse(),
                address.getApartment(),
                address.getComment()
        ));
    }

    /**
     * УДАЛЕНО: Создание Address через обычные API больше не доступно.
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные и создаются только через админку (/admin/api/addresses)
     * - Пользователь не может создавать новые Address
     * - При регистрации пользователю автоматически назначается addressId
     */
    // @PostMapping - удален, создание только через админку

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user");
        }
        return (Long) auth.getPrincipal(); // здесь ты подставляешь свою логику (UserContext, кастомный principal и т.п.)
    }
}
