package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrDeviceUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NvrDeviceUserRepo extends JpaRepository<NvrDeviceUser, Long> {
    Optional<NvrDeviceUser> findByDeviceIdAndRole(Long deviceId, String role);
    List<NvrDeviceUser> findByDeviceId(Long deviceId);
}
