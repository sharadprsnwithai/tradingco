package com.tradingbot.adapter.kite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class KiteAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthenticator.class);

    private final KiteConfig config;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final GoogleAuthenticator gAuth;

    /**
     * Constructs a new KiteAuthenticator with the provided configuration and JSON object mapper.
     *
     * @param config       the Kite broker configuration containing API credentials and settings
     * @param objectMapper the Jackson ObjectMapper for parsing JSON responses
     */
    public KiteAuthenticator(KiteConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.gAuth = new GoogleAuthenticator(new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder().build());
    }

    /**
     * Performs headless authentication with Zerodha Kite.
     * If the adapter is disabled, returns a mock access token.
     * Otherwise, executes the full headless login flow including 2FA.
     *
     * @return a reactive Mono containing the access token after successful authentication
     */
    public Mono<String> authenticate() {
        if (!config.isEnabled()) {
            log.info("Kite adapter is disabled; using mock access token");
            String mockToken = "mock_kite_access_token_" + config.getUserId();
            accessToken.set(mockToken);
            return Mono.just(mockToken);
        }

        return Mono.fromCallable(this::executeHeadlessLogin)
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess(token -> log.info("Kite authentication successfully completed for user: {}", config.getUserId()))
            .doOnError(ex -> log.error("Kite headless authentication failed: {}", ex.getMessage()));
    }

    /**
     * Returns the current access token if available, otherwise initiates authentication.
     *
     * @return a reactive Mono containing the access token
     */
    public Mono<String> getAccessToken() {
        String token = accessToken.get();
        if (token != null) {
            return Mono.just(token);
        }
        return authenticate();
    }

    /**
     * Checks if a valid session exists by verifying if an access token is present.
     *
     * @return true if an access token is available, false otherwise
     */
    public boolean hasValidSession() {
        return accessToken.get() != null;
    }

    /**
     * Manually sets the access token for the session.
     *
     * @param token the access token to store
     */
    public void setAccessToken(String token) {
        this.accessToken.set(token);
    }

    /**
     * Executes the complete headless Zerodha login flow.
     * Performs 4 steps: login with credentials, 2FA verification with TOTP,
     * request token capture from connect redirect, and token exchange.
     *
     * @return the access token obtained from successful login
     * @throws Exception if any step of the login flow fails
     */
    private String executeHeadlessLogin() throws Exception {
        log.info("Starting headless Zerodha login flow for user: {}", config.getUserId());
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);

        // Step 1: Login with User ID & Password
        String loginFormData = "user_id=" + URLEncoder.encode(config.getUserId(), StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(config.getPassword(), StandardCharsets.UTF_8);

        String loginResponse = postForm("https://kite.zerodha.com/api/login", loginFormData);
        JsonNode loginJson = objectMapper.readTree(loginResponse);
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 1 login failed: " + loginJson.path("message").asText());
        }

        String requestId = loginJson.path("data").path("request_id").asText();
        log.info("Kite Step 1 succeeded. Request ID acquired.");

        // Step 2: 2FA submission with TOTP
        String totp = generateTotp();
        String twoFaFormData = "user_id=" + URLEncoder.encode(config.getUserId(), StandardCharsets.UTF_8)
            + "&request_id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
            + "&twofa_value=" + URLEncoder.encode(totp, StandardCharsets.UTF_8)
            + "&twofa_type=totp&skip_session=";

        String twoFaResponse = postForm("https://kite.zerodha.com/api/twofa", twoFaFormData);
        JsonNode twoFaJson = objectMapper.readTree(twoFaResponse);
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 2 2FA failed: " + twoFaJson.path("message").asText());
        }
        log.info("Kite Step 2 TOTP 2FA verified successfully.");

        // Step 3: Connect Login Redirect to capture request_token
        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + config.getApiKey();
        String requestToken = extractRequestTokenFromConnect(connectUrl);
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalStateException("Failed to capture request_token from Kite connect redirect");
        }
        log.info("Kite Step 3 request_token captured successfully.");

        // Step 4: Exchange request_token for access_token
        String checksum = DigestUtils.sha256Hex(config.getApiKey() + requestToken + config.getApiSecret());
        String tokenFormData = "api_key=" + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);

        String tokenResponse = postForm(config.getBaseUrl() + "/session/token", tokenFormData);
        JsonNode tokenJson = objectMapper.readTree(tokenResponse);
        if (!"success".equalsIgnoreCase(tokenJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 4 session token exchange failed: " + tokenJson.path("message").asText());
        }

        String token = tokenJson.path("data").path("access_token").asText();
        accessToken.set(token);
        return token;
    }

    /**
     * Extracts the request token by following redirects from the Kite connect login URL.
     *
     * @param targetUrl the initial Kite connect login URL to start redirect following
     * @return the extracted request token, or null if not found
     * @throws Exception if there's an error during HTTP connection
     */
    private String extractRequestTokenFromConnect(String targetUrl) throws Exception {
        String currentUrl = targetUrl;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URI(currentUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();

            int status = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            conn.disconnect();

            if (location != null && !location.isBlank()) {
                if (location.contains("request_token=")) {
                    return parseQueryParam(location, "request_token");
                }
                currentUrl = location;
            } else {
                break;
            }
        }
        return null;
    }

    /**
     * Parses a specific query parameter from a URL string.
     *
     * @param url   the URL containing query parameters
     * @param param the name of the parameter to extract
     * @return the decoded parameter value, or null if not found or on error
     */
    private String parseQueryParam(String url, String param) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && parts[0].equals(param)) {
                        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing query param from url {}: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * Sends an HTTP POST request with form-encoded data.
     *
     * @param urlStr   the URL to send the POST request to
     * @param formData the form-encoded data to send in the request body
     * @return the response body as a string
     * @throws Exception if there's an error during the HTTP request
     */
    private String postForm(String urlStr, String formData) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        byte[] bytes = formData.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Generates a Time-based One-Time Password (TOTP) using the configured secret.
     *
     * @return a 6-digit TOTP code, or "000000" if generation fails
     */
    private String generateTotp() {
        try {
            int code = gAuth.getTotpPassword(config.getTotpSecret());
            return String.format("%06d", code);
        } catch (Exception e) {
            log.warn("Could not compute TOTP from secret, fallback to 000000: {}", e.getMessage());
            return "000000";
        }
    }
}
