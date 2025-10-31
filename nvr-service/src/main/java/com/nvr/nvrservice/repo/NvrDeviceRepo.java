package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NvrDeviceRepo extends JpaRepository<NvrDevice, Long> {
    Page<NvrDevice> findByOwnerId(Long ownerId, Pageable pageable);
    long countByOwnerId(Long ownerId);
}
