package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone backtest runner that fetches real historical data from Shoonya (Finvasia NorenAPI)
 * and runs LowestVolumeReversalStrategy through the BacktestEngine.
 *
 * Authentication flow:
 *   1. QuickAuth with derived appkey + SHA-256(password) + TOTP
 *   2. GenAcsTok with SHA-256(clientId + secretKey + authCode)
 *   3. TPSeries POST with jData JSON + jKey session token
 */
public class ShoonyaBacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int[] KEY_OFFSETS = {83, 50, 97, 114, 110, 46, 27, 93};

    // Shoonya credentials from .env
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
        System.out.println("=".repeat(80));
        System.out.println("  SHOONYA BACKTEST RUNNER - REAL NorenAPI DATA");
        System.out.println("=".repeat(80));

        // Step 1: Authenticate with Shoonya
        System.out.println("\n[1/5] Authenticating with Shoonya (Finvasia NorenAPI)...");
        if (!authenticateWithExistingSession()) {
            authenticateShoonya();
        }
        System.out.println("  -> Shoonya authentication successful");

        // Step 2: Fetch scripmasters to discover tokens
        System.out.println("\n[2/5] Fetching Shoonya scripmasters for instrument tokens...");
        Map<String, String> symbolToToken = discoverShoonyaTokens();

        if (symbolToToken.isEmpty()) {
            System.err.println("ERROR: No instrument tokens discovered. Cannot run backtest.");
            return;
        }

        // Step 3: Fetch 1 month of 5m historical candles via TPSeries
        System.out.println("\n[3/5] Fetching 1-month historical 5m candles from Shoonya TPSeries...");
        Map<String, List<Candle>> allCandles = new HashMap<>();

        long nowEpoch = Instant.now().getEpochSecond();
        long startEpoch = nowEpoch - (30L * 24 * 60 * 60); // 30 days ago

        for (Map.Entry<String, String> entry : symbolToToken.entrySet()) {
            String symbol = entry.getKey();
            String token = entry.getValue();
            String exchange = symbol.startsWith("NSE:") ? "NSE" : "NFO";

            List<Candle> candles = fetchShoonyaTPSeries(exchange, token, startEpoch, nowEpoch, "5", symbol);
            if (!candles.isEmpty()) {
                allCandles.put(symbol, candles);
                LocalDateTime first = LocalDateTime.ofInstant(candles.get(0).timestamp(), IST);
                LocalDateTime last = LocalDateTime.ofInstant(candles.get(candles.size() - 1).timestamp(), IST);
                System.out.printf("  -> %s: %d candles (%s to %s)%n", symbol, candles.size(),
                    first.format(DateTimeFormatter.ofPattern("dd-MM HH:mm")),
                    last.format(DateTimeFormatter.ofPattern("dd-MM HH:mm")));
            } else {
                System.out.printf("  -> %s: NO DATA%n", symbol);
            }
            Thread.sleep(400); // Rate limiting (Shoonya: ~3 req/sec)
        }

        if (allCandles.isEmpty()) {
            System.err.println("ERROR: No historical data fetched. Cannot run backtest.");
            return;
        }

        // Step 4: Run backtests
        System.out.println("\n[4/5] Running backtests...");
        BacktestEngine engine = new BacktestEngine();
        BigDecimal initialCapital = BigDecimal.valueOf(100000);

        // --- Backtest: LowestVolumeReversalStrategy ---
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  BACKTEST: LOWEST VOLUME REVERSAL STRATEGY (Shoonya Data)");
        System.out.println("=".repeat(80));

        NseIndiaClient noOpNseClient = new NseIndiaClient(
            org.springframework.web.reactive.function.client.WebClient.builder(),
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        com.tradingbot.instrument.LotSizeService mockLotSizeService = new com.tradingbot.instrument.LotSizeService(
            null, null, org.springframework.web.reactive.function.client.WebClient.builder()
        ) {
            @Override public int getLotSize(String s) { return 250; }
            @Override public int getOrderQuantity(String s) { return 500; }
        };

        for (Map.Entry<String, List<Candle>> entry : allCandles.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> candles = entry.getValue();

            if (symbol.equals("NSE:NIFTY") || symbol.equals("NSE:BANKNIFTY")) continue;

            LowestVolumeReversalStrategy lvrStrategy = new LowestVolumeReversalStrategy(
                "LVR_SHOONYA", "SHOONYA_ACCOUNT", symbol, 2, 2.0, 2, noOpNseClient, mockLotSizeService
            );

            try {
                BacktestResult result = engine.run(lvrStrategy, candles, initialCapital);
                printResult("Lowest Volume Reversal", symbol, result);
            } catch (Exception e) {
                System.out.printf("  ERROR backtesting %s: %s%n", symbol, e.getMessage());
            }
        }

        // Step 5: Summary
        System.out.println("\n[5/5] Backtest complete.");
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  SHOONYA BACKTEST COMPLETE");
        System.out.println("=".repeat(80));
    }

    // ========== Shoonya Authentication ==========

    private static boolean authenticateWithExistingSession() {
        try {
            java.io.File sessionFile = new java.io.File("data/shoonya_session.json");
            if (!sessionFile.exists()) return false;

            JsonNode root = mapper.readTree(sessionFile);
            String token = root.path("accessToken").asText(null);
            String userToken = root.path("susertoken").asText(null);
            String createdAtStr = root.path("createdAt").asText(null);

            if (token == null || createdAtStr == null) return false;

            Instant createdAt = Instant.parse(createdAtStr);
            if (createdAt.plus(Duration.ofHours(12)).isBefore(Instant.now())) {
                System.out.println("  -> Cached session expired, needs fresh login");
                return false;
            }

            accessToken = token;
            sUserToken = userToken;
            System.out.println("  -> Using cached session from " + createdAtStr);
            return true;
        } catch (Exception e) {
            System.out.println("  -> Could not load cached session: " + e.getMessage());
            return false;
        }
    }

    private static void authenticateShoonya() throws Exception {
        // Step 1: QuickAuth
        StringBuilder keyBuilder = new StringBuilder(SHOONYA_USER_ID).append("|");
        for (int p = 0; p < KEY_OFFSETS.length; p++) {
            keyBuilder.append((char) (KEY_OFFSETS[p] + p));
        }
        String appkey = DigestUtils.sha256Hex(keyBuilder.toString());
        String pwdSha = DigestUtils.sha256Hex(SHOONYA_PASSWORD);
        String totp = generateTotpManual(SHOONYA_TOTP_SECRET);
        System.out.println("  -> Generated TOTP: " + totp);

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
        System.out.println("  -> QuickAuth payload: " + quickAuthBody.substring(0, Math.min(200, quickAuthBody.length())));

        String quickAuthResponse = postForm("https://api.shoonya.com/NorenWClientAPI/QuickAuth", quickAuthBody);
        System.out.println("  -> QuickAuth response: " + quickAuthResponse.substring(0, Math.min(200, quickAuthResponse.length())));

        JsonNode quickAuthJson = mapper.readTree(quickAuthResponse);
        if (!"Ok".equalsIgnoreCase(quickAuthJson.path("stat").asText())) {
            throw new IllegalStateException("Shoonya QuickAuth failed: " + quickAuthJson.path("emsg").asText());
        }

        String authCode = quickAuthJson.path("code").asText(null);
        if (authCode == null || authCode.isBlank()) {
            throw new IllegalStateException("Shoonya QuickAuth did not return authorization code");
        }
        System.out.println("  -> Step 1: QuickAuth succeeded, auth code acquired");

        // Step 2: GenAcsTok
        String checksum = DigestUtils.sha256Hex(SHOONYA_CLIENT_ID + SHOONYA_SECRET_KEY + authCode);
        Map<String, String> genAcsPayload = Map.of("code", authCode, "checksum", checksum);
        String genAcsBody = "jData=" + mapper.writeValueAsString(genAcsPayload);

        String genAcsResponse = postForm("https://api.shoonya.com/NorenWClientAPI/GenAcsTok", genAcsBody);
        System.out.println("  -> GenAcsTok response: " + genAcsResponse.substring(0, Math.min(200, genAcsResponse.length())));

        JsonNode genAcsJson = mapper.readTree(genAcsResponse);
        if (!"Ok".equalsIgnoreCase(genAcsJson.path("stat").asText())) {
            throw new IllegalStateException("Shoonya GenAcsTok failed: " + genAcsJson.path("emsg").asText());
        }

        accessToken = genAcsJson.path("access_token").asText(genAcsJson.path("susertoken").asText());
        sUserToken = genAcsJson.path("susertoken").asText(accessToken);
        System.out.println("  -> Step 2: GenAcsTok succeeded, session token acquired");
    }

    // ========== Shoonya Token Discovery ==========

    private static Map<String, String> discoverShoonyaTokens() throws Exception {
        Map<String, String> symbolToToken = new HashMap<>();

        // Shoonya scripmasters endpoint - returns CSV
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.shoonya.com/ScripMaster.csv"))
            .header("Authorization", accessToken)
            .GET()
            .build();

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());
        String csv = response.body();

        if (csv == null || csv.isBlank()) {
            System.out.println("  -> WARNING: Empty scripmaster response, using known tokens");
            return getKnownTokens();
        }

        System.out.println("  -> Received scripmaster data (" + csv.length() + " bytes)");

        // Parse CSV - find NSE EQ instruments for our target symbols
        String[] lines = csv.split("\n");
        if (lines.length < 2) {
            System.out.println("  -> WARNING: Scripmaster has no data rows, using known tokens");
            return getKnownTokens();
        }

        String[] headers = lines[0].split(",");
        int idxToken = -1, idxExchange = -1, idxName = -1, idxTradingSymbol = -1, idxInstrumentType = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("token")) idxToken = i;
            else if (h.equals("exch")) idxExchange = i;
            else if (h.equals("name")) idxName = i;
            else if (h.equals("tsym")) idxTradingSymbol = i;
            else if (h.equals("instname")) idxInstrumentType = i;
        }

        // Target symbols to find
        String[][] targets = {
            {"NIFTY", "NSE", "Index"},
            {"BANKNIFTY", "NSE", "Index"},
            {"RELIANCE", "NSE", "EQ"},
            {"TCS", "NSE", "EQ"},
            {"INFY", "NSE", "EQ"},
            {"HDFCBANK", "NSE", "EQ"},
            {"ICICIBANK", "NSE", "EQ"}
        };

        String[] canonicalNames = {"NSE:NIFTY", "NSE:BANKNIFTY", "NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK", "NSE:ICICIBANK"};

        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= Math.max(idxToken, idxExchange)) continue;

            String exchange = idxExchange >= 0 && cols.length > idxExchange ? cols[idxExchange].trim() : "";
            String name = idxName >= 0 && cols.length > idxName ? cols[idxName].trim() : "";
            String tradingSymbol = idxTradingSymbol >= 0 && cols.length > idxTradingSymbol ? cols[idxTradingSymbol].trim() : "";
            String instrumentType = idxInstrumentType >= 0 && cols.length > idxInstrumentType ? cols[idxInstrumentType].trim() : "";
            String token = idxToken >= 0 && cols.length > idxToken ? cols[idxToken].trim() : "";

            if (!"NSE".equals(exchange) || token.isEmpty()) continue;

            for (int j = 0; j < targets.length; j++) {
                String targetName = targets[j][0];
                String targetType = targets[j][2];
                String canonical = canonicalNames[j];

                if (symbolToToken.containsKey(canonical)) continue;

                boolean nameMatch = targetName.equalsIgnoreCase(name);
                boolean symbolMatch = targetName.equalsIgnoreCase(tradingSymbol);

                if (nameMatch || symbolMatch) {
                    if ("Index".equals(targetType) && instrumentType.isEmpty()) {
                        symbolToToken.put(canonical, token);
                        System.out.printf("  -> %s: shoonya_token=%s (from scripmaster)%n", canonical, token);
                    } else if ("EQ".equals(targetType) && "EQ".equals(instrumentType)) {
                        symbolToToken.put(canonical, token);
                        System.out.printf("  -> %s: shoonya_token=%s (from scripmaster)%n", canonical, token);
                    }
                }
            }
        }

        // Fallback to known tokens if scripmaster didn't have all
        if (symbolToToken.size() < targets.length) {
            System.out.println("  -> Some tokens missing from scripmaster, using fallback known tokens");
            Map<String, String> known = getKnownTokens();
            for (Map.Entry<String, String> e : known.entrySet()) {
                symbolToToken.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        return symbolToToken;
    }

    private static Map<String, String> getKnownTokens() {
        // Correct Shoonya NSE tokens (discovered via SearchScrip API)
        Map<String, String> tokens = new HashMap<>();
        tokens.put("NSE:NIFTY", "256265");
        tokens.put("NSE:BANKNIFTY", "260105");
        tokens.put("NSE:RELIANCE", "2885");
        tokens.put("NSE:TCS", "11536");
        tokens.put("NSE:INFY", "1594");
        tokens.put("NSE:HDFCBANK", "1333");
        tokens.put("NSE:ICICIBANK", "4963");

        for (Map.Entry<String, String> e : tokens.entrySet()) {
            System.out.printf("  -> %s: shoonya_token=%s (known fallback)%n", e.getKey(), e.getValue());
        }
        return tokens;
    }

    // ========== Shoonya TPSeries ==========

    private static List<Candle> fetchShoonyaTPSeries(String exchange, String token,
                                                       long startEpoch, long endEpoch,
                                                       String interval, String canonicalSymbol) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("uid", SHOONYA_USER_ID);
            payload.put("exch", exchange);
            payload.put("token", token);
            payload.put("st", String.valueOf(startEpoch));
            payload.put("et", String.valueOf(endEpoch));
            payload.put("intrv", interval);

            String jDataStr = mapper.writeValueAsString(payload);
            // Shoonya expects raw JSON in jData field, not URL-encoded
            String formBody = "jData=" + jDataStr
                + "&jKey=" + sUserToken;

            String response = postForm("https://api.shoonya.com/NorenWClientAPI/TPSeries", formBody);
            return parseShoonyaCandles(response, canonicalSymbol, interval);
        } catch (Exception e) {
            System.out.printf("    WARNING: Failed to fetch Shoonya TPSeries for %s: %s%n", canonicalSymbol, e.getMessage());
            return List.of();
        }
    }

    private static List<Candle> parseShoonyaCandles(String responseBody, String symbol, String interval) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String stat = node.path("stat").asText("Ok");
                    if ("Ok".equalsIgnoreCase(stat) || node.has("into")) {
                        BigDecimal open = new BigDecimal(node.path("into").asText("0"));
                        BigDecimal high = new BigDecimal(node.path("inth").asText("0"));
                        BigDecimal low = new BigDecimal(node.path("intl").asText("0"));
                        BigDecimal close = new BigDecimal(node.path("intc").asText("0"));
                        long volume = node.path("v").asLong(0);

                        Instant timestamp = parseShoonyaTimestamp(node);
                        candles.add(new Candle(symbol, interval, timestamp, open, high, low, close, volume));
                    }
                }
            } else if (root.isObject() && "Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
                System.out.println("    WARNING: Shoonya TPSeries error: " + root.path("emsg").asText("Unknown"));
            }
        } catch (Exception e) {
            System.out.printf("    WARNING: Failed to parse Shoonya TPSeries response: %s%n", e.getMessage());
            System.out.println("    Response preview: " + responseBody.substring(0, Math.min(300, responseBody.length())));
        }

        // Sort chronologically ascending
        candles.sort(Comparator.comparing(Candle::timestamp));

        // Limit to max candles (keep most recent)
        int maxCandles = 2000;
        if (candles.size() > maxCandles) {
            return candles.subList(candles.size() - maxCandles, candles.size());
        }
        return candles;
    }

    /**
     * Parse Shoonya timestamp. Tries:
     * 1. "ssboe" field (epoch seconds) - preferred
     * 2. "time" field as "dd/MM/yyyy HH:mm:ss" - fallback
     * 3. Instant.now() - last resort
     */
    private static Instant parseShoonyaTimestamp(JsonNode node) {
        // Try epoch seconds first
        if (node.has("ssboe")) {
            long epochSeconds = node.path("ssboe").asLong();
            if (epochSeconds > 0) {
                return Instant.ofEpochSecond(epochSeconds);
            }
        }
        // Try time string - Shoonya uses "dd/MM/yyyy HH:mm:ss"
        String timeStr = node.path("time").asText("");
        if (!timeStr.isEmpty()) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
                LocalDateTime ldt = LocalDateTime.parse(timeStr, formatter);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e) {
                // Try alternate format with dashes
                try {
                    DateTimeFormatter altFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
                    LocalDateTime ldt = LocalDateTime.parse(timeStr, altFormatter);
                    return ldt.atZone(IST).toInstant();
                } catch (Exception e2) {
                    System.out.println("    WARNING: Could not parse timestamp: " + timeStr);
                }
            }
        }
        return Instant.now();
    }

    // ========== HTTP Helper ==========

    private static String postForm(String urlStr, String formData) throws Exception {
        URI uri = new URI(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        byte[] bytes = formData.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

        try (var os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        try (var br = new BufferedReader(new InputStreamReader(
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

    // ========== TOTP Generation ==========

    private static String generateTotpManual(String secret) throws Exception {
        byte[] key = base32Decode(secret);
        long time = System.currentTimeMillis() / 1000L / 30L;
        byte[] timeBytes = new byte[8];
        long t = time;
        for (int i = 7; i >= 0; i--) {
            timeBytes[i] = (byte) (t & 0xFF);
            t >>= 8;
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(timeBytes);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) |
                     ((hash[offset + 1] & 0xFF) << 16) |
                     ((hash[offset + 2] & 0xFF) << 8) |
                     (hash[offset + 3] & 0xFF);
        int otp = binary % 1000000;
        return String.format("%06d", otp);
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
            if (bitsLeft >= 8) {
                decoded[count++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        if (count < decoded.length) {
            byte[] result = new byte[count];
            System.arraycopy(decoded, 0, result, 0, count);
            return result;
        }
        return decoded;
    }

    // ========== Print Results ==========

    private static void printResult(String strategyName, String symbol, BacktestResult result) {
        System.out.printf("%n  --- %s on %s ---%n", strategyName, symbol);
        System.out.printf("  Initial Capital:    Rs.%,.2f%n", result.initialCapital());
        System.out.printf("  Final Capital:      Rs.%,.2f%n", result.finalCapital());
        System.out.printf("  Net P&L:            Rs.%,.2f%n", result.netPnL());
        System.out.printf("  Total Trades:       %d%n", result.totalTrades());
        System.out.printf("  Winning Trades:     %d%n", result.winningTrades());
        System.out.printf("  Losing Trades:      %d%n", result.losingTrades());
        System.out.printf("  Win Rate:           %.1f%%%n", result.winRatePercent());
        System.out.printf("  Gross Profit:       Rs.%,.2f%n", result.grossProfit());
        System.out.printf("  Gross Loss:         Rs.%,.2f%n", result.grossLoss());
        System.out.printf("  Profit Factor:      %.2f%n", result.profitFactor());
        System.out.printf("  Max Drawdown:       Rs.%,.2f (%.1f%%)%n", result.maxDrawdown(), result.maxDrawdownPercent());

        if (!result.trades().isEmpty()) {
            System.out.println("  Trades:");
            for (int i = 0; i < Math.min(result.trades().size(), 10); i++) {
                var t = result.trades().get(i);
                System.out.printf("    %d. %s %s | Entry: Rs.%.2f -> Exit: Rs.%.2f | Qty: %d | P&L: Rs.%.2f (%.2f%%) | %s -> %s%n",
                    i + 1, t.symbol(), t.direction(), t.entryPrice(), t.exitPrice(),
                    t.quantity(), t.pnl(), t.pnlPercent(),
                    LocalDateTime.ofInstant(t.entryTime(), IST).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    LocalDateTime.ofInstant(t.exitTime(), IST).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                );
            }
            if (result.trades().size() > 10) {
                System.out.printf("    ... and %d more trades%n", result.trades().size() - 10);
            }
        } else {
            System.out.println("  No trades executed.");
        }
    }

    // ========== .env Loader ==========

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
            System.out.println("  -> Loaded .env configuration");
        } catch (Exception e) {
            System.err.println("WARNING: Could not load .env file: " + e.getMessage());
        }
    }
}
