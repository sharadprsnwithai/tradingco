package com.tradingbot.adapter.kite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KiteAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthenticator.class);
    private static final Pattern REQUEST_TOKEN_PATTERN = Pattern.compile("request_token[\"'\\s:=]+([a-zA-Z0-9_-]+)");

    private final KiteConfig config;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final GoogleAuthenticator gAuth;

    /**
     * Optional listener invoked whenever a brand-new access token is minted
     * (headless login, manual token, or mock). The Kite WebSocket ticker caches
     * the token it was constructed with; callers (KiteBrokerAdapter) use this
     * hook to re-key the live ticker with the fresh token so auto-reconnects
     * after daily expiry stop returning 403 Forbidden.
     */
    private volatile Consumer<String> tokenRenewedListener;

    // Single-flight guard: concurrent getAccessToken() callers share ONE headless login.
    // Without this, parallel headless logins invalidate each other's request_id at Zerodha.
    private final Object authLock = new Object();
    private volatile Mono<String> inFlightAuth;

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
     * Registers a listener that fires with the new token whenever (re)authentication
     * produces a fresh access token. Replaces any previously registered listener.
     *
     * @param listener consumer receiving the newly minted access token
     */
    public void setTokenRenewedListener(Consumer<String> listener) {
        this.tokenRenewedListener = listener;
    }

    private void notifyTokenRenewed(String token) {
        Consumer<String> listener = this.tokenRenewedListener;
        if (listener != null && token != null) {
            try {
                listener.accept(token);
            } catch (Exception ex) {
                log.warn("Kite tokenRenewedListener threw: {}", ex.getMessage());
            }
        }
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
            notifyTokenRenewed(mockToken);
            return Mono.just(mockToken);
        }

        // Manual token mode (KITE_ACCESS_TOKEN): operator logs in via Kite's official
        // flow each morning and provides the token. Skips headless login entirely.
        String manualToken = config.getAccessToken();
        if (manualToken != null && !manualToken.isBlank()) {
            log.info("Using manually provided KITE_ACCESS_TOKEN for user: {}", config.getUserId());
            accessToken.set(manualToken.trim());
            notifyTokenRenewed(manualToken.trim());
            return Mono.just(manualToken.trim());
        }

        return Mono.fromCallable(this::executeHeadlessLogin)
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess(token -> log.info("Kite authentication successfully completed for user: {}", config.getUserId()))
            .doOnError(ex -> log.error("Kite headless authentication failed: {}", ex.getMessage()));
    }

    /**
     * Returns the current access token if available, otherwise initiates authentication.
     * Concurrent callers share a single in-flight login (single-flight pattern).
     *
     * @return a reactive Mono containing the access token
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
     * Invalidates the cached access token. The next call to {@link #getAccessToken()}
     * will trigger a fresh headless authentication. Used when Kite rejects the token
     * (HTTP 401/403), e.g. after daily token expiry.
     */
    public void invalidateToken() {
        log.warn("Kite access token invalidated - next API call will re-authenticate");
        this.accessToken.set(null);
    }

    /**
     * Executes the complete headless Zerodha login flow using Java HttpClient
     * which properly handles cookie sessions across requests.
     * Performs 4 steps: login with credentials, 2FA verification with TOTP,
     * request token capture from connect redirect, and token exchange.
     *
     * @return the access token obtained from successful login
     * @throws Exception if any step of the login flow fails
     */
    private String executeHeadlessLogin() throws Exception {
        log.info("Starting headless Zerodha login flow for user: {}", config.getUserId());

        // Shared cookie manager across all steps — HttpClient uses this automatically
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient httpClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // Step 1: Login with User ID & Password
        String loginFormData = "user_id=" + URLEncoder.encode(config.getUserId(), StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(config.getPassword(), StandardCharsets.UTF_8);

        HttpRequest loginReq = HttpRequest.newBuilder()
            .uri(URI.create("https://kite.zerodha.com/api/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .POST(HttpRequest.BodyPublishers.ofString(loginFormData))
            .build();

        HttpResponse<String> loginResp = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString());
        JsonNode loginJson = objectMapper.readTree(loginResp.body());
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

        HttpRequest twoFaReq = HttpRequest.newBuilder()
            .uri(URI.create("https://kite.zerodha.com/api/twofa"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .POST(HttpRequest.BodyPublishers.ofString(twoFaFormData))
            .build();

        HttpResponse<String> twoFaResp = httpClient.send(twoFaReq, HttpResponse.BodyHandlers.ofString());
        JsonNode twoFaJson = objectMapper.readTree(twoFaResp.body());
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 2 2FA failed: " + twoFaJson.path("message").asText());
        }
        log.info("Kite Step 2 TOTP 2FA verified successfully.");

        // Check if request_token is in the 2FA response data (newer Kite versions)
        String requestToken = twoFaJson.path("data").path("request_token").asText(null);
        if (requestToken != null && !requestToken.isBlank()) {
            log.info("Kite request_token found directly in 2FA response data.");
        } else {
            // Step 3: Connect Login Redirect to capture request_token
            // Cookies from Step 1 & 2 are automatically sent by HttpClient
            log.info("Attempting to capture request_token via connect redirect (cookies carried automatically)...");
            requestToken = fetchRequestTokenViaConnect(httpClient);
        }

        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalStateException("Failed to capture request_token from Kite connect redirect");
        }
        log.info("Kite Step 3 request_token captured successfully.");

        // Step 4: Exchange request_token for access_token
        String checksum = DigestUtils.sha256Hex(config.getApiKey() + requestToken + config.getApiSecret());
        String tokenFormData = "api_key=" + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);

        HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + "/session/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0")
            .POST(HttpRequest.BodyPublishers.ofString(tokenFormData))
            .build();

        HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        JsonNode tokenJson = objectMapper.readTree(tokenResp.body());
        if (!"success".equalsIgnoreCase(tokenJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 4 session token exchange failed: " + tokenJson.path("message").asText());
        }

        String token = tokenJson.path("data").path("access_token").asText();
        accessToken.set(token);
        notifyTokenRenewed(token);
        return token;
    }

    /**
     * Fetches the Kite connect URL and follows redirects to capture the request_token.
     * Uses HttpClient which automatically carries session cookies from previous steps.
     *
     * @param httpClient the HttpClient with session cookies
     * @return the request token, or null if not found
     * @throws Exception if there's an error during HTTP request
     */
    private String fetchRequestTokenViaConnect(HttpClient httpClient) throws Exception {
        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + config.getApiKey();
        String currentUrl = connectUrl;

        for (int i = 0; i < 5; i++) {
            log.debug("Following redirect {}/5 from: {}", i + 1, currentUrl);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(currentUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            log.debug("Redirect {}/5: status={}", i + 1, status);

            // Check for request_token in Location header
            resp.previousResponse().ifPresent(prev -> {});
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location != null && !location.isBlank()) {
                if (location.contains("request_token=")) {
                    log.info("Found request_token in redirect URL at step {}", i + 1);
                    return extractQueryParam(location, "request_token");
                }
                // Make relative URLs absolute
                if (!location.startsWith("http")) {
                    location = "https://kite.zerodha.com" + location;
                }
                currentUrl = location;
                continue;
            }

            // No redirect — check response body for request_token
            String body = resp.body();
            log.debug("Connect page response body ({} bytes): {}", body.length(),
                body.length() > 200 ? body.substring(0, 200) : body);

            Matcher m = REQUEST_TOKEN_PATTERN.matcher(body);
            if (m.find()) {
                log.info("Found request_token in response body at step {}", i + 1);
                return m.group(1);
            }

            // If 400/401, the session might not have been established
            if (status >= 400) {
                log.warn("Connect URL returned status {} — session cookies may not be attached", status);
            }

            break;
        }
        return null;
    }

    /**
     * Extracts a query parameter from a URL string.
     *
     * @param url   the URL containing query parameters
     * @param param the name of the parameter to extract
     * @return the decoded parameter value, or null if not found
     */
    private String extractQueryParam(String url, String param) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && parts[0].equals(param)) {
                        return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing query param from url {}: {}", url, e.getMessage());
        }
        return null;
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
