package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrCamera;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NvrCameraRepo extends JpaRepository<NvrCamera, Long> {
    List<NvrCamera> findByDeviceId(Long deviceId);

    @Query("select count(c) from NvrCamera c where c.device.ownerId = :ownerId")
    long countByOwner(Long ownerId);
}
