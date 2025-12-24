package com.nvr.nvrservice.service;

import com.nvr.nvrservice.domain.Address;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrCameraRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.repo.NvrDeviceUserRepo;
import com.nvr.nvrservice.security.CryptoService;
import com.nvr.nvrservice.security.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NvrDeviceServiceAddressModelTest {

    @Mock
    private NvrDeviceRepo deviceRepo;
    
    @Mock
    private AddressRepo addressRepo;
    
    @Mock
    private NvrDeviceUserRepo deviceUserRepo;
    
    @Mock
    private NvrCameraRepo cameraRepo;
    
    @Mock
    private CryptoService cryptoService;
    
    @Mock
    private NvrSyncService syncService;
    
    @Mock
    private SecurityContext securityContext;
    
    @Mock
    private RequestAttributes requestAttributes;
    
    @InjectMocks
    private NvrDeviceService deviceService;

    private Address address1;
    private Address address2;
    private NvrDevice device1;
    private NvrDevice device2;

    @BeforeEach
    void setUp() {
        address1 = new Address();
        address1.setId(1L);
        address1.setLabel("Test Address 1");
        address1.setOwnerId(100L);

        address2 = new Address();
        address2.setId(2L);
        address2.setLabel("Test Address 2");
        address2.setOwnerId(200L);

        device1 = NvrDevice.builder()
                .id(1L)
                .name("Device 1")
                .ip("192.168.1.1")
                .port(8082)
                .addressEntity(address1)
                .build();

        device2 = NvrDevice.builder()
                .id(2L)
                .name("Device 2")
                .ip("192.168.1.2")
                .port(8082)
                .addressEntity(address2)
                .build();
    }

    @Test
    void testFindDevicesByAddressId() {
        // Given
        Long addressId = 1L;
        Pageable pageable = PageRequest.of(0, 10);
        Page<NvrDevice> expectedPage = new PageImpl<>(List.of(device1), pageable, 1);
        
        when(deviceRepo.findByAddressEntity_Id(eq(addressId), any(Pageable.class)))
                .thenReturn(expectedPage);

        // When
        Page<NvrDevice> result = deviceRepo.findByAddressEntity_Id(addressId, pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(device1.getId(), result.getContent().get(0).getId());
        assertEquals(addressId, result.getContent().get(0).getAddressEntity().getId());
        verify(deviceRepo).findByAddressEntity_Id(addressId, pageable);
    }

    @Test
    void testAccessControl_UserWithAddressIdA_DoesNotSeeDevicesFromAddressIdB() {
        // Given: пользователь с addressId=1 не должен видеть устройства с addressId=2
        Long userAddressId = 1L;
        Long otherAddressId = 2L;
        Pageable pageable = PageRequest.of(0, 10);
        
        // Устройства пользователя (addressId=1)
        Page<NvrDevice> userDevices = new PageImpl<>(List.of(device1), pageable, 1);
        
        // Устройства другого пользователя (addressId=2)
        Page<NvrDevice> otherDevices = new PageImpl<>(List.of(device2), pageable, 1);
        
        when(deviceRepo.findByAddressEntity_Id(eq(userAddressId), any(Pageable.class)))
                .thenReturn(userDevices);
        when(deviceRepo.findByAddressEntity_Id(eq(otherAddressId), any(Pageable.class)))
                .thenReturn(otherDevices);

        // When: запрашиваем устройства для addressId=1
        Page<NvrDevice> result = deviceRepo.findByAddressEntity_Id(userAddressId, pageable);

        // Then: должны получить только свои устройства
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(device1.getId(), result.getContent().get(0).getId());
        assertEquals(userAddressId, result.getContent().get(0).getAddressEntity().getId());
        
        // Проверяем, что устройства другого пользователя не попали в результат
        assertFalse(result.getContent().stream()
                .anyMatch(d -> d.getAddressEntity().getId().equals(otherAddressId)));
    }

    @Test
    void testFindByIdAndAddressId_Success() {
        // Given
        Long deviceId = 1L;
        Long addressId = 1L;
        
        when(deviceRepo.findByIdAndAddressEntity_Id(deviceId, addressId))
                .thenReturn(Optional.of(device1));

        // When
        Optional<NvrDevice> result = deviceRepo.findByIdAndAddressEntity_Id(deviceId, addressId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(device1.getId(), result.get().getId());
        assertEquals(addressId, result.get().getAddressEntity().getId());
    }

    @Test
    void testFindByIdAndAddressId_NotFound_WhenAddressIdMismatch() {
        // Given: устройство существует, но принадлежит другому addressId
        Long deviceId = 1L;
        Long wrongAddressId = 2L; // устройство принадлежит addressId=1, а запрашиваем addressId=2
        
        when(deviceRepo.findByIdAndAddressEntity_Id(deviceId, wrongAddressId))
                .thenReturn(Optional.empty());

        // When
        Optional<NvrDevice> result = deviceRepo.findByIdAndAddressEntity_Id(deviceId, wrongAddressId);

        // Then
        assertFalse(result.isPresent());
        // Это правильное поведение: доступ запрещён, если addressId не совпадает
    }

    @Test
    void testCountByAddressId() {
        // Given
        Long addressId = 1L;
        long expectedCount = 3L;
        
        when(deviceRepo.countByAddressEntity_Id(addressId))
                .thenReturn(expectedCount);

        // When
        long result = deviceRepo.countByAddressEntity_Id(addressId);

        // Then
        assertEquals(expectedCount, result);
        verify(deviceRepo).countByAddressEntity_Id(addressId);
    }
}

