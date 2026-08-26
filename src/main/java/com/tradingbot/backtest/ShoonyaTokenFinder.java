package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.shoonya.ShoonyaAuthenticator;
import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility to discover correct Shoonya instrument tokens via SearchScrip API.
 */
public class ShoonyaTokenFinder {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int[] KEY_OFFSETS = {83, 50, 97, 114, 110, 46, 27, 93};

    private static String SHOONYA_USER_ID;
    private static String SHOONYA_ACCOUNT_ID;
    private static String SHOONYA_CLIENT_ID;
    private static String SHOONYA_SECRET_KEY;
    private static String SHOONYA_PASSWORD;
    private static String SHOONYA_TOTP_SECRET;
    private static String SHOONYA_API_KEY;
    private static String SHOONYA_VENDOR_CODE;

    private static String accessToken;
    private static String sUserToken;

    public static void main(String[] args) throws Exception {
        loadEnv();
        authenticate();

        String[][] testSymbols = {
            {"NSE", "2885", "RELIANCE"},
            {"NSE", "11536", "TCS"},
            {"NSE", "1594", "INFY"},
            {"NSE", "1333", "HDFCBANK"},
            {"NSE", "4963", "ICICIBANK"},
            {"NSE", "26000", "NIFTY50_INDEX"},
            {"NSE", "256265", "NIFTY_FALLBACK"}
        };

        for (String[] sym : testSymbols) {
            testTpseries(sym[0], sym[1], "5", sym[2]);
            Thread.sleep(350);
        }
    }

    private static void testTpseries(String exch, String token, String intrv, String label) throws Exception {
        long now = Instant.now().getEpochSecond();
        long start = now - (30L * 24 * 3600); // 30 days

        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", SHOONYA_USER_ID);
        payload.put("exch", exch);
        payload.put("token", token);
        payload.put("st", String.valueOf(start));
        payload.put("et", String.valueOf(now));
        payload.put("intrv", intrv);

        String jDataStr = mapper.writeValueAsString(payload);
        String formBody = "jData=" + jDataStr + "&jKey=" + sUserToken;

        org.springframework.web.reactive.function.client.ExchangeStrategies strategies =
            org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();

        org.springframework.web.reactive.function.client.WebClient wc = org.springframework.web.reactive.function.client.WebClient.builder()
            .baseUrl("https://api.shoonya.com")
            .exchangeStrategies(strategies)
            .build();

        String response = wc.post()
            .uri("/NorenWClientAPI/TPSeries")
            .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formBody)
            .retrieve()
            .bodyToMono(String.class)
            .block();

        JsonNode root = mapper.readTree(response);
        if (root.isArray()) {
            System.out.printf("  [OK] %-15s (token=%s): %d candles fetched%n", label, token, root.size());
        } else {
            System.out.printf("  [ERR] %-15s (token=%s): %s%n", label, token, root.path("emsg").asText(response));
        }
    }

    private static void searchScrip(String symbol, String exchange) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", SHOONYA_USER_ID);
        payload.put("exch", exchange);
        payload.put("stext", symbol);

        String jDataStr = mapper.writeValueAsString(payload);
        String formBody = "jData=" + jDataStr + "&jKey=" + sUserToken;

        String response = postForm("https://api.shoonya.com/NorenWClientAPI/SearchScrip", formBody);
        System.out.println("\n=== Search: " + exchange + ":" + symbol + " ===");
        System.out.println("Response: " + response.substring(0, Math.min(500, response.length())));

        JsonNode root = mapper.readTree(response);
        if (root.isArray()) {
            for (JsonNode node : root) {
                String tsym = node.path("tsym").asText("");
                String token = node.path("token").asText("");
                String instname = node.path("instname").asText("");
                String exp = node.path("expdate").asText("");
                String strike = node.path("strike").asText("");
                if ("EQ".equals(instname) || instname.isEmpty()) {
                    System.out.printf("  -> tsym=%s, token=%s, type=%s, exp=%s, strike=%s%n", tsym, token, instname, exp, strike);
                }
            }
        }
    }

private static void authenticate() throws Exception {
ShoonyaConfig cfg = new ShoonyaConfig();
cfg.setEnabled(true);
cfg.setClientId(SHOONYA_CLIENT_ID);
cfg.setSecretKey(SHOONYA_SECRET_KEY);
cfg.setUserId(SHOONYA_USER_ID);
cfg.setAccountId(SHOONYA_ACCOUNT_ID);
cfg.setPassword(SHOONYA_PASSWORD);
cfg.setTotpSecret(SHOONYA_TOTP_SECRET);
cfg.setVendorCode(SHOONYA_VENDOR_CODE);
cfg.setApiKey(SHOONYA_API_KEY);

ShoonyaAuthenticator auth = new ShoonyaAuthenticator(
cfg,
org.springframework.web.reactive.function.client.WebClient.builder(),
mapper
);
String token = auth.authenticate().block();
accessToken = token;
sUserToken = auth.getSUserToken() != null ? auth.getSUserToken() : token;
System.out.println("GenAcsTok OK, session token: " + (sUserToken != null ? sUserToken.substring(0, 8) + "..." : "null"));
}

    private static String postForm(String urlStr, String formData) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(urlStr).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        byte[] bytes = formData.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (var os = conn.getOutputStream()) { os.write(bytes); os.flush(); }

        int code = conn.getResponseCode();
        try (var br = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    private static String generateTotpManual(String secret) throws Exception {
        byte[] key = base32Decode(secret);
        long time = System.currentTimeMillis() / 1000L / 30L;
        byte[] timeBytes = new byte[8];
        long t = time;
        for (int i = 7; i >= 0; i--) { timeBytes[i] = (byte) (t & 0xFF); t >>= 8; }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(timeBytes);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1000000);
    }

    private static byte[] base32Decode(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        encoded = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] decoded = new byte[encoded.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, count = 0;
        for (char c : encoded.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) { decoded[count++] = (byte) (buffer >> (bitsLeft - 8)); bitsLeft -= 8; }
        }
        return count < decoded.length ? java.util.Arrays.copyOf(decoded, count) : decoded;
    }

    private static void loadEnv() {
        try (var reader = new BufferedReader(new FileReader(".env", StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    switch (key) {
                        case "SHOONYA_USER_ID" -> SHOONYA_USER_ID = value;
                        case "SHOONYA_ACCOUNT_ID" -> SHOONYA_ACCOUNT_ID = value;
                        case "SHOONYA_CLIENT_ID" -> SHOONYA_CLIENT_ID = value;
                        case "SHOONYA_SECRET_KEY" -> SHOONYA_SECRET_KEY = value;
                        case "SHOONYA_PASSWORD" -> SHOONYA_PASSWORD = value;
                        case "SHOONYA_TOTP_SECRET" -> SHOONYA_TOTP_SECRET = value;
                        case "SHOONYA_API_KEY" -> SHOONYA_API_KEY = value;
                        case "SHOONYA_VENDOR_CODE" -> SHOONYA_VENDOR_CODE = value;
                    }
                }
            }
        } catch (Exception e) { System.err.println("Could not load .env: " + e.getMessage()); }
    }
}
