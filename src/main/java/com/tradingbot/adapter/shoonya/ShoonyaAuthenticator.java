package com.tradingbot.adapter.shoonya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements 100% Automated Headless Shoonya (Finvasia NorenAPI) OAuth Authentication & Session Manager.
 * Execution:
 * 1. Computes in-memory TOTP from SHOONYA_TOTP_SECRET.
 * 2. Executes headless QuickAuth with app_key and secure key derivation.
 * 3. Captures authorization code returned by Shoonya.
 * 4. Exchanges code via /GenAcsTok with SHA-256(Client_id + secret_key + code).
 * 5. Persists active session into data/shoonya_session.json (12h cache).
 */
@Component
public class ShoonyaAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaAuthenticator.class);
    private static final File SESSION_FILE = new File("data/shoonya_session.json");
    private static final int[] KEY_OFFSETS = {83, 50, 97, 114, 110, 46, 27, 93};

    private final ShoonyaConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GoogleAuthenticator gAuth;
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<String> sUserToken = new AtomicReference<>();

    // Single-flight guard: concurrent getAccessToken() callers (e.g. many REST calls
    // failing with 401/403 at once) share ONE headless login. Without this, parallel
    // logins invalidate each other's session at Shoonya, producing an INVALID_SESSION
    // death-spiral. Mirrors the KiteAuthenticator pattern.
    private final Object authLock = new Object();
    private volatile Mono<String> inFlightAuth;

    /**
     * Constructs a ShoonyaAuthenticator with the specified configuration, web client builder, and object mapper.
     *
     * @param config           the Shoonya configuration containing credentials and settings
     * @param webClientBuilder the Spring WebClient builder for HTTP communication
     * @param objectMapper     the Jackson ObjectMapper for JSON serialization/deserialization
     */
    public ShoonyaAuthenticator(ShoonyaConfig config, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl("https://api.shoonya.com").build();
        this.gAuth = new GoogleAuthenticator(new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build());
    }

    /**
     * Authenticates with the Shoonya API using the headless OAuth flow.
     * Returns immediately if already authenticated or if adapter is disabled.
     * Falls back to disk-cached session if available and valid.
     *
     * @return a {@link Mono} emitting the access token string
     */
    public Mono<String> authenticate() {
        if (!config.isEnabled()) {
            log.info("Shoonya adapter is disabled; using mock session token");
            String mockToken = "mock_shoonya_access_token_" + config.getUserId();
            accessToken.set(mockToken);
            return Mono.just(mockToken);
        }

        // 1. Check if token is explicitly configured in .env / config
        if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
            log.info("Using configured SHOONYA_ACCESS_TOKEN for user: {}", config.getUserId());
            accessToken.set(config.getAccessToken());
            sUserToken.set(config.getAccessToken());
            return Mono.just(config.getAccessToken());
        }

        // 2. Check existing session file on disk (valid for 12 hours)
        String diskToken = loadFreshDiskSession();
        if (diskToken != null) {
            log.info("Loaded fresh Shoonya session from data/shoonya_session.json");
            accessToken.set(diskToken);
            return Mono.just(diskToken);
        }

        // 3. Execute 100% Automated Headless Login Flow
        return Mono.fromCallable(this::executeHeadlessLogin)
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess(token -> log.info("Shoonya automated authentication completed successfully for user: {}", config.getUserId()))
            .doOnError(ex -> log.error("Shoonya automated authentication failed: {}", ex.getMessage()));
    }

    /**
     * Executes the complete headless login flow by performing QuickAuth and GenAcsTok API calls.
     *
     * @return the access token obtained from the authentication flow
     * @throws Exception if any step of the authentication process fails
     */
    private String executeHeadlessLogin() throws Exception {
        log.info("Starting 100% automated headless Shoonya login for user: {}", config.getUserId());

        // Step 1: Compute derived appkey
        StringBuilder keyBuilder = new StringBuilder(config.getUserId()).append("|");
        for (int p = 0; p < KEY_OFFSETS.length; p++) {
            keyBuilder.append((char) (KEY_OFFSETS[p] + p));
        }
        String appkey = DigestUtils.sha256Hex(keyBuilder.toString());
        String pwdSha = DigestUtils.sha256Hex(config.getPassword());
        String totp = generateTotp();

        String vc = (config.getVendorCode() != null && !config.getVendorCode().isBlank() && !"mock_vendor".equals(config.getVendorCode()))
            ? config.getVendorCode()
            : "NOREN_API";

        Map<String, Object> quickAuthPayload = new HashMap<>();
        quickAuthPayload.put("apkversion", "W2_20250926");
        quickAuthPayload.put("uid", config.getUserId());
        quickAuthPayload.put("pwd", pwdSha);
        quickAuthPayload.put("factor2", totp);
        quickAuthPayload.put("appkey", appkey);
        quickAuthPayload.put("imei", "12345678-1234-1234-1234-123456789abc");
        quickAuthPayload.put("addldivinf", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        quickAuthPayload.put("source", "API");
        quickAuthPayload.put("vc", vc);
        quickAuthPayload.put("app_key", config.getClientId());

        String quickAuthBody = "jData=" + objectMapper.writeValueAsString(quickAuthPayload);

        String quickAuthResponse;
        try {
            quickAuthResponse = webClient.post()
                .uri("/NorenWClientAPI/QuickAuth")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://api.shoonya.com")
                .header("Referer", "https://api.shoonya.com/OAuthlogin/authorize/oauth?client_id=" + config.getClientId())
                .body(BodyInserters.fromValue(quickAuthBody))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (WebClientResponseException e) {
            log.error("Shoonya QuickAuth HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Shoonya QuickAuth HTTP error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        }

    log.info("Shoonya QuickAuth response: {}", quickAuthResponse);
    JsonNode quickAuthJson = objectMapper.readTree(quickAuthResponse);
    if (!"Ok".equalsIgnoreCase(quickAuthJson.path("stat").asText())) {
        throw new IllegalStateException("Shoonya Step 1 QuickAuth failed: " + quickAuthJson.path("emsg").asText());
    }

    String authCode = quickAuthJson.path("code").asText(null);
    if (authCode == null || authCode.isBlank()) {
        throw new IllegalStateException("Shoonya QuickAuth did not return an authorization code needed for GenAcsTok");
    }
    log.info("Shoonya Step 1 QuickAuth succeeded, auth code acquired");

    // Step 2: GenAcsTok exchanges the OAuth code for the real NorenWClientAPI session token.
    // Per Shoonya's current auth model the QuickAuth susertoken is deprecated and is NOT a valid
    // session for NorenWClientAPI REST/WS - the activated session token comes from GenAcsTok
    // (its "susertoken" field, which equals "access_token"). That token is used as the REST
    // body jKey and as the WebSocket handshake token. No Authorization header is sent.
    String checksum = DigestUtils.sha256Hex(config.getClientId() + config.getSecretKey() + authCode);
    Map<String, String> genAcsPayload = Map.of("code", authCode, "checksum", checksum);
    String genAcsBody = "jData=" + objectMapper.writeValueAsString(genAcsPayload);
    String genAcsResponse = webClient.post()
        .uri("/NorenWClientAPI/GenAcsTok")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromValue(genAcsBody))
        .retrieve()
        .bodyToMono(String.class)
        .block();
    log.info("Shoonya GenAcsTok response: {}", genAcsResponse);
    JsonNode genAcsJson = objectMapper.readTree(genAcsResponse);
    if (!"Ok".equalsIgnoreCase(genAcsJson.path("stat").asText())) {
        throw new IllegalStateException("Shoonya Step 2 GenAcsTok failed: " + genAcsJson.path("emsg").asText());
    }
    String sessionToken = genAcsJson.path("susertoken").asText(genAcsJson.path("access_token").asText());
    if (sessionToken == null || sessionToken.isBlank()) {
        throw new IllegalStateException("Shoonya GenAcsTok did not return a session token");
    }
    log.info("Shoonya Step 2 GenAcsTok succeeded; using GenAcsTok session token for NorenWClientAPI (REST jKey + WS)");
    accessToken.set(sessionToken);
    sUserToken.set(sessionToken);
    saveDiskSession(sessionToken, sessionToken);
    return sessionToken;
}

    /**
     * Gets the current access token, authenticating if necessary.
     *
     * @return a {@link Mono} emitting the access token string
     */
    public Mono<String> getAccessToken() {
        String token = accessToken.get();
        if (token != null) {
            return Mono.just(token);
        }
        synchronized (authLock) {
            token = accessToken.get();
            if (token != null) {
                return Mono.just(token);
            }
            if (inFlightAuth == null) {
                inFlightAuth = authenticate()
                    .doFinally(sig -> {
                        synchronized (authLock) {
                            inFlightAuth = null;
                        }
                    })
                    .cache();
            }
            return inFlightAuth;
        }
    }

    /**
     * Gets the current SUser token.
     *
     * @return the SUser token string, or null if not authenticated
     */
    public String getSUserToken() {
        return sUserToken.get();
    }

    /**
     * Checks if there is a valid session with a non-null access token.
     *
     * @return true if an access token is present, false otherwise
     */
    public boolean hasValidSession() {
        return accessToken.get() != null;
    }

    /**
     * Sets the access token directly.
     *
     * @param token the access token to set
     */
    public void setAccessToken(String token) {
        this.accessToken.set(token);
        this.sUserToken.set(token);
    }

    /**
     * Invalidates the current session, forcing the next {@link #getAccessToken()}
     * call to re-authenticate. Used when Shoonya rejects the session token
     * (HTTP 401/403), which happens when the overnight session has been killed
     * or the cached/disk token has expired.
     *
     * <p>Both the in-memory token and the on-disk session cache are cleared so a
     * stale disk session (still within its 12h window but dead at Shoonya) is not
     * reloaded on the next auth attempt.</p>
     */
    public void invalidateToken() {
        accessToken.set(null);
        sUserToken.set(null);
        try {
            if (SESSION_FILE.exists() && !SESSION_FILE.delete()) {
                log.warn("Could not delete stale Shoonya session file: {}", SESSION_FILE.getAbsolutePath());
            } else {
                log.info("Invalidated Shoonya session (in-memory + disk cache cleared)");
            }
        } catch (Exception e) {
            log.warn("Failed to delete Shoonya session file on invalidate: {}", e.getMessage());
        }
    }

    /**
     * Loads a fresh session from the disk cache if it exists and is less than 12 hours old.
     *
     * @return the cached access token if valid, or null if expired or not found
     */
    private String loadFreshDiskSession() {
        if (SESSION_FILE.exists()) {
            try {
                JsonNode root = objectMapper.readTree(SESSION_FILE);
                String token = root.path("accessToken").asText(null);
                String userToken = root.path("susertoken").asText(token);
                String createdAtStr = root.path("createdAt").asText(null);
                if (token != null && createdAtStr != null) {
                    Instant createdAt = Instant.parse(createdAtStr);
                    java.time.ZoneId istZone = java.time.ZoneId.of("Asia/Kolkata");
                    java.time.LocalDate tokenDate = createdAt.atZone(istZone).toLocalDate();
                    java.time.LocalDate today = java.time.LocalDate.now(istZone);
                    if (tokenDate.equals(today) && createdAt.plus(12, ChronoUnit.HOURS).isAfter(Instant.now())) {
                        sUserToken.set(userToken != null ? userToken : token);
                        return token;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read Shoonya session file: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Saves the current session to disk for caching purposes.
     *
     * @param token     the access token to save
     * @param userToken the SUser token to save
     */
    private void saveDiskSession(String token, String userToken) {
        try {
            SESSION_FILE.getParentFile().mkdirs();
            Map<String, Object> data = new HashMap<>();
            data.put("accessToken", token);
            data.put("susertoken", userToken);
            data.put("uid", config.getUserId());
            data.put("actid", config.getAccountId());
            data.put("createdAt", Instant.now().toString());
            objectMapper.writeValue(SESSION_FILE, data);
        } catch (Exception e) {
            log.warn("Failed to write Shoonya session file: {}", e.getMessage());
        }
    }

    /**
     * Generates a TOTP code using the configured secret, or returns the raw code if directly provided.
     * If no secret or code is configured, returns null (allowing login without factor2).
     *
     * @return a 6-digit TOTP code as a string, or null if not configured
     */
    private String generateTotp() {
        if (config.getTotpSecret() == null || config.getTotpSecret().isBlank()) {
            return null;
        }
        String secret = config.getTotpSecret().trim().replace(" ", "");
        // If the user provided a direct 6-digit numeric TOTP code, use it directly
        if (secret.matches("^\\d{6}$")) {
            return secret;
        }
        try {
            int code = gAuth.getTotpPassword(secret);
            return String.format("%06d", code);
        } catch (Exception e) {
            log.warn("Could not compute TOTP from secret, using raw value: {}", e.getMessage());
            return secret;
        }
    }
}
