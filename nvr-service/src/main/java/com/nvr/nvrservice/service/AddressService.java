package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.CreateAddressRequest;
import com.nvr.nvrservice.domain.Address;
import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepo repository;
    private final NvrDeviceRepo deviceRepo;

    private UserContext userCtxOrThrow() {
        // Аналогично NvrDeviceService, но здесь не хотим тащить SecurityContext напрямую,
        // поэтому предполагаем, что UserContext уже положен в request-атрибут фильтром.
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        UserContext ctx = null;
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            Object uc = sra.getRequest().getAttribute("userContext");
            if (uc instanceof UserContext u) ctx = u;
        }
        if (ctx == null || ctx.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user in context");
        }
        return ctx;
    }

    private boolean isSuperAdmin(UserContext ctx) {
        return ctx.role() != null && "SUPER_ADMIN".equalsIgnoreCase(ctx.role());
    }

    /**
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - Для админки: возвращаем все адреса (используется в AdminController)
     * - Для обычных пользователей: используйте getUserAddress() вместо этого метода
     * 
     * @deprecated Используйте getUserAddress() для обычных пользователей
     */
    @Deprecated
    public List<Address> getForOwner(Long ownerId) {
        UserContext ctx = userCtxOrThrow();
        if (isSuperAdmin(ctx)) {
            // SUPER_ADMIN видит все адреса в системе (для админки)
            return repository.findAll();
        }
        // Fallback: для обратной совместимости со старыми данными
        return repository.findByOwnerId(ctx.userId());
    }
    
    /**
     * Получает адрес пользователя по его addressId из UserContext.
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: Address теперь глобальные, не привязаны к ownerId.
     * 
     * @return Address пользователя или null, если addressId не назначен
     */
    public Address getUserAddress() {
        UserContext ctx = userCtxOrThrow();
        if (ctx.addressId() == null) {
            return null;
        }
        return repository.findById(ctx.addressId()).orElse(null);
    }

    /**
     * Создает новый Address (только для админки).
     * 
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные (не привязаны к ownerId)
     * - ownerId сохраняется как metadata для обратной совместимости
     * - Создание Address доступно только через админку (/admin/api/addresses)
     * - Обычные пользователи не могут создавать Address
     * 
     * @param ownerId ID владельца (metadata, deprecated, используется только для админки)
     * @param req данные адреса
     * @return созданный Address
     */
    public Address create(Long ownerId, CreateAddressRequest req) {
        Address a = new Address();
        a.setOwnerId(ownerId); // metadata для обратной совместимости
        a.setLabel(req.label());
        a.setCity(req.city());
        a.setStreet(req.street());
        a.setHouse(req.house());
        a.setApartment(req.apartment());
        a.setComment(req.comment());
        a.setCreatedAt(OffsetDateTime.now());
        a.setUpdatedAt(OffsetDateTime.now());
        return repository.save(a);
    }

    /**
     * Удаляет Address (только для админки).
     * 
     * ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS:
     * - Address теперь глобальные, проверка ownerId убрана (кроме админки)
     * - Удаление доступно только через админку
     * 
     * @param ownerId ID владельца (deprecated, используется только для админки, не проверяется)
     * @param addressId ID адреса для удаления
     */
    @Transactional
    public void delete(Long ownerId, Long addressId) {
        // ПЕРЕХОД К ГЛОБАЛЬНЫМ ADDRESS: проверка ownerId убрана, Address глобальные
        // Для админки ownerId может использоваться как metadata, но не как ограничение доступа
        Address address = repository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        boolean hasDevices = deviceRepo.existsByAddressEntity_Id(addressId);
        if (hasDevices) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Address has devices. Remove them first.");
        }

        repository.delete(address);
    }
}
