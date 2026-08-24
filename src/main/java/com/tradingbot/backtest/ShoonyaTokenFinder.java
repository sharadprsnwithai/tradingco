package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static String SHOONYA_CLIENT_ID;
    private static String SHOONYA_SECRET_KEY;
    private static String SHOONYA_PASSWORD;
    private static String SHOONYA_TOTP_SECRET;
    private static String SHOONYA_API_KEY;

    private static String accessToken;
    private static String sUserToken;

    public static void main(String[] args) throws Exception {
        loadEnv();
        authenticate();

        String[] symbols = {"RELIANCE", "TCS", "INFY", "HDFCBANK", "ICICIBANK"};
        for (String sym : symbols) {
            searchScrip(sym, "NSE");
            Thread.sleep(400);
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
        StringBuilder keyBuilder = new StringBuilder(SHOONYA_USER_ID).append("|");
        for (int p = 0; p < KEY_OFFSETS.length; p++) {
            keyBuilder.append((char) (KEY_OFFSETS[p] + p));
        }
        String appkey = DigestUtils.sha256Hex(keyBuilder.toString());
        String pwdSha = DigestUtils.sha256Hex(SHOONYA_PASSWORD);
        String totp = generateTotpManual(SHOONYA_TOTP_SECRET);

        Map<String, Object> quickAuthPayload = new HashMap<>();
        quickAuthPayload.put("apkversion", "W2_20250926");
        quickAuthPayload.put("uid", SHOONYA_USER_ID);
        quickAuthPayload.put("pwd", pwdSha);
        quickAuthPayload.put("factor2", totp);
        quickAuthPayload.put("appkey", appkey);
        quickAuthPayload.put("imei", "12345678-1234-1234-1234-123456789abc");
        quickAuthPayload.put("addldivinf", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        quickAuthPayload.put("source", "API");
        quickAuthPayload.put("vc", "NOREN_API");
        quickAuthPayload.put("app_key", SHOONYA_CLIENT_ID);

        String quickAuthBody = "jData=" + mapper.writeValueAsString(quickAuthPayload);
        String quickAuthResponse = postForm("https://api.shoonya.com/NorenWClientAPI/QuickAuth", quickAuthBody);

    JsonNode quickAuthJson = mapper.readTree(quickAuthResponse);
    if (!"Ok".equalsIgnoreCase(quickAuthJson.path("stat").asText())) {
        throw new IllegalStateException("QuickAuth failed: " + quickAuthJson.path("emsg").asText());
    }

    String directUserToken = quickAuthJson.path("susertoken").asText(null);
    if (directUserToken != null && !directUserToken.isBlank()) {
        accessToken = directUserToken;
        sUserToken = directUserToken;
        System.out.println("QuickAuth OK with direct session token, session acquired");
        return;
    }

    String authCode = quickAuthJson.path("code").asText(null);
    System.out.println("QuickAuth OK, auth code acquired");

    String checksum = DigestUtils.sha256Hex(SHOONYA_CLIENT_ID + SHOONYA_SECRET_KEY + authCode);
    Map<String, String> genAcsPayload = Map.of("code", authCode, "checksum", checksum);
    String genAcsBody = "jData=" + mapper.writeValueAsString(genAcsPayload);

    String genAcsResponse = postForm("https://api.shoonya.com/NorenWClientAPI/GenAcsTok", genAcsBody);
    JsonNode genAcsJson = mapper.readTree(genAcsResponse);
    if (!"Ok".equalsIgnoreCase(genAcsJson.path("stat").asText())) {
        throw new IllegalStateException("GenAcsTok failed: " + genAcsJson.path("emsg").asText());
    }

    accessToken = genAcsJson.path("access_token").asText(genAcsJson.path("susertoken").asText());
    sUserToken = genAcsJson.path("susertoken").asText(accessToken);
    System.out.println("GenAcsTok OK, session acquired");
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
                        case "SHOONYA_CLIENT_ID" -> SHOONYA_CLIENT_ID = value;
                        case "SHOONYA_SECRET_KEY" -> SHOONYA_SECRET_KEY = value;
                        case "SHOONYA_PASSWORD" -> SHOONYA_PASSWORD = value;
                        case "SHOONYA_TOTP_SECRET" -> SHOONYA_TOTP_SECRET = value;
                        case "SHOONYA_API_KEY" -> SHOONYA_API_KEY = value;
                    }
                }
            }
        } catch (Exception e) { System.err.println("Could not load .env: " + e.getMessage()); }
    }
}
