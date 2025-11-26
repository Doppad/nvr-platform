package com.nvr.nvrservice.service;

import com.nvr.nvrservice.api.dto.CreateAddressRequest;
import com.nvr.nvrservice.domain.Address;
import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
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

    // getForOwner - дай все адреса этого пользователя
    public List<Address> getForOwner(Long ownerId) {
        return repository.findByOwnerId(ownerId);
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
