package com.nvr.nvrservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nvr.nvrservice.service.dto.DahuaChannelDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Клиент для работы с Dahua HTTP API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DahuaApiClient {

    private final DahuaDigestHttpClient digestHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Паттерн для парсинга INI-строк вида: table.VideoInput[0].Name=...
    private static final Pattern INI_LINE_PATTERN = Pattern.compile(
            "table\\.VideoInput\\[(\\d+)\\]\\.(\\w+)=(.+)"
    );
    
    // Паттерн для парсинга ChannelTitle: table.ChannelTitle[0].Name=...
    private static final Pattern CHANNEL_TITLE_PATTERN = Pattern.compile(
            "table\\.ChannelTitle\\[(\\d+)\\]\\.Name=(.+)"
    );

    /**
     * Получает названия каналов из ChannelTitle.
     *
     * @param baseUrl  базовый URL устройства
     * @param username имя пользователя
     * @param password пароль
     * @return Map, где ключ - номер канала (1-16), значение - название канала
     */
    public Map<Integer, String> getChannelTitles(String baseUrl, String username, String password) {
        String endpoint = baseUrl + "/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle";

        try {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException e) {
                log.error("Invalid URI format for endpoint {}: {}", endpoint, e.getMessage());
                return Collections.emptyMap();
            }
            String response = digestHttpClient.executeDigestGet(uri, username, password);
            
            // Проверяем, что ответ не пустой и не HTML
            if (response == null || response.trim().isEmpty()) {
                log.debug("Empty response from ChannelTitle endpoint at {} (endpoint: {})", baseUrl, endpoint);
                return Collections.emptyMap();
            }
            
            String trimmedResponse = response.trim();
            if (trimmedResponse.startsWith("<!") || trimmedResponse.startsWith("<html") || 
                trimmedResponse.startsWith("<HTML")) {
                log.debug("Received HTML instead of INI from ChannelTitle at {} (endpoint: {}). " +
                        "Response preview: {}", baseUrl, endpoint, truncate(response, 200));
                return Collections.emptyMap();
            }
            
            log.debug("Raw response from ChannelTitle: {}", truncate(response, 500));
            return parseChannelTitlesFromIni(response);
        } catch (IOException e) {
            log.error("Failed to fetch channel titles from Dahua device at {} (endpoint: {}): {}",
                    baseUrl, endpoint, e.getMessage());
            return Collections.emptyMap();
        } catch (Exception e) {
            log.error("Unexpected error while fetching channel titles from Dahua device at {} (endpoint: {}): {}",
                    baseUrl, endpoint, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Получает список каналов с NVR устройства.
     *
     * @param baseUrl  базовый URL устройства (например, "http://81.23.151.25:8082")
     * @param username имя пользователя
     * @param password пароль
     * @return список каналов (пустой список, если произошла ошибка или ответ некорректный)
     */
    public List<DahuaChannelDto> getChannels(String baseUrl, String username, String password) {
        String endpoint = baseUrl + "/cgi-bin/devVideoInput.cgi?action=getCollect";

        try {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException e) {
                log.error("Invalid URI format for endpoint {}: {}", endpoint, e.getMessage());
                return Collections.emptyList();
            }
            String response = digestHttpClient.executeDigestGet(uri, username, password);
            
            // Проверяем, что ответ не пустой и не HTML
            if (response == null || response.trim().isEmpty()) {
                log.debug("Empty response from Dahua device at {} (endpoint: {})", baseUrl, endpoint);
                return Collections.emptyList();
            }
            
            // Проверяем на HTML (обычно начинается с <!DOCTYPE или <html)
            String trimmedResponse = response.trim();
            if (trimmedResponse.startsWith("<!") || trimmedResponse.startsWith("<html") || 
                trimmedResponse.startsWith("<HTML")) {
                log.debug("Received HTML instead of INI from Dahua device at {} (endpoint: {}). " +
                        "Response preview: {}", baseUrl, endpoint, truncate(response, 200));
                return Collections.emptyList();
            }
            
            log.debug("Raw response from devVideoInput.cgi: {}", truncate(response, 500));
            List<DahuaChannelDto> channels = parseChannelsFromIni(response);
            
            if (channels.isEmpty()) {
                log.debug("Parsed 0 channels from Dahua device at {} (endpoint: {}). " +
                        "Response preview: {}", baseUrl, endpoint, truncate(response, 300));
            }
            
            return channels;
        } catch (IOException e) {
            log.error("Failed to fetch channels from Dahua device at {} (endpoint: {}): {}",
                    baseUrl, endpoint, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Unexpected error while fetching channels from Dahua device at {} (endpoint: {}): {}",
                    baseUrl, endpoint, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Получает максимальное количество удалённых входных каналов (MaxRemoteInputChannels) из Dahua устройства.
     *
     * @param baseUrl  базовый URL устройства
     * @param username имя пользователя
     * @param password пароль
     * @return максимальное количество каналов (16, 32, 64 и т.д.), или null если не удалось получить
     */
    public Integer getMaxRemoteInputChannels(String baseUrl, String username, String password) {
        String endpoint = baseUrl + "/cgi-bin/magicBox.cgi?action=getProductDefinition&name=MaxRemoteInputChannels";
        
        try {
            URI uri = URI.create(endpoint);
            String response = digestHttpClient.executeDigestGet(uri, username, password);
            
            if (response == null || response.trim().isEmpty()) {
                log.debug("Empty response from MaxRemoteInputChannels at {}", baseUrl);
                return null;
            }
            
            // Формат ответа: table.MaxRemoteInputChannels=16 или table.MaxRemoteInputChannels=32|64...
            Pattern pattern = Pattern.compile("table\\.MaxRemoteInputChannels=(\\d+)");
            Matcher matcher = pattern.matcher(response);
            
            if (matcher.find()) {
                String value = matcher.group(1);
                // Если значение содержит |, берём первое число
                if (value.contains("|")) {
                    value = value.split("\\|")[0];
                }
                int maxChannels = Integer.parseInt(value);
                log.debug("Retrieved MaxRemoteInputChannels={} from device {}", maxChannels, baseUrl);
                return maxChannels;
            }
            
            log.debug("Could not parse MaxRemoteInputChannels from response: {}", truncate(response, 200));
            return null;
        } catch (Exception e) {
            log.debug("Failed to fetch MaxRemoteInputChannels from {}: {}", baseUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Получает состояние камер (ONLINE/OFFLINE).
     *
     * @param baseUrl  базовый URL устройства
     * @param username имя пользователя
     * @param password пароль
     * @return Map, где ключ - номер канала, значение - состояние (Connected/Unconnect и т.д.)
     *         Пустая Map, если произошла ошибка или ответ некорректный
     */
    public Map<Integer, String> getCameraStates(String baseUrl, String username, String password) {
        // Пробуем несколько вариантов endpoints
        String[] endpoints = {
            baseUrl + "/cgi-bin/api/LogicDeviceManager/getCameraStates", // Множественное число
            baseUrl + "/cgi-bin/api/LogicDeviceManager/getCameraState",  // Единственное число (текущий)
            baseUrl + "/cgi-bin/devVideoInput.cgi?action=getCameraState", // Альтернативный через devVideoInput
        };
        
        String jsonBody = "{\"uniqueChannels\":[-1]}";
        
        for (String endpoint : endpoints) {
            try {
                URI uri;
                try {
                    uri = URI.create(endpoint);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid URI format for endpoint {}: {}", endpoint, e.getMessage());
                    continue; // Пробуем следующий endpoint
                }
                
                log.debug("Trying to fetch camera states from endpoint: {}", endpoint);
                String response = digestHttpClient.executeDigestPostJson(uri, username, password, jsonBody);
                
                // Проверяем, что ответ не пустой и не HTML
                if (response == null || response.trim().isEmpty()) {
                    log.debug("Empty response from {} (endpoint: {}), trying next endpoint", baseUrl, endpoint);
                    continue;
                }
                
                String trimmedResponse = response.trim();
                if (trimmedResponse.startsWith("<!") || trimmedResponse.startsWith("<html") || 
                    trimmedResponse.startsWith("<HTML")) {
                    log.debug("Received HTML instead of JSON from {} (endpoint: {}). " +
                            "Response preview: {}, trying next endpoint", baseUrl, endpoint, truncate(response, 200));
                    continue;
                }
                
                log.debug("Raw response from getCameraState: {}", truncate(response, 500));
                Map<Integer, String> states = parseCameraStatesFromJson(response);
                if (!states.isEmpty()) {
                    log.debug("Successfully fetched camera states from endpoint: {} (parsed {} states)", 
                            endpoint, states.size());
                    return states;
                }
                log.debug("Parsed empty states from {} (endpoint: {}). Response preview: {}. Trying next endpoint", 
                        baseUrl, endpoint, truncate(response, 300));
            } catch (IOException e) {
                // Понижаем уровень логов для ожидаемых ошибок (400, 501) → DEBUG
                String errorMessage = e.getMessage();
                if (errorMessage != null && (errorMessage.contains("400") || errorMessage.contains("501"))) {
                    log.debug("HTTP {} from Dahua device at {} (endpoint: {}). Trying next endpoint.", 
                            errorMessage.contains("400") ? "400" : "501", baseUrl, endpoint);
                } else if (errorMessage != null && errorMessage.contains("403")) {
                    log.debug("HTTP 403 Forbidden from Dahua device at {} (endpoint: {}). Trying next endpoint.", 
                            baseUrl, endpoint);
                } else {
                    log.debug("Failed to fetch camera states from {} (endpoint: {}): {}. Trying next endpoint.",
                            baseUrl, endpoint, errorMessage);
                }
                continue; // Пробуем следующий endpoint
            } catch (Exception e) {
                log.debug("Unexpected error while fetching camera states from {} (endpoint: {}): {}. Trying next endpoint.",
                        baseUrl, endpoint, e.getMessage());
                continue; // Пробуем следующий endpoint
            }
        }
        
        // Если все endpoints не сработали - это деградация, но не ошибка
        log.debug("Failed to fetch camera states from Dahua device at {} using all tried endpoints. " +
                "Camera states will not be updated to preserve existing nvr_status values.", baseUrl);
        return Collections.emptyMap();
    }

    /**
     * Парсит INI-ответ от devVideoInput.cgi в список каналов.
     * Формат ответа:
     * table.VideoInput[0].Name=Channel 1
     * table.VideoInput[0].IP=192.168.1.100
     * table.VideoInput[0].DeviceName=Camera 1
     * table.VideoInput[0].ChannelName=Main Stream
     * table.VideoInput[0].Protocol=ONVIF
     * table.VideoInput[0].Type=IP
     * ...
     * 
     * Индекс в [ ] трактуется как channelNo (0-based, поэтому добавляем +1).
     */
    private List<DahuaChannelDto> parseChannelsFromIni(String iniContent) {
        // Map<channelNo, Map<property, value>>
        Map<Integer, Map<String, String>> channelsMap = new HashMap<>();

        String[] lines = iniContent.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            Matcher matcher = INI_LINE_PATTERN.matcher(line);
            if (matcher.matches()) {
                int channelIndex = Integer.parseInt(matcher.group(1));
                String property = matcher.group(2);
                String value = matcher.group(3);

                // Используем индекс как channelNo (0-based, поэтому +1)
                int channelNo = channelIndex + 1;
                channelsMap.computeIfAbsent(channelNo, k -> new HashMap<>()).put(property, value);
            }
        }

        List<DahuaChannelDto> channels = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> entry : channelsMap.entrySet()) {
            int channelNo = entry.getKey();
            Map<String, String> props = entry.getValue();

            // Если есть свойство ChannelNo, используем его (но обычно индекс уже правильный)
            int actualChannelNo = channelNo;
            if (props.containsKey("ChannelNo")) {
                try {
                    int channelNoFromProp = Integer.parseInt(props.get("ChannelNo"));
                    // Используем значение из свойства, если оно разумное (>= 1)
                    if (channelNoFromProp >= 1) {
                        actualChannelNo = channelNoFromProp;
                    }
                } catch (NumberFormatException e) {
                    log.debug("Invalid ChannelNo for channel index {}: {}", channelNo, props.get("ChannelNo"));
                }
            }

            DahuaChannelDto channel = new DahuaChannelDto(
                    actualChannelNo,
                    props.getOrDefault("IP", ""),
                    props.getOrDefault("DeviceName", ""),
                    props.getOrDefault("ChannelName", ""),
                    props.getOrDefault("Protocol", ""),
                    props.getOrDefault("Type", "")
            );

            channels.add(channel);
        }

        // Сортируем по номеру канала
        channels.sort(Comparator.comparingInt(DahuaChannelDto::channelNo));

        log.debug("Parsed {} channels from INI response", channels.size());
        return channels;
    }

    /**
     * Парсит JSON-ответ от getCameraState.
     * Формат ответа примерно такой:
     * {
     *   "states": [
     *     {"uniqueChannel": 0, "connectionState": "Connected"},
     *     {"uniqueChannel": 1, "connectionState": "Unconnect"},
     *     ...
     *   ]
     * }
     * 
     * ВАЖНО: Dahua часто возвращает uniqueChannel в 0-based формате (0..15),
     * а наша система использует 1-based (1..16). Метод автоматически определяет
     * формат и нормализует номера каналов к 1-based.
     */
    private Map<Integer, String> parseCameraStatesFromJson(String jsonContent) {
        Map<Integer, String> states = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode statesArray = root.get("states");

            List<Integer> rawChannels = new ArrayList<>();
            List<Map.Entry<Integer, String>> rawPairs = new ArrayList<>();

            if (statesArray != null && statesArray.isArray()) {
                for (JsonNode stateNode : statesArray) {
                    // Поддерживаем оба варианта: channel и uniqueChannel
                    JsonNode channelNode = stateNode.get("channel");
                    if (channelNode == null) {
                        channelNode = stateNode.get("uniqueChannel");
                    }
                    JsonNode connectionStateNode = stateNode.get("connectionState");

                    if (channelNode != null && connectionStateNode != null) {
                        int uc = channelNode.asInt();
                        String state = connectionStateNode.asText();
                        rawChannels.add(uc);
                        rawPairs.add(new java.util.AbstractMap.SimpleEntry<>(uc, state));
                    }
                }
            }

            // Определяем формат: если есть 0, значит 0-based и нужно добавить +1
            boolean zeroBased = rawChannels.contains(0);
            
            // Нормализуем номера каналов к 1-based формату
            for (var p : rawPairs) {
                int channelNo = zeroBased ? (p.getKey() + 1) : p.getKey();
                states.put(channelNo, p.getValue());
            }

            if (states.isEmpty()) {
                log.debug("Parsed 0 camera states from JSON. Raw response preview: {}", 
                        truncate(jsonContent, 500));
            } else {
                log.debug("Parsed camera states size={}, zeroBased={}, sampleKeys={}",
                        states.size(), zeroBased, 
                        states.keySet().stream().sorted().limit(10).toList());
            }
        } catch (Exception e) {
            log.error("Failed to parse camera states JSON: {}", e.getMessage(), e);
            // Fallback на regex парсинг, если JSON некорректный
            // Поддерживаем оба варианта: channel и uniqueChannel
            Pattern statePattern1 = Pattern.compile(
                    "\"(?:channel|uniqueChannel)\"\\s*:\\s*(\\d+).*?\"connectionState\"\\s*:\\s*\"([^\"]+)\""
            );
            Matcher matcher = statePattern1.matcher(jsonContent);
            List<Integer> rawChannels = new ArrayList<>();
            List<Map.Entry<Integer, String>> rawPairs = new ArrayList<>();
            
            while (matcher.find()) {
                int uc = Integer.parseInt(matcher.group(1));
                String state = matcher.group(2);
                rawChannels.add(uc);
                rawPairs.add(new java.util.AbstractMap.SimpleEntry<>(uc, state));
            }
            
            // Применяем ту же нормализацию для fallback парсинга
            boolean zeroBased = rawChannels.contains(0);
            for (var p : rawPairs) {
                int channelNo = zeroBased ? (p.getKey() + 1) : p.getKey();
                states.put(channelNo, p.getValue());
            }
            
            if (states.isEmpty()) {
                log.debug("Parsed 0 camera states via regex fallback. Raw response preview: {}", 
                        truncate(jsonContent, 500));
            } else {
                log.debug("Parsed camera states via regex fallback: size={}, zeroBased={}, sampleKeys={}",
                        states.size(), zeroBased,
                        states.keySet().stream().sorted().limit(10).toList());
            }
        }

        return states;
    }

    /**
     * Парсит INI-ответ от ChannelTitle в Map номер канала -> название.
     * Формат ответа:
     * table.ChannelTitle[0].Name=IPC
     * table.ChannelTitle[1].Name=IPC
     * table.ChannelTitle[5].Name=4 подъезд
     * ...
     * Индекс + 1 = номер канала (1..16)
     */
    private Map<Integer, String> parseChannelTitlesFromIni(String iniContent) {
        Map<Integer, String> titles = new HashMap<>();

        String[] lines = iniContent.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            Matcher matcher = CHANNEL_TITLE_PATTERN.matcher(line);
            if (matcher.matches()) {
                int channelIndex = Integer.parseInt(matcher.group(1));
                String channelName = matcher.group(2);
                
                // Индекс + 1 = номер канала (может быть любое количество)
                int channelNumber = channelIndex + 1;
                titles.put(channelNumber, channelName);
            }
        }

        log.debug("Parsed {} channel titles from INI response", titles.size());
        return titles;
    }

    /**
     * Обрезает строку для логирования.
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}


