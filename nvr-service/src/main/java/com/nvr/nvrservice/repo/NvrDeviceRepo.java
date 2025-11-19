package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NvrDeviceRepo extends JpaRepository<NvrDevice, Long> {
    Page<NvrDevice> findByOwnerId(Long ownerId, Pageable pageable);
    long countByOwnerId(Long ownerId);

    // для GET /nvr/devices/{id}
    Optional<NvrDevice> findByIdAndOwnerId(Long id, Long ownerId);

    // GET /nvr/devices/by-address/{addressId}
    Page<NvrDevice> findByOwnerIdAndAddressEntity_Id(Long ownerId, Long addressId, Pageable pageable);

}
