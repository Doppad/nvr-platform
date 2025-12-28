package com.nvr.nvrservice.repo;

import com.nvr.nvrservice.domain.NvrCamera;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NvrCameraRepo extends JpaRepository<NvrCamera, Long> {
    List<NvrCamera> findByDeviceId(Long deviceId);

    @Query("select count(c) from NvrCamera c where c.device.ownerId = :ownerId")
    long countByOwner(Long ownerId);
    
    /**
     * Проверяет наличие камер с has_camera=true и (rtsp_status is null или rtsp_status='NONE') для устройства.
     * Оптимизированный запрос для проверки необходимости RTSP проверки.
     */
    @Query("select count(c) > 0 from NvrCamera c where c.device.id = :deviceId " +
           "and c.hasCamera = true and (c.rtspStatus is null or c.rtspStatus = 'NONE')")
    boolean existsByDeviceIdAndHasCameraTrueAndRtspStatusNullOrNONE(@Param("deviceId") Long deviceId);
    
    /**
     * Проверяет наличие камер с has_camera=true и nvr_status='UNKNOWN' для устройства.
     * Оптимизированный запрос для проверки необходимости RTSP проверки.
     */
    @Query("select count(c) > 0 from NvrCamera c where c.device.id = :deviceId " +
           "and c.hasCamera = true and c.nvrStatus = 'UNKNOWN'")
    boolean existsByDeviceIdAndHasCameraTrueAndNvrStatusUNKNOWN(@Param("deviceId") Long deviceId);
    
    /**
     * Находит все камеры пользователя (по ownerId устройства).
     */
    @Query("select distinct c from NvrCamera c where c.device.ownerId = :ownerId")
    List<NvrCamera> findByDeviceOwnerId(@Param("ownerId") Long ownerId);
    
    /**
     * Находит все камеры устройств, привязанных к указанному адресу.
     */
    @Query("select distinct c from NvrCamera c where c.device.addressEntity.id = :addressId")
    List<NvrCamera> findByDeviceAddressId(@Param("addressId") Long addressId);
    
    /**
     * Находит все камеры пользователя, привязанные к указанному адресу.
     */
    @Query("select c from NvrCamera c where c.device.ownerId = :ownerId and c.device.addressEntity.id = :addressId")
    List<NvrCamera> findByDeviceOwnerIdAndAddressId(@Param("ownerId") Long ownerId, @Param("addressId") Long addressId);
}
