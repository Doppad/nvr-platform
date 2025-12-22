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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.time.Instant;

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
    
    // Thread pool для асинхронных RTSP проверок каналов
    private final ExecutorService rtspCheckExecutor = Executors.newFixedThreadPool(16);
    
    // In-memory lock для защиты от параллельных запусков checkRtspHealthForDevice для одного deviceId
    private final ConcurrentHashMap.KeySetView<Long, Boolean> rtspCheckInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Синхронизирует каналы для всех устройств Dahua.
     * Выполняется по расписанию (каждые 5 минут).
     * Обновляет только структуру каналов и nvr_status (не выполняет RTSP проверку).
     */
    @Scheduled(fixedRate = 300000) // 5 минут
    public void syncAllDevices() {
        log.info("Starting synchronization of all Dahua devices");

        // Читаем устройства в отдельной read-only транзакции, чтобы видеть актуальные данные
        List<Long> dahuaDeviceIds = getDahuaDeviceIds();
        
        log.info("Found {} Dahua devices to sync", dahuaDeviceIds.size());

        // Синхронизируем каждое устройство в отдельной транзакции
        for (Long deviceId : dahuaDeviceIds) {
            try {
                syncDeviceChannels(deviceId);
            } catch (Exception e) {
                log.error("Failed to sync channels for device {}: {}",
                        deviceId, e.getMessage(), e);
            }
        }

        log.info("Finished synchronization of all Dahua devices");
    }

    /**
     * Проверяет RTSP доступность для всех устройств Dahua.
     * Выполняется по расписанию (каждые 10 минут).
     * Обновляет только rtsp_status (не трогает структуру и nvr_status).
     * Можно отключить, если RTSP проверка выполняется только вручную.
     */
    @Scheduled(fixedRate = 600000) // 10 минут, старт сразу после готовности Spring контекста
    public void checkRtspHealthForAllDevices() {
        log.info("Starting RTSP health check for all Dahua devices");

        List<Long> dahuaDeviceIds = getDahuaDeviceIds();
        log.info("Found {} Dahua devices for RTSP health check", dahuaDeviceIds.size());

        for (Long deviceId : dahuaDeviceIds) {
            try {
                checkRtspHealthForDevice(deviceId);
            } catch (Exception e) {
                log.error("Failed to check RTSP health for device {}: {}",
                        deviceId, e.getMessage(), e);
            }
        }

        log.info("Finished RTSP health check for all Dahua devices");
    }

    /**
     * Получает список ID всех устройств Dahua в read-only транзакции.
     * Это гарантирует, что мы видим актуальные данные из БД.
     */
    @Transactional(readOnly = true)
    public List<Long> getDahuaDeviceIds() {
        List<NvrDevice> allDevices = deviceRepo.findAll();
        
        List<Long> dahuaDeviceIds = allDevices.stream()
                .filter(device -> "Dahua".equalsIgnoreCase(device.getVendor()))
                .map(NvrDevice::getId)
                .collect(Collectors.toList());

        return dahuaDeviceIds;
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
        log.info("Starting sync for device {} ({}: {})", device.getId(), device.getName(), device.getIp());

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

        // Определяем HTTP порт (используем httpPort, если задан, иначе используем port устройства)
        int httpPort = device.getHttpPort() != null ? device.getHttpPort() : 
                      (device.getPort() != null ? device.getPort() : 80);
        String baseUrl = String.format("http://%s:%d", device.getIp(), httpPort);

        // Получаем максимальное количество каналов из Dahua API (если ещё не сохранено)
        if (device.getMaxChannels() == null && "Dahua".equalsIgnoreCase(device.getVendor())) {
            Integer maxChannels = dahuaApiClient.getMaxRemoteInputChannels(baseUrl, username, password);
            if (maxChannels != null) {
                device.setMaxChannels(maxChannels);
                deviceRepo.save(device);
                log.debug("Retrieved and saved maxChannels={} for device {}", maxChannels, device.getId());
            }
        }

        // Для всех устройств Dahua пытаемся использовать ChannelTitle
        // Если ChannelTitle не работает, используем старый метод getChannels
        if ("Dahua".equalsIgnoreCase(device.getVendor())) {
            // Сначала пытаемся получить каналы через ChannelTitle
            Map<Integer, String> channelTitles = dahuaApiClient.getChannelTitles(baseUrl, username, password);
            
            // Проверяем, что ChannelTitle вернул достаточно каналов
            // Если camerasCount задан и ChannelTitle вернул меньше каналов, используем getChannels
            boolean useChannelTitles = !channelTitles.isEmpty() && 
                    (device.getCamerasCount() == null || channelTitles.size() >= device.getCamerasCount());
            
            if (useChannelTitles) {
                // Успешно получили ChannelTitle с достаточным количеством каналов
                log.debug("Fetched {} channel titles from device {} using ChannelTitle API", 
                        channelTitles.size(), device.getId());
                
                // Синхронизируем каналы в БД
                int updatedCount = syncChannelsFromTitles(device, channelTitles, username, password);
                
                // Обновляем количество камер в устройстве на основе реальных данных
                int actualChannelsCount = channelTitles.size();
                device.setCamerasCount(actualChannelsCount);
                deviceRepo.save(device);
                
                // Подсчитываем статистику для итогового лога
                List<NvrCamera> allCameras = cameraRepo.findByDeviceId(device.getId());
                long onlineCount = allCameras.stream()
                        .filter(c -> "ONLINE".equals(c.getNvrStatus()))
                        .count();
                long offlineCount = allCameras.stream()
                        .filter(c -> "OFFLINE".equals(c.getNvrStatus()))
                        .count();
                long hiddenCount = allCameras.stream()
                        .filter(c -> Boolean.FALSE.equals(c.getHasCamera()))
                        .count();
                
                log.info("Sync completed for device {}: {} channels ({} online, {} offline, {} hidden)", 
                        device.getId(), actualChannelsCount, onlineCount, offlineCount, hiddenCount);
            } else {
                // ChannelTitle не вернул данные или вернул мало каналов - используем старый метод
                log.debug("ChannelTitle returned {} channels, trying getChannels API for device {}", 
                        channelTitles.size(), device.getId());
                
                List<DahuaChannelDto> channels = dahuaApiClient.getChannels(baseUrl, username, password);
                
                if (channels.isEmpty()) {
                    log.warn("Received empty channel list from device {} (id={}, ip={}, httpPort={}). " +
                            "Device may be offline or API endpoint returned error/HTML. " +
                            "Existing camera data preserved.",
                            device.getName(), device.getId(), device.getIp(), httpPort);
                    return;
                }
                
                log.debug("Fetched {} channels from device {} using getChannels API", 
                        channels.size(), device.getId());

                // Получаем состояние камер (опционально, может вернуть пустую Map)
                Map<Integer, String> cameraStates = dahuaApiClient.getCameraStates(baseUrl, username, password);
                log.debug("Fetched {} camera states from device {}", cameraStates.size(), device.getId());

                // Синхронизируем каналы в БД
                syncChannelsToDatabase(device, channels, cameraStates, username, password);

                // Обновляем количество камер в устройстве на основе реальных данных
                device.setCamerasCount(channels.size());
                deviceRepo.save(device);

                // Подсчитываем статистику для итогового лога
                List<NvrCamera> allCameras = cameraRepo.findByDeviceId(device.getId());
                long onlineCount = allCameras.stream()
                        .filter(c -> "ONLINE".equals(c.getNvrStatus()))
                        .count();
                long offlineCount = allCameras.stream()
                        .filter(c -> "OFFLINE".equals(c.getNvrStatus()))
                        .count();
                long hiddenCount = allCameras.stream()
                        .filter(c -> Boolean.FALSE.equals(c.getHasCamera()))
                        .count();
                
                log.info("Sync completed for device {}: {} channels ({} online, {} offline, {} hidden)", 
                        device.getId(), channels.size(), onlineCount, offlineCount, hiddenCount);
            }
            
            // Проверяем, нужно ли запускать RTSP проверку после синхронизации
            // Запускаем только если есть камеры, которые нужно проверить:
            // - rtspStatus is null/NONE (еще не проверяли)
            // - nvrStatus == UNKNOWN (статус неизвестен)
            // Используем оптимизированные existsBy методы вместо findByDeviceId
            boolean needsRtspCheck = cameraRepo.existsByDeviceIdAndHasCameraTrueAndRtspStatusNullOrNONE(device.getId()) ||
                                     cameraRepo.existsByDeviceIdAndHasCameraTrueAndNvrStatusUNKNOWN(device.getId());
            
            if (needsRtspCheck) {
                // Запускаем RTSP проверку сразу после синхронизации для быстрого обновления статусов
                // Запускаем асинхронно, чтобы не блокировать транзакцию синхронизации
                // Используем ForkJoinPool.commonPool() вместо rtspCheckExecutor, чтобы не забивать пул для проверки каналов
                final Long deviceIdForRtspCheck = device.getId();
                CompletableFuture.runAsync(() -> {
                    try {
                        checkRtspHealthForDevice(deviceIdForRtspCheck);
                    } catch (Exception e) {
                        log.error("Failed to check RTSP health for device {} after sync: {}", 
                                deviceIdForRtspCheck, e.getMessage(), e);
                    }
                });
            } else {
                log.debug("Skipping RTSP check for device {} after sync: all cameras already have RTSP status", device.getId());
            }
        } else {
            // Для не-Dahua устройств используем старую логику
            log.warn("Device {} (vendor={}) is not Dahua, skipping sync", 
                    device.getName(), device.getVendor());
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

        // Определяем, были ли получены реальные данные о статусах камер
        // Если cameraStates пустой или null - не обновляем nvr_status, чтобы не затереть существующие значения
        boolean hasCameraStates = cameraStates != null && !cameraStates.isEmpty();
        
        if (!hasCameraStates) {
            log.debug("No camera states received from Dahua API for device {} ({}). " +
                    "Preserving existing nvr_status values.", 
                    device.getId(), device.getName());
        }

        // Обрабатываем каналы из API
        for (DahuaChannelDto channelDto : channels) {
            int channelNo = channelDto.channelNo();
            NvrCamera camera = existingByChannelNo.get(channelNo);

            // Определяем nvr_status и has_camera из состояния (только из Dahua API)
            // ВАЖНО: обновляем nvr_status только если получили реальные данные от API
            String nvrStatus = null;
            boolean hasCamera = false;
            String connectionState = null;
            
            if (hasCameraStates) {
                connectionState = cameraStates.getOrDefault(channelNo, null);
                if (connectionState != null) {
                    nvrStatus = mapConnectionStateToStatus(connectionState);
                    hasCamera = determineHasCameraFromState(connectionState);
                } else {
                    // Если для этого канала нет состояния в API, используем fallback
                    // Камера считается реальной, если есть ip_address, device_name или channel_name
                    hasCamera = (channelDto.ipAddress() != null && !channelDto.ipAddress().isEmpty()) ||
                               (channelDto.deviceName() != null && !channelDto.deviceName().isEmpty()) ||
                               (channelDto.channelName() != null && !channelDto.channelName().isEmpty());
                    nvrStatus = "UNKNOWN";
                }
            } else {
                // Если данных от API нет - сохраняем существующий статус или используем fallback
                if (camera != null && camera.getNvrStatus() != null) {
                    nvrStatus = camera.getNvrStatus(); // Сохраняем существующий статус
                    hasCamera = Boolean.TRUE.equals(camera.getHasCamera()); // Сохраняем существующий has_camera
                } else {
                    // Для новых каналов используем fallback на основе данных канала
                    hasCamera = (channelDto.ipAddress() != null && !channelDto.ipAddress().isEmpty()) ||
                               (channelDto.deviceName() != null && !channelDto.deviceName().isEmpty()) ||
                               (channelDto.channelName() != null && !channelDto.channelName().isEmpty());
                    nvrStatus = "UNKNOWN";
                }
            }

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
                        .status("UNKNOWN") // legacy поле
                        .isActive(true)
                        .statusUpdatedAt(now)
                        .hasCamera(hasCamera)
                        .nvrStatus(nvrStatus)
                        .nvrStatusUpdatedAt(hasCameraStates ? now : null) // Обновляем timestamp только если получили данные
                        // rtsp_status и rtsp_status_updated_at не трогаем - они обновляются отдельно
                        .createdAt(now)
                        .build();

                log.debug("Creating new camera: channelNo={}, name={}, hasCamera={}, nvrStatus={}, hasCameraStates={}", 
                        channelNo, camera.getName(), hasCamera, nvrStatus, hasCameraStates);
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
                camera.setStatus("UNKNOWN"); // legacy поле
                camera.setIsActive(true);
                camera.setStatusUpdatedAt(now);
                // Обновляем только nvr_status (не трогаем rtsp_status)
                camera.setHasCamera(hasCamera);
                // Обновляем nvr_status только если получили реальные данные от API
                if (hasCameraStates) {
                    camera.setNvrStatus(nvrStatus);
                    camera.setNvrStatusUpdatedAt(now);
                } else {
                    // Сохраняем существующий статус, если данных от API нет
                    log.debug("Preserving existing nvr_status={} for channel {} (no API data)", 
                            camera.getNvrStatus(), channelNo);
                }
                // rtsp_status и rtsp_status_updated_at не трогаем - они обновляются отдельно

                log.debug("Updating existing camera: channelNo={}, name={}, hasCamera={}, nvrStatus={}, hasCameraStates={}", 
                        channelNo, camera.getName(), hasCamera, nvrStatus, hasCameraStates);
            }

            cameraRepo.save(camera);
            existingByChannelNo.remove(channelNo); // Убираем из списка обработанных
        }

        // Деактивируем каналы, которых больше нет в API
        for (NvrCamera removedCamera : existingByChannelNo.values()) {
            removedCamera.setIsActive(false);
            removedCamera.setStatus("UNKNOWN"); // legacy поле
            removedCamera.setStatusUpdatedAt(now);
            removedCamera.setHasCamera(false);
            removedCamera.setNvrStatus("UNKNOWN");
            removedCamera.setNvrStatusUpdatedAt(now);
            // rtsp_status не трогаем - он обновляется отдельно
            cameraRepo.save(removedCamera);
            log.debug("Deactivated camera: channelNo={}", removedCamera.getChannelNo());
        }
    }

    /**
     * Синхронизирует каналы из ChannelTitle для устройства Dahua.
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

        // Пытаемся получить статусы камер через API (опционально)
        int httpPort = device.getHttpPort() != null ? device.getHttpPort() : 
                      (device.getPort() != null ? device.getPort() : 80);
        String baseUrl = String.format("http://%s:%d", device.getIp(), httpPort);
        Map<Integer, String> cameraStates = dahuaApiClient.getCameraStates(baseUrl, username, password);
        boolean hasCameraStates = cameraStates != null && !cameraStates.isEmpty();
        
        log.info("Fetched {} camera states for device {} (id={}, ownerId={}, totalChannels={}, username='{}')", 
                cameraStates != null ? cameraStates.size() : 0, 
                device.getName(), device.getId(), device.getOwnerId(), 
                channelTitles.size(), username);
        
        if (!hasCameraStates) {
            log.warn("No camera states received from Dahua API for device {} (id={}, ownerId={}). " +
                    "All channels will be marked as UNKNOWN. " +
                    "Possible causes: user '{}' doesn't have permissions to access getCameraStates API. " +
                    "Consider using user_admin role for full access.",
                    device.getName(), device.getId(), device.getOwnerId(), username);
        } else if (cameraStates.size() < channelTitles.size()) {
            log.warn("Partial camera states received for device {} (id={}, ownerId={}): got {} states for {} channels. " +
                    "Missing states will be marked as UNKNOWN. " +
                    "Possible causes: user '{}' has limited permissions or API returned partial data. " +
                    "Consider using user_admin role for full access.",
                    device.getName(), device.getId(), device.getOwnerId(), 
                    cameraStates.size(), channelTitles.size(), username);
        }

        // Создаём/обновляем все каналы из channelTitles (может быть любое количество)
        for (Map.Entry<Integer, String> entry : channelTitles.entrySet()) {
            int channelNumber = entry.getKey();
            String channelName = entry.getValue();
            
            // Определяем nvr_status и has_camera из cameraStates (если доступно)
            // ВАЖНО: обновляем nvr_status только если получили реальные данные от API
            String nvrStatus = null;
            boolean hasCamera = false;
            String connectionState = null;
            
            if (hasCameraStates && cameraStates.containsKey(channelNumber)) {
                connectionState = cameraStates.get(channelNumber);
                nvrStatus = mapConnectionStateToStatus(connectionState);
                hasCamera = determineHasCameraFromState(connectionState);
            } else {
                // Если данных нет - сохраняем существующий статус или используем fallback
                NvrCamera existingCamera = existingByChannelNo.get(channelNumber);
                if (existingCamera != null && existingCamera.getNvrStatus() != null) {
                    nvrStatus = existingCamera.getNvrStatus(); // Сохраняем существующий статус
                    hasCamera = Boolean.TRUE.equals(existingCamera.getHasCamera()); // Сохраняем существующий has_camera
                } else {
                    // Для новых каналов используем fallback: камера считается реальной, если название не пустое 
                    // и не дефолтное "Channel N"
                    hasCamera = channelName != null && !channelName.isEmpty() && 
                               !channelName.matches("Channel\\s*" + channelNumber);
                    nvrStatus = "UNKNOWN";
                }
            }
            
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
                        .status("UNKNOWN") // legacy поле
                        .statusUpdatedAt(now)
                        .hasCamera(hasCamera)
                        .nvrStatus(nvrStatus)
                        .nvrStatusUpdatedAt(hasCameraStates ? now : null) // Обновляем timestamp только если получили данные
                        // rtsp_status не трогаем - он обновляется отдельно
                        .createdAt(now)
                        .build();
                updatedCount++;
                log.debug("Creating channel {}: {}, hasCamera={}, nvrStatus={}, hasCameraStates={}", 
                        channelNumber, channelName, hasCamera, nvrStatus, hasCameraStates);
            } else {
                // Обновляем существующий канал
                camera.setName(channelName);
                camera.setRtspUrl(rtspUrl);
                camera.setStatus("UNKNOWN"); // legacy поле
                camera.setStatusUpdatedAt(now);
                // Обновляем только nvr_status (не трогаем rtsp_status)
                camera.setHasCamera(hasCamera);
                // Обновляем nvr_status только если получили реальные данные от API
                if (hasCameraStates) {
                    camera.setNvrStatus(nvrStatus);
                    camera.setNvrStatusUpdatedAt(now);
                } else {
                    // Сохраняем существующий статус, если данных от API нет
                    log.debug("Preserving existing nvr_status={} for channel {} (no API data)", 
                            camera.getNvrStatus(), channelNumber);
                }
                // rtsp_status не трогаем - он обновляется отдельно
                updatedCount++;
                log.debug("Updating channel {}: {}, hasCamera={}, nvrStatus={}, hasCameraStates={}", 
                        channelNumber, channelName, hasCamera, nvrStatus, hasCameraStates);
            }

            cameraRepo.save(camera);
        }

        return updatedCount;
    }

    /**
     * Проверяет RTSP доступность для каналов устройства.
     * Выполняется асинхронно для всех каналов параллельно.
     * Обновляет только rtsp_status и rtsp_status_updated_at (не трогает nvr_status).
     * 
     * @param deviceId ID устройства
     */
    public void checkRtspHealthForDevice(Long deviceId) {
        // Защита от параллельных запусков для одного deviceId
        if (!rtspCheckInProgress.add(deviceId)) {
            log.debug("RTSP health check for device {} already in progress, skipping duplicate request", deviceId);
            return;
        }
        
        // Измеряем время работы метода с момента получения lock (до всех return внутри try)
        Instant startTime = Instant.now();
        // Переменные для измерения duration (нужны в finally)
        final long[] durationTotalTimeoutSeconds = {90}; // Значение по умолчанию
        final AtomicInteger durationTimeouts = new AtomicInteger(0); // Единый счётчик таймаутов для использования в finally
        
        try {
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
            
            // Проверяем только каналы с has_camera=true и непустым rtsp_url (реальные камеры)
            List<NvrCamera> channels = cameraRepo.findByDeviceId(deviceId).stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHasCamera()))
                    .filter(c -> c.getRtspUrl() != null && !c.getRtspUrl().trim().isEmpty())
                    .collect(Collectors.toList());
            
            if (channels.isEmpty()) {
                log.debug("No cameras (has_camera=true with RTSP URL) found for device {} to check RTSP", deviceId);
                return;
            }
            
            log.info("Starting RTSP health check for device {} ({} cameras)", deviceId, channels.size());
            
            OffsetDateTime now = OffsetDateTime.now();
            
            // Таймауты: 5 секунд на канал, 90 секунд общий таймаут на устройство
            // Увеличено для устройств с большим количеством камер (16 потоков × 5 сек = 80 сек максимум)
            final long PER_CHANNEL_TIMEOUT_SECONDS = 5;
            final long TOTAL_TIMEOUT_SECONDS = 90;
            durationTotalTimeoutSeconds[0] = TOTAL_TIMEOUT_SECONDS; // Для finally
            
            // Счётчики для агрегации ошибок
            final int[] failedCount = {0};
            
            // Создаём асинхронные задачи для проверки каждого канала с таймаутом
            List<CompletableFuture<Void>> futures = channels.stream()
                    .map(channel -> {
                        final NvrCamera channelFinal = channel; // final для использования в лямбде
                        return CompletableFuture.runAsync(() -> {
                            String rtspUrl = channelFinal.getRtspUrl();
                            try {
                                boolean isOnline = rtspHealthChecker.isOnline(rtspUrl);
                                // Обновляем только rtsp_status (не трогаем nvr_status)
                                channelFinal.setRtspStatus(isOnline ? "OK" : "FAIL");
                                channelFinal.setRtspStatusUpdatedAt(now);
                                // Legacy поля для обратной совместимости
                                channelFinal.setIsActive(isOnline);
                                channelFinal.setStatus(isOnline ? "ONLINE" : "OFFLINE");
                                channelFinal.setStatusUpdatedAt(now);
                                if (!isOnline) {
                                    synchronized (failedCount) {
                                        failedCount[0]++;
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("RTSP check failed for channel {} (device {}): {}", 
                                        channelFinal.getChannelNo(), deviceId, e.getMessage());
                                synchronized (failedCount) {
                                    failedCount[0]++;
                                }
                                channelFinal.setRtspStatus("FAIL");
                                channelFinal.setRtspStatusUpdatedAt(now);
                                channelFinal.setIsActive(false);
                                channelFinal.setStatus("OFFLINE");
                                channelFinal.setStatusUpdatedAt(now);
                            }
                        }, rtspCheckExecutor)
                        .orTimeout(PER_CHANNEL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            if (ex instanceof java.util.concurrent.TimeoutException) {
                                log.debug("RTSP check timeout for channel {} (device {})", 
                                        channelFinal.getChannelNo(), deviceId);
                                durationTimeouts.incrementAndGet(); // Атомарный инкремент
                            }
                            synchronized (failedCount) {
                                failedCount[0]++;
                            }
                            return null;
                        });
                    })
                    .collect(Collectors.toList());
            
            // Ждём завершения всех проверок с общим таймаутом
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(TOTAL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("RTSP health check timeout for device {} after {} seconds ({} channels checked)", 
                        deviceId, TOTAL_TIMEOUT_SECONDS, channels.size());
            } catch (Exception e) {
                log.error("RTSP health check error for device {}: {}", deviceId, e.getMessage(), e);
            }
            
            // Сохраняем обновлённые каналы
            cameraRepo.saveAll(channels);
            
            // Подсчитываем статусы RTSP
            long rtspOk = channels.stream().filter(c -> "OK".equals(c.getRtspStatus())).count();
            long rtspFail = channels.stream().filter(c -> "FAIL".equals(c.getRtspStatus())).count();
            long rtspNone = channels.stream().filter(c -> "NONE".equals(c.getRtspStatus()) || c.getRtspStatus() == null).count();
            
            log.info("RTSP health check completed for device {} ({} cameras): OK={}, FAIL={}, NONE={}, timeouts={}", 
                    deviceId, channels.size(), rtspOk, rtspFail, rtspNone, durationTimeouts.get());
            
            if (failedCount[0] > 0 || durationTimeouts.get() > 0) {
                log.warn("RTSP health check for device {}: {} failed (including {} timeouts) out of {} total", 
                        deviceId, failedCount[0], durationTimeouts.get(), channels.size());
            }
            
            if (durationTimeouts.get() > 0) {
                log.warn("RTSP health check for device {}: {} channels did not complete within {} seconds total timeout", 
                        deviceId, durationTimeouts.get(), TOTAL_TIMEOUT_SECONDS);
            }
        } finally {
            // Сначала освобождаем lock (как можно раньше)
            rtspCheckInProgress.remove(deviceId);
            
            // Затем измеряем duration работы метода (в finally, чтобы сработало даже при exceptions/early-return)
            long durationSeconds = java.time.Duration.between(startTime, Instant.now()).getSeconds();
            if (durationSeconds > durationTotalTimeoutSeconds[0] || durationTimeouts.get() > 0) {
                log.warn("RTSP health check for device {} took {} seconds (timeout={}s, timeouts={})", 
                        deviceId, durationSeconds, durationTotalTimeoutSeconds[0], durationTimeouts.get());
            }
        }
    }

    /**
     * Преобразует состояние подключения камеры в статус.
     * 
     * Возможные значения connectionState:
     * - Connected → ONLINE
     * - Unconnect, Disconnected → OFFLINE
     * - Connecting, UnInited, Hibernation → UNKNOWN
     * - Empty, Disable → UNKNOWN (но канал будет HIDDEN через has_camera=false)
     */
    private String mapConnectionStateToStatus(String connectionState) {
        if (connectionState == null) {
            return "UNKNOWN";
        }
        String stateLower = connectionState.toLowerCase();
        return switch (stateLower) {
            case "connected" -> "ONLINE";
            case "unconnect", "disconnected" -> "OFFLINE";
            case "connecting", "uninited", "hibernation" -> "UNKNOWN";
            case "empty", "disable" -> "UNKNOWN"; // Будет скрыт через has_camera=false
            default -> "UNKNOWN";
        };
    }

    /**
     * Определяет, есть ли реальная камера на канале на основе connectionState.
     * 
     * @param connectionState состояние подключения камеры из Dahua API
     * @return true если камера реальная, false если канал пустой/отключён
     */
    private boolean determineHasCameraFromState(String connectionState) {
        if (connectionState == null) {
            return false; // Если состояния нет, считаем канал пустым
        }
        String stateLower = connectionState.toLowerCase();
        // Empty и Disable означают, что канал не сконфигурирован или отключён
        return !stateLower.equals("empty") && !stateLower.equals("disable");
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

