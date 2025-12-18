package com.nvr.nvrservice.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-клиент для работы с Dahua API через Digest-аутентификацию (RFC 7616).
 */
@Slf4j
@Component
public class DahuaDigestHttpClient {

    private static final Pattern DIGEST_PARAM_PATTERN = Pattern.compile(
            "([a-zA-Z]+)=\"([^\"]+)\"|([a-zA-Z]+)=([^,\\s]+)"
    );

    private final CloseableHttpClient httpClient;

    public DahuaDigestHttpClient() {
        this.httpClient = HttpClients.createDefault();
    }

    /**
     * Выполняет GET-запрос с Digest-аутентификацией.
     *
     * @param uri      полный URI запроса
     * @param username имя пользователя
     * @param password пароль
     * @return тело ответа в виде строки
     * @throws IOException если произошла ошибка при выполнении запроса
     */
    public String executeDigestGet(URI uri, String username, String password) throws IOException {
        return executeDigestRequest(new HttpGet(uri), username, password, null);
    }

    /**
     * Выполняет POST-запрос с JSON телом и Digest-аутентификацией.
     *
     * @param uri      полный URI запроса
     * @param username имя пользователя
     * @param password пароль
     * @param jsonBody JSON тело запроса (может быть null)
     * @return тело ответа в виде строки
     * @throws IOException если произошла ошибка при выполнении запроса
     */
    public String executeDigestPostJson(URI uri, String username, String password, String jsonBody) throws IOException {
        HttpPost post = new HttpPost(uri);
        post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        if (jsonBody != null && !jsonBody.isEmpty()) {
            post.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        }
        return executeDigestRequest(post, username, password, null);
    }

    /**
     * Основной метод для выполнения запроса с Digest-аутентификацией.
     */
    private String executeDigestRequest(
            ClassicHttpRequest request,
            String username,
            String password,
            String previousNonce
    ) throws IOException {
        URI requestUri;
        try {
            requestUri = request.getUri();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI in request", e);
        }

        try (CloseableHttpResponse response = httpClient.execute((HttpUriRequestBase) request)) {
            int statusCode = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

            // Если успешный ответ (2xx) - возвращаем тело
            if (statusCode >= 200 && statusCode < 300) {
                return body;
            }

            // Если 401 - пытаемся выполнить Digest-аутентификацию
            if (statusCode == 401) {
                Header authHeader = response.getFirstHeader(HttpHeaders.WWW_AUTHENTICATE);
                if (authHeader != null && authHeader.getValue().startsWith("Digest")) {
                    String authHeaderValue = authHeader.getValue();

                    Map<String, String> digestParams = parseDigestHeader(authHeaderValue);
                    String realm = digestParams.get("realm");
                    String nonce = digestParams.get("nonce");
                    String qop = digestParams.get("qop");
                    String opaque = digestParams.get("opaque");

                    if (realm == null || nonce == null) {
                        throw new IOException("Invalid WWW-Authenticate header: missing realm or nonce");
                    }

                    // Генерируем nc и cnonce
                    String nc = "00000001"; // для первого запроса всегда 00000001
                    String cnonce = generateCnonce();

                    // Формируем URI для Digest (без query параметров для вычисления HA2)
                    URI uriForDigest = requestUri;
                    String requestUriString = uriForDigest.getPath();
                    if (uriForDigest.getQuery() != null) {
                        requestUriString += "?" + uriForDigest.getQuery();
                    }

                    // Вычисляем response по RFC 7616
                    String responseHash = calculateDigestResponse(
                            username, password, realm, nonce, nc, cnonce, qop,
                            request.getMethod(), requestUriString
                    );

                    // Формируем заголовок Authorization
                    StringBuilder authHeaderBuilder = new StringBuilder("Digest ");
                    authHeaderBuilder.append("username=\"").append(username).append("\", ");
                    authHeaderBuilder.append("realm=\"").append(realm).append("\", ");
                    authHeaderBuilder.append("nonce=\"").append(nonce).append("\", ");
                    authHeaderBuilder.append("uri=\"").append(requestUriString).append("\", ");
                    authHeaderBuilder.append("response=\"").append(responseHash).append("\"");

                    if (qop != null) {
                        authHeaderBuilder.append(", qop=").append(qop);
                        authHeaderBuilder.append(", nc=").append(nc);
                        authHeaderBuilder.append(", cnonce=\"").append(cnonce).append("\"");
                    }

                    if (opaque != null) {
                        authHeaderBuilder.append(", opaque=\"").append(opaque).append("\"");
                    }

                    String authorizationHeader = authHeaderBuilder.toString();

                    // Повторяем запрос с заголовком Authorization
                    ClassicHttpRequest authenticatedRequest = createAuthenticatedRequest(
                            request, authorizationHeader
                    );

                    try (CloseableHttpResponse authResponse = httpClient.execute(
                            (HttpUriRequestBase) authenticatedRequest)) {
                        int authStatusCode = authResponse.getCode();
                        String authBody = new String(
                                authResponse.getEntity().getContent().readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                        if (authStatusCode >= 200 && authStatusCode < 300) {
                            return authBody;
                        } else if (authStatusCode == 403) {
                            throw new IOException("Digest authentication failed: 403 Forbidden. Response body: " + truncate(authBody, 500));
                        } else if (authStatusCode == 400 || authStatusCode == 501) {
                            // HTTP 400/501 - ожидаемые ошибки для некоторых endpoints, логируем как DEBUG
                            log.debug("HTTP {} from Dahua API at {}: {}", authStatusCode, requestUri, truncate(authBody, 200));
                            throw new IOException(String.format("HTTP %d: %s", authStatusCode, truncate(authBody, 200)));
                        } else {
                            // Неожиданные ошибки логируем как ERROR
                            String errorDetails = String.format(
                                    "Unexpected status code after authentication: %d. Response body: %s. URL: %s",
                                    authStatusCode, truncate(authBody, 500), requestUri
                            );
                            log.error("Dahua API error: {}", errorDetails);
                            throw new IOException(errorDetails);
                        }
                    }
                } else {
                    throw new IOException("401 Unauthorized without Digest challenge");
                }
            } else {
                throw new IOException("Unexpected status code: " + statusCode + ", body: " + truncate(body, 200));
            }
        }
    }

    /**
     * Парсит заголовок WWW-Authenticate и извлекает параметры Digest.
     */
    private Map<String, String> parseDigestHeader(String headerValue) {
        Map<String, String> params = new HashMap<>();
        // Убираем префикс "Digest "
        String digestPart = headerValue.substring(headerValue.indexOf(" ") + 1);
        Matcher matcher = DIGEST_PARAM_PATTERN.matcher(digestPart);

        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            if (key != null && value != null) {
                params.put(key.toLowerCase(), value);
            }
        }

        return params;
    }

    /**
     * Генерирует случайный cnonce (client nonce).
     */
    private String generateCnonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Вычисляет response для Digest-аутентификации по RFC 7616.
     */
    private String calculateDigestResponse(
            String username,
            String password,
            String realm,
            String nonce,
            String nc,
            String cnonce,
            String qop,
            String method,
            String uri
    ) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            // HA1 = MD5(username:realm:password)
            String ha1Input = username + ":" + realm + ":" + password;
            byte[] ha1Bytes = md5.digest(ha1Input.getBytes(StandardCharsets.UTF_8));
            String ha1 = bytesToHex(ha1Bytes);

            // HA2 = MD5(method:uri)
            String ha2Input = method + ":" + uri;
            byte[] ha2Bytes = md5.digest(ha2Input.getBytes(StandardCharsets.UTF_8));
            String ha2 = bytesToHex(ha2Bytes);

            // response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            String responseInput;
            if (qop != null && qop.equals("auth")) {
                responseInput = ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2;
            } else {
                responseInput = ha1 + ":" + nonce + ":" + ha2;
            }

            byte[] responseBytes = md5.digest(responseInput.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(responseBytes);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * Преобразует массив байтов в hex-строку.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Создаёт копию запроса с добавленным заголовком Authorization.
     */
    private ClassicHttpRequest createAuthenticatedRequest(
            ClassicHttpRequest original,
            String authorizationHeader
    ) throws IOException {
        ClassicHttpRequest authenticated;
        URI originalUri;
        try {
            originalUri = original.getUri();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URI in original request", e);
        }
        
        if (original instanceof HttpGet) {
            authenticated = new HttpGet(originalUri);
        } else if (original instanceof HttpPost) {
            authenticated = new HttpPost(originalUri);
            HttpPost originalPost = (HttpPost) original;
            if (originalPost.getEntity() != null) {
                ((HttpPost) authenticated).setEntity(originalPost.getEntity());
            }
            // Копируем Content-Type, если был установлен
            Header contentType = originalPost.getFirstHeader(HttpHeaders.CONTENT_TYPE);
            if (contentType != null) {
                authenticated.setHeader(contentType);
            }
        } else {
            throw new IllegalArgumentException("Unsupported request type: " + original.getClass());
        }

        authenticated.setHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        return authenticated;
    }

    /**
     * Обрезает строку до указанной длины для логирования.
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

