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

    // getForOwner - дай все адреса этого пользователя
    public List<Address> getForOwner(Long ownerId) {
        UserContext ctx = userCtxOrThrow();
        if (isSuperAdmin(ctx)) {
            // SUPER_ADMIN видит все адреса в системе
            return repository.findAll();
        }
        return repository.findByOwnerId(ctx.userId());
    }

    /** тут я:
     * жестко привязываю адрес к владельцу (ownerId из JWT)
     * задаю сист. поля (createAt, updateAt)
     * привожу всё к сущности Address
     * сохр. через репо
     *
     * Так я избегаю ситуации, когда фронт внезапно присылает ownerId в теле, и кто-то может создать адрес “от имени другого пользователя”.
     * Важная идея:
     * ownerId мы всегда берём из контекста безопасности / токена, а не из JSON.
      */
    public Address create(Long ownerId, CreateAddressRequest req) {
        Address a = new Address();
        a.setOwnerId(ownerId);
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

    @Transactional
    public void delete(Long ownerId, Long addressId) {
        Address address = repository.findById(addressId)
                .filter(a -> a.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        boolean hasDevices = deviceRepo.existsByAddressEntity_Id(addressId);
        if (hasDevices) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Address has devices. Remove them first.");
        }

        repository.delete(address);
    }
}
