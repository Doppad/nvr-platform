package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NvrDeviceRepo extends JpaRepository<NvrDevice, Long> {
    // DEPRECATED: Используется только для обратной совместимости
    @Deprecated
    Page<NvrDevice> findByOwnerId(Long ownerId, Pageable pageable);
    @Deprecated
    long countByOwnerId(Long ownerId);
    @Deprecated
    Optional<NvrDevice> findByIdAndOwnerId(Long id, Long ownerId);

    // НОВАЯ МОДЕЛЬ: устройства привязаны к Address через address_id
    // Получить все устройства по addressId
    Page<NvrDevice> findByAddressEntity_Id(Long addressId, Pageable pageable);
    List<NvrDevice> findByAddressEntity_Id(Long addressId);
    long countByAddressEntity_Id(Long addressId);
    
    // Получить устройство по ID и проверить, что оно принадлежит addressId
    Optional<NvrDevice> findByIdAndAddressEntity_Id(Long id, Long addressId);

    boolean existsByAddressEntity_Id(Long addressId);
    
    // DEPRECATED: для обратной совместимости
    @Deprecated
    Page<NvrDevice> findByOwnerIdAndAddressEntity_Id(Long ownerId, Long addressId, Pageable pageable);
}
