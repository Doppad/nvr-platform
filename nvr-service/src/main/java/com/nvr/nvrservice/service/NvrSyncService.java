package com.nvr.nvrservice.service;

import com.nvr.nvrservice.domain.NvrCamera;
import com.nvr.nvrservice.domain.NvrDevice;
import com.nvr.nvrservice.domain.NvrDeviceUser;
import com.nvr.nvrservice.repo.NvrCameraRepo;
import com.nvr.nvrservice.repo.NvrDeviceRepo;
import com.nvr.nvrservice.repo.NvrDeviceUserRepo;
import com.nvr.nvrservice.security.CryptoService;
import com.nvr.nvrservice.service.dto.DahuaChannelDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Сервис для синхронизации каналов с NVR устройствами.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NvrSyncService {

    private final NvrDeviceRepo deviceRepo;
    private final NvrCameraRepo cameraRepo;
    private final NvrDeviceUserRepo deviceUserRepo;
    private final DahuaApiClient dahuaApiClient;
    private final CryptoService cryptoService;
    private final RtspHealthChecker rtspHealthChecker;
    
    // Thread pool для асинхронных RTSP проверок
    private final ExecutorService rtspCheckExecutor = Executors.newFixedThreadPool(16);

    /**
     * Синхронизирует каналы для всех устройств Dahua.
     * Выполняется по расписанию (каждые 5 минут).
     */
    @Scheduled(fixedRate = 300000) // 5 минут
    @Transactional
    public void syncAllDevices() {
        log.info("Starting synchronization of all Dahua devices");

        List<NvrDevice> dahuaDevices = deviceRepo.findAll().stream()
                .filter(device -> "Dahua".equalsIgnoreCase(device.getVendor()))
                .collect(Collectors.toList());

        log.info("Found {} Dahua devices to sync", dahuaDevices.size());

        for (NvrDevice device : dahuaDevices) {
            try {
                syncDeviceChannels(device);
            } catch (Exception e) {
                log.error("Failed to sync channels for device {} ({}): {}",
                        device.getId(), device.getName(), e.getMessage(), e);
            }
        }

        log.info("Finished synchronization of all Dahua devices");
    }

    /**
     * Синхронизирует каналы для конкретного устройства.
     *
     * @param deviceId ID устройства
     */
    @Transactional
    public void syncDeviceChannels(Long deviceId) {
        NvrDevice device = deviceRepo.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));

        syncDeviceChannels(device);
    }

    /**
     * Синхронизирует каналы для конкретного устройства.
     *
     * @param device устройство
     */
    @Transactional
    public void syncDeviceChannels(NvrDevice device) {
        log.info("Syncing channels for device {} ({}: {})", device.getId(), device.getName(), device.getIp());

        // Получаем учётные данные администратора (обычно роль "user_admin" или "user_default")
        Optional<NvrDeviceUser> adminUser = deviceUserRepo.findByDeviceIdAndRole(device.getId(), "user_admin");
        if (adminUser.isEmpty()) {
            adminUser = deviceUserRepo.findByDeviceIdAndRole(device.getId(), "user_default");
        }

        if (adminUser.isEmpty()) {
            log.warn("No admin user found for device {} ({}), skipping sync", device.getId(), device.getName());
            return;
        }

        NvrDeviceUser user = adminUser.get();
        String username = user.getUsername();
        String password = cryptoService.decrypt(user.getPasswordEnc());

        // Определяем HTTP порт (используем httpPort, если задан, иначе стандартный 80)
        int httpPort = device.getHttpPort() != null ? device.getHttpPort() : 80;
        String baseUrl = String.format("http://%s:%d", device.getIp(), httpPort);

        // Для устройств Dahua 16ch используем ChannelTitle
        // Проверяем по vendor и количеству каналов, или по ID устройства (для конкретного устройства)
        boolean isDahua16ch = "Dahua".equalsIgnoreCase(device.getVendor()) && 
                               (device.getId() != null && device.getId() == 66 || 
                                device.getCamerasCount() != null && device.getCamerasCount() >= 16);
        
        if (isDahua16ch) {
            // Получаем названия каналов из ChannelTitle
            Map<Integer, String> channelTitles = dahuaApiClient.getChannelTitles(baseUrl, username, password);
            
            if (channelTitles.isEmpty()) {
                log.warn("Received empty channel titles from device {} (id={}, ip={}, httpPort={}). " +
                        "Device may be offline or API endpoint returned error/HTML.",
                        device.getName(), device.getId(), device.getIp(), httpPort);
                return;
            }
            
            log.info("Fetched {} channel titles from device {}", channelTitles.size(), device.getId());
            
            // Синхронизируем 16 каналов в БД
            int updatedCount = syncChannelsFromTitles(device, channelTitles, username, password);
            
            // Обновляем количество камер в устройстве
            device.setCamerasCount(16);
            deviceRepo.save(device);
            
            log.info("Fetched 16 channels for device {}. Updated {} channels.", device.getId(), updatedCount);
            
            // Проверяем RTSP доступность для всех каналов
            checkRtspHealthForDevice(device.getId());
        } else {
            // Старая логика для других устройств
            List<DahuaChannelDto> channels = dahuaApiClient.getChannels(baseUrl, username, password);
            
            if (channels.isEmpty()) {
                log.warn("Received empty channel list from device {} (id={}, ip={}, httpPort={}). " +
                        "Device may be offline or API endpoint returned error/HTML.",
                        device.getName(), device.getId(), device.getIp(), httpPort);
                return;
            }
            
            log.info("Fetched {} channels from device {}", channels.size(), device.getId());

            // Получаем состояние камер (опционально, может вернуть пустую Map)
            Map<Integer, String> cameraStates = dahuaApiClient.getCameraStates(baseUrl, username, password);
            if (!cameraStates.isEmpty()) {
                log.info("Fetched {} camera states from device {}", cameraStates.size(), device.getId());
            } else {
                log.debug("No camera states received from device {} (may be normal)", device.getId());
            }

            // Синхронизируем каналы в БД
            syncChannelsToDatabase(device, channels, cameraStates, username, password);

            // Обновляем количество камер в устройстве
            device.setCamerasCount(channels.size());
            deviceRepo.save(device);

            log.info("Successfully synced {} channels for device {}", channels.size(), device.getId());
        }
    }

    /**
     * Синхронизирует каналы в БД: создаёт новые, обновляет существующие, деактивирует удалённые.
     */
    private void syncChannelsToDatabase(
            NvrDevice device,
            List<DahuaChannelDto> channels,
            Map<Integer, String> cameraStates,
            String username,
            String password
    ) {
        // Получаем существующие каналы для устройства
        List<NvrCamera> existingCameras = cameraRepo.findByDeviceId(device.getId());
        Map<Integer, NvrCamera> existingByChannelNo = existingCameras.stream()
                .collect(Collectors.toMap(NvrCamera::getChannelNo, c -> c));

        OffsetDateTime now = OffsetDateTime.now();

        // Формируем RTSP URL шаблон
        // rtsp://{login}:{password}@{ip}:{rtspPort}/cam/realmonitor?channel={channelNo}&subtype=0
        // rtspPort обычно равен port устройства (RTSP порт, обычно 554)
        int rtspPort = device.getPort() != null ? device.getPort() : 554;
        String rtspUrlTemplate = String.format("rtsp://%s:%s@%s:%d/cam/realmonitor?channel=%%d&subtype=0",
                username, password, device.getIp(), rtspPort);

        // Обрабатываем каналы из API
        for (DahuaChannelDto channelDto : channels) {
            int channelNo = channelDto.channelNo();
            NvrCamera camera = existingByChannelNo.get(channelNo);

            // Определяем статус камеры из состояния
            String connectionState = cameraStates.getOrDefault(channelNo, "Unknown");
            String status = mapConnectionStateToStatus(connectionState);

            // Формируем RTSP URL для конкретного канала
            String rtspUrl = String.format(rtspUrlTemplate, channelNo);

            if (camera == null) {
                // Создаём новый канал
                camera = NvrCamera.builder()
                        .device(device)
                        .channelNo(channelNo)
                        .name(channelDto.channelName() != null && !channelDto.channelName().isEmpty()
                                ? channelDto.channelName()
                                : "Channel " + channelNo)
                        .enabled(true)
                        .ipAddress(channelDto.ipAddress())
                        .deviceName(channelDto.deviceName())
                        .channelName(channelDto.channelName())
                        .protocol(channelDto.protocol())
                        .type(channelDto.type())
                        .rtspUrl(rtspUrl)
                        .status(status)
                        .isActive(true)
                        .statusUpdatedAt(now)
                        .createdAt(now)
                        .build();

                log.debug("Creating new camera: channelNo={}, name={}", channelNo, camera.getName());
            } else {
                // Обновляем существующий канал (upsert)
                camera.setName(channelDto.channelName() != null && !channelDto.channelName().isEmpty()
                        ? channelDto.channelName()
                        : "Channel " + channelNo);
                camera.setIpAddress(channelDto.ipAddress());
                camera.setDeviceName(channelDto.deviceName());
                camera.setChannelName(channelDto.channelName());
                camera.setProtocol(channelDto.protocol());
                camera.setType(channelDto.type());
                camera.setRtspUrl(rtspUrl);
                camera.setStatus(status);
                camera.setIsActive(true);
                camera.setStatusUpdatedAt(now);

                log.debug("Updating existing camera: channelNo={}, name={}", channelNo, camera.getName());
            }

            cameraRepo.save(camera);
            existingByChannelNo.remove(channelNo); // Убираем из списка обработанных
        }

        // Деактивируем каналы, которых больше нет в API
        for (NvrCamera removedCamera : existingByChannelNo.values()) {
            removedCamera.setIsActive(false);
            removedCamera.setStatus("UNKNOWN");
            removedCamera.setStatusUpdatedAt(now);
            cameraRepo.save(removedCamera);
            log.debug("Deactivated camera: channelNo={}", removedCamera.getChannelNo());
        }
    }

    /**
     * Синхронизирует 16 каналов из ChannelTitle для устройства Dahua 16ch.
     * 
     * @param device устройство
     * @param channelTitles Map номер канала -> название
     * @param username имя пользователя
     * @param password пароль
     * @return количество обновлённых каналов
     */
    private int syncChannelsFromTitles(
            NvrDevice device,
            Map<Integer, String> channelTitles,
            String username,
            String password
    ) {
        // Получаем существующие каналы для устройства
        List<NvrCamera> existingCameras = cameraRepo.findByDeviceId(device.getId());
        Map<Integer, NvrCamera> existingByChannelNo = existingCameras.stream()
                .collect(Collectors.toMap(NvrCamera::getChannelNo, c -> c));

        OffsetDateTime now = OffsetDateTime.now();
        int rtspPort = device.getPort() != null ? device.getPort() : 554;
        int updatedCount = 0;

        // Создаём/обновляем все 16 каналов
        for (int channelNumber = 1; channelNumber <= 16; channelNumber++) {
            String channelName = channelTitles.getOrDefault(channelNumber, "Channel" + channelNumber);
            
            // Формируем RTSP URL: rtsp://{login}:{password}@{ip}:{rtspPort}/cam/realmonitor?channel={N}&subtype=1
            String rtspUrl = String.format("rtsp://%s:%s@%s:%d/cam/realmonitor?channel=%d&subtype=1",
                    username, password, device.getIp(), rtspPort, channelNumber);

            NvrCamera camera = existingByChannelNo.get(channelNumber);

            if (camera == null) {
                // Создаём новый канал
                camera = NvrCamera.builder()
                        .device(device)
                        .channelNo(channelNumber)
                        .name(channelName)
                        .rtspUrl(rtspUrl)
                        .enabled(true)
                        .isActive(false) // Будет обновлено после RTSP проверки
                        .status("UNKNOWN")
                        .statusUpdatedAt(now)
                        .createdAt(now)
                        .build();
                updatedCount++;
                log.debug("Creating channel {}: {}", channelNumber, channelName);
            } else {
                // Обновляем существующий канал
                camera.setName(channelName);
                camera.setRtspUrl(rtspUrl);
                // isActive будет обновлено после RTSP проверки
                camera.setStatusUpdatedAt(now);
                updatedCount++;
                log.debug("Updating channel {}: {}", channelNumber, channelName);
            }

            cameraRepo.save(camera);
        }

        return updatedCount;
    }

    /**
     * Проверяет RTSP доступность для всех каналов устройства.
     * Выполняется асинхронно для всех каналов параллельно.
     * 
     * @param deviceId ID устройства
     */
    private void checkRtspHealthForDevice(Long deviceId) {
        NvrDevice device = deviceRepo.findById(deviceId)
                .orElse(null);
        
        if (device == null) {
            log.warn("Device {} not found for RTSP health check", deviceId);
            return;
        }
        
        // Проверяем только для устройств Dahua
        if (!"Dahua".equalsIgnoreCase(device.getVendor())) {
            log.debug("Skipping RTSP health check for non-Dahua device {}", deviceId);
            return;
        }
        
        List<NvrCamera> channels = cameraRepo.findByDeviceId(deviceId);
        if (channels.isEmpty()) {
            log.debug("No channels found for device {} to check RTSP", deviceId);
            return;
        }
        
        log.info("Starting RTSP health check for device {} ({} channels)", deviceId, channels.size());
        
        // Создаём асинхронные задачи для проверки каждого канала
        List<CompletableFuture<Void>> futures = channels.stream()
                .map(channel -> CompletableFuture.runAsync(() -> {
                    String rtspUrl = channel.getRtspUrl();
                    if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
                        log.debug("Channel {} has no RTSP URL, marking as offline", channel.getChannelNo());
                        channel.setIsActive(false);
                        channel.setStatus("OFFLINE");
                        channel.setStatusUpdatedAt(OffsetDateTime.now());
                        return;
                    }
                    
                    boolean isOnline = rtspHealthChecker.isOnline(rtspUrl);
                    channel.setIsActive(isOnline);
                    channel.setStatus(isOnline ? "ONLINE" : "OFFLINE");
                    channel.setStatusUpdatedAt(OffsetDateTime.now());
                }, rtspCheckExecutor))
                .collect(Collectors.toList());
        
        // Ждём завершения всех проверок
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Сохраняем обновлённые каналы
        cameraRepo.saveAll(channels);
        
        long onlineCount = channels.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .count();
        
        log.info("RTSP health check for device {}: {} online / {} total",
                deviceId, onlineCount, channels.size());
    }

    /**
     * Преобразует состояние подключения камеры в статус.
     */
    private String mapConnectionStateToStatus(String connectionState) {
        if (connectionState == null) {
            return "UNKNOWN";
        }
        return switch (connectionState.toLowerCase()) {
            case "connected" -> "ONLINE";
            case "unconnect", "disconnected" -> "OFFLINE";
            default -> "UNKNOWN";
        };
    }

    /**
     * Закрывает thread pool при завершении приложения.
     */
    @PreDestroy
    public void shutdown() {
        rtspCheckExecutor.shutdown();
        try {
            if (!rtspCheckExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                rtspCheckExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            rtspCheckExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

