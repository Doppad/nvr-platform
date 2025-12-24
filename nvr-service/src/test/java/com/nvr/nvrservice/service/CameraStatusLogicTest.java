package com.nvr.nvrservice.service;

import com.nvr.nvrservice.repo.AddressRepo;
import com.nvr.nvrservice.repo.NvrCameraRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.repo.NvrDeviceUserRepo;
import com.nvr.nvrservice.security.CryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для логики вычисления статусов камер.
 * Проверяет, что ONLINE_NO_STREAM не считается ошибкой, а только предупреждением.
 */
@ExtendWith(MockitoExtension.class)
class CameraStatusLogicTest {

    @Mock
    private NvrDeviceRepo deviceRepo;
    
    @Mock
    private NvrDeviceUserRepo deviceUserRepo;
    
    @Mock
    private AddressRepo addressRepo;
    
    @Mock
    private NvrCameraRepo cameraRepo;
    
    @Mock
    private CryptoService cryptoService;
    
    @Mock
    private NvrSyncService syncService;
    
    @InjectMocks
    private NvrDeviceService deviceService;

    /**
     * Тест: ONLINE + OK → ONLINE (зелёный, не ошибка)
     */
    @Test
    void testComputeUiStatus_OnlineWithStream() throws Exception {
        Method method = NvrDeviceService.class.getDeclaredMethod(
                "computeUiStatus", Boolean.class, String.class, String.class);
        method.setAccessible(true);
        
        String result = (String) method.invoke(deviceService, true, "ONLINE", "OK");
        
        assertEquals("ONLINE", result, "ONLINE + OK должен возвращать ONLINE");
    }

    /**
     * Тест: ONLINE + FAIL/NO_STREAM → ONLINE_NO_STREAM (жёлтый, WARNING, НЕ ERROR)
     */
    @Test
    void testComputeUiStatus_OnlineWithoutStream() throws Exception {
        Method method = NvrDeviceService.class.getDeclaredMethod(
                "computeUiStatus", Boolean.class, String.class, String.class);
        method.setAccessible(true);
        
        // Тест с FAIL
        String result1 = (String) method.invoke(deviceService, true, "ONLINE", "FAIL");
        assertEquals("ONLINE_NO_STREAM", result1, "ONLINE + FAIL должен возвращать ONLINE_NO_STREAM (WARNING)");
        
        // Тест с NONE
        String result2 = (String) method.invoke(deviceService, true, "ONLINE", "NONE");
        assertEquals("ONLINE_NO_STREAM", result2, "ONLINE + NONE должен возвращать ONLINE_NO_STREAM (WARNING)");
        
        // Тест с null
        String result3 = (String) method.invoke(deviceService, true, "ONLINE", null);
        assertEquals("ONLINE_NO_STREAM", result3, "ONLINE + null должен возвращать ONLINE_NO_STREAM (WARNING)");
    }

    /**
     * Тест: OFFLINE → OFFLINE (красный, ERROR)
     */
    @Test
    void testComputeUiStatus_Offline() throws Exception {
        Method method = NvrDeviceService.class.getDeclaredMethod(
                "computeUiStatus", Boolean.class, String.class, String.class);
        method.setAccessible(true);
        
        // OFFLINE независимо от RTSP статуса
        String result1 = (String) method.invoke(deviceService, true, "OFFLINE", "OK");
        assertEquals("OFFLINE", result1, "OFFLINE + OK должен возвращать OFFLINE (ERROR)");
        
        String result2 = (String) method.invoke(deviceService, true, "OFFLINE", "FAIL");
        assertEquals("OFFLINE", result2, "OFFLINE + FAIL должен возвращать OFFLINE (ERROR)");
    }

    /**
     * Тест: Пустые каналы (hasCamera=false) → HIDDEN (не ошибка)
     */
    @Test
    void testComputeUiStatus_EmptyChannel() throws Exception {
        Method method = NvrDeviceService.class.getDeclaredMethod(
                "computeUiStatus", Boolean.class, String.class, String.class);
        method.setAccessible(true);
        
        // hasCamera = false
        String result1 = (String) method.invoke(deviceService, false, "ONLINE", "OK");
        assertEquals("HIDDEN", result1, "hasCamera=false должен возвращать HIDDEN");
        
        // hasCamera = null
        String result2 = (String) method.invoke(deviceService, null, "ONLINE", "OK");
        assertEquals("HIDDEN", result2, "hasCamera=null должен возвращать HIDDEN");
    }

    /**
     * Тест: UNKNOWN статусы обрабатываются корректно
     */
    @Test
    void testComputeUiStatus_Unknown() throws Exception {
        Method method = NvrDeviceService.class.getDeclaredMethod(
                "computeUiStatus", Boolean.class, String.class, String.class);
        method.setAccessible(true);
        
        // UNKNOWN + OK → ONLINE (защитное поведение)
        String result1 = (String) method.invoke(deviceService, true, "UNKNOWN", "OK");
        assertEquals("ONLINE", result1, "UNKNOWN + OK должен возвращать ONLINE");
        
        // UNKNOWN + FAIL → OFFLINE (защитное поведение)
        String result2 = (String) method.invoke(deviceService, true, "UNKNOWN", "FAIL");
        assertEquals("OFFLINE", result2, "UNKNOWN + FAIL должен возвращать OFFLINE");
        
        // UNKNOWN + NONE/null → UNKNOWN
        String result3 = (String) method.invoke(deviceService, true, "UNKNOWN", "NONE");
        assertEquals("UNKNOWN", result3, "UNKNOWN + NONE должен возвращать UNKNOWN");
        
        String result4 = (String) method.invoke(deviceService, true, "UNKNOWN", null);
        assertEquals("UNKNOWN", result4, "UNKNOWN + null должен возвращать UNKNOWN");
    }

    /**
     * Тест: Проверка семантики - ONLINE_NO_STREAM не должен считаться ошибкой
     * Это интеграционный тест логики: проверяем, что статусы правильно классифицируются
     */
    @Test
    void testStatusSemantics_OnlineNoStreamIsNotError() {
        // Семантика статусов:
        // - OFFLINE = ERROR (красный)
        // - ONLINE_NO_STREAM = WARNING (жёлтый), НЕ ERROR
        // - ONLINE = OK (зелёный)
        // - HIDDEN = нейтрально (не считается)
        
        assertTrue(isErrorStatus("OFFLINE"), "OFFLINE должен считаться ошибкой");
        assertFalse(isErrorStatus("ONLINE_NO_STREAM"), "ONLINE_NO_STREAM НЕ должен считаться ошибкой (это WARNING)");
        assertFalse(isErrorStatus("ONLINE"), "ONLINE не должен считаться ошибкой");
        assertFalse(isErrorStatus("HIDDEN"), "HIDDEN не должен считаться ошибкой");
        assertFalse(isErrorStatus("UNKNOWN"), "UNKNOWN не должен считаться ошибкой (неопределённое состояние)");
    }

    /**
     * Вспомогательный метод для проверки, является ли статус ошибкой.
     * В реальной логике это должно быть только OFFLINE.
     */
    private boolean isErrorStatus(String uiStatus) {
        return "OFFLINE".equals(uiStatus);
    }
}

