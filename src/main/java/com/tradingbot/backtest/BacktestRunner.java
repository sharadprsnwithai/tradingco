package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.nse.NseGainerLoser;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone backtest runner that fetches real historical data from Kite Connect API
 * and runs LowestVolumeReversalStrategy through the BacktestEngine.
 */
public class BacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static String KITE_API_KEY;
    private static String KITE_API_SECRET;
    private static String KITE_USER_ID;
    private static String KITE_PASSWORD;
    private static String KITE_TOTP_SECRET;

    public static void main(String[] args) throws Exception {
        loadEnv();
        System.out.println("=".repeat(80));
        System.out.println("  MULTI-BROKER TRADING BOT - BACKTEST WITH REAL KITE DATA");
        System.out.println("=".repeat(80));

        // Step 1: Authenticate with Kite
        System.out.println("\n[1/4] Authenticating with Kite Connect...");
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);

        String accessToken = executeKiteHeadlessLogin();
        System.out.println("  -> Kite authentication successful");

        // Step 2: Discover instrument tokens via Kite search
        System.out.println("\n[2/4] Discovering instrument tokens from Kite...");
        Map<String, String> symbolToToken = new HashMap<>();

        String[] symbolsToFetch = {"NIFTY 50", "NIFTY BANK", "RELIANCE", "TCS", "INFY", "HDFCBANK", "ICICIBANK"};
        String[] canonicalNames = {"NSE:NIFTY", "NSE:BANKNIFTY", "NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK", "NSE:ICICIBANK"};

        for (int i = 0; i < symbolsToFetch.length; i++) {
            String token = searchKiteInstrument(accessToken, symbolsToFetch[i]);
            if (token != null) {
                symbolToToken.put(canonicalNames[i], token);
                System.out.printf("  -> %s: instrument_token=%s%n", canonicalNames[i], token);
            } else {
                System.out.printf("  -> %s: NOT FOUND%n", canonicalNames[i]);
            }
        }

        if (symbolToToken.isEmpty()) {
            System.err.println("ERROR: No instruments found. Cannot run backtest.");
            return;
        }

        // Step 3: Fetch 1 month of 5m historical candles
        System.out.println("\n[3/4] Fetching 1-month historical 5m candles from Kite...");
        Map<String, List<Candle>> allCandles = new HashMap<>();

        LocalDate toDate = LocalDate.now(IST);
        LocalDate fromDate = toDate.minusDays(30);

        for (Map.Entry<String, String> entry : symbolToToken.entrySet()) {
            String symbol = entry.getKey();
            String token = entry.getValue();

            List<Candle> candles = fetchKiteHistoricalCandles(accessToken, token, "5minute", fromDate, toDate, symbol);
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
            Thread.sleep(300); // Rate limiting (Kite: 3 req/sec)
        }

        if (allCandles.isEmpty()) {
            System.err.println("ERROR: No historical data fetched. Cannot run backtest.");
            return;
        }

        // Step 4: Run backtests
        System.out.println("\n[4/4] Running backtests...");
        BacktestEngine engine = new BacktestEngine();
        BigDecimal initialCapital = BigDecimal.valueOf(100000);

        // --- Backtest: LowestVolumeReversalStrategy ---
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  BACKTEST: LOWEST VOLUME REVERSAL STRATEGY");
        System.out.println("=".repeat(80));

        // No-op NSE client for backtesting (stock selection not needed — all F&O symbols tested)
        NseIndiaClient noOpNseClient = new NseIndiaClient(
            org.springframework.web.reactive.function.client.WebClient.builder(),
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        // Mock lot size service for backtesting — returns default lot size of 250
        com.tradingbot.instrument.LotSizeService mockLotSizeService = new com.tradingbot.instrument.LotSizeService(
            null, null, org.springframework.web.reactive.function.client.WebClient.builder()
        ) {
            @Override public int getLotSize(String symbol) { return 250; }
            @Override public int getOrderQuantity(String symbol) { return 500; }
        };

        for (Map.Entry<String, List<Candle>> entry : allCandles.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> candles = entry.getValue();

            if (symbol.equals("NSE:NIFTY") || symbol.equals("NSE:BANKNIFTY")) continue;

            LowestVolumeReversalStrategy lvrStrategy = new LowestVolumeReversalStrategy(
                "LVR_BACKTEST", "BACKTEST_ACCOUNT", symbol, 2, 2.0, 2, noOpNseClient, mockLotSizeService
            );

            try {
                BacktestResult result = engine.run(lvrStrategy, candles, initialCapital);
                printResult("Lowest Volume Reversal", symbol, result);
            } catch (Exception e) {
                System.out.printf("  ERROR backtesting %s: %s%n", symbol, e.getMessage());
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  BACKTEST COMPLETE");
        System.out.println("=".repeat(80));
    }

    // ========== Kite API Methods ==========

    private static String executeKiteHeadlessLogin() throws Exception {
        // Step 1: Login with User ID & Password
        String loginFormData = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(KITE_PASSWORD, StandardCharsets.UTF_8);

        String loginResponse = postKiteForm("https://kite.zerodha.com/api/login", loginFormData);
        JsonNode loginJson = mapper.readTree(loginResponse);
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 1 login failed: " + loginJson.path("message").asText());
        }
        String requestId = loginJson.path("data").path("request_id").asText();
        System.out.println("  -> Step 1: Login succeeded (request_id acquired)");

        // Step 2: 2FA with TOTP
        String totp = generateTotpManual(KITE_TOTP_SECRET);
        System.out.println("  -> Generated TOTP: " + totp);

        String twoFaFormData = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&request_id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
            + "&twofa_value=" + URLEncoder.encode(totp, StandardCharsets.UTF_8)
            + "&twofa_type=totp&skip_session=";

        String twoFaResponse = postKiteForm("https://kite.zerodha.com/api/twofa", twoFaFormData);
        JsonNode twoFaJson = mapper.readTree(twoFaResponse);
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 2 2FA failed: " + twoFaJson.path("message").asText());
        }
        System.out.println("  -> Step 2: TOTP 2FA verified");

        // Step 3: Capture request_token from connect redirect
        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + KITE_API_KEY;
        String requestToken = extractRequestToken(connectUrl);
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalStateException("Failed to capture request_token from Kite connect redirect");
        }
        System.out.println("  -> Step 3: request_token captured");

        // Step 4: Exchange request_token for access_token
        String checksum = DigestUtils.sha256Hex(KITE_API_KEY + requestToken + KITE_API_SECRET);
        String tokenFormData = "api_key=" + URLEncoder.encode(KITE_API_KEY, StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);

        String tokenResponse = postKiteForm("https://api.kite.trade/session/token", tokenFormData);
        JsonNode tokenJson = mapper.readTree(tokenResponse);
        if (!"success".equalsIgnoreCase(tokenJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 4 token exchange failed: " + tokenJson.path("message").asText());
        }

        String accessToken = tokenJson.path("data").path("access_token").asText();
        System.out.println("  -> Step 4: Access token acquired");
        return accessToken;
    }

    private static String extractRequestToken(String targetUrl) throws Exception {
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

    private static String parseQueryParam(String url, String param) {
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
            System.out.println("  WARNING: Error parsing query param: " + e.getMessage());
        }
        return null;
    }

    private static String searchKiteInstrument(String accessToken, String query) throws Exception {
        // Kite instruments endpoint returns CSV: instrument_token,exchange,tradingsymbol,...
        String url = "https://api.kite.trade/instruments";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
            .header("X-Kite-Version", "3")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String csv = response.body();

        // Parse CSV line by line - find NSE equity or index matching the query
        String[] lines = csv.split("\n");
        String headerLine = lines.length > 0 ? lines[0] : "";
        String[] headers = headerLine.split(",");

        // Find column indices
        int idxToken = -1, idxExchange = -1, idxName = -1, idxTradingSymbol = -1, idxInstrumentType = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("instrument_token")) idxToken = i;
            else if (h.equals("exchange")) idxExchange = i;
            else if (h.equals("name")) idxName = i;
            else if (h.equals("tradingsymbol")) idxTradingSymbol = i;
            else if (h.equals("instrument_type")) idxInstrumentType = i;
        }

        // Search for matching NSE instrument
        String bestMatch = null;
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= idxToken) continue;

            String exchange = idxExchange >= 0 && cols.length > idxExchange ? cols[idxExchange].trim() : "";
            String name = idxName >= 0 && cols.length > idxName ? cols[idxName].trim() : "";
            String tradingSymbol = idxTradingSymbol >= 0 && cols.length > idxTradingSymbol ? cols[idxTradingSymbol].trim() : "";
            String instrumentType = idxInstrumentType >= 0 && cols.length > idxInstrumentType ? cols[idxInstrumentType].trim() : "";
            String token = cols[idxToken].trim();

            if (!"NSE".equals(exchange)) continue;

            // Match by name (for indices like "NIFTY 50", "NIFTY BANK") or tradingsymbol (for stocks)
            boolean nameMatch = query.equalsIgnoreCase(name);
            boolean symbolMatch = query.equalsIgnoreCase(tradingSymbol);

            // For indices, prefer EQ type or no type; for stocks match directly
            if (nameMatch || symbolMatch) {
                // Prefer equity (EQ) or index
                if ("EQ".equals(instrumentType) || instrumentType.isEmpty() || nameMatch) {
                    bestMatch = token;
                    // For indices, stop at first match
                    if (nameMatch && instrumentType.isEmpty()) return token;
                }
            }
        }
        return bestMatch;
    }

    private static List<Candle> fetchKiteHistoricalCandles(String accessToken, String instrumentToken,
                                                             String interval, LocalDate from, LocalDate to,
                                                             String canonicalSymbol) {
        try {
            String url = String.format("https://api.kite.trade/instruments/historical/%s/%s?from=%s&to=%s",
                instrumentToken, interval,
                from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
                .header("X-Kite-Version", "3")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String tf = interval.replace("minute", "");
            return parseKiteCandles(response.body(), canonicalSymbol, tf);
        } catch (Exception e) {
            System.out.printf("    WARNING: Failed to fetch Kite candles: %s%n", e.getMessage());
            return List.of();
        }
    }

    private static List<Candle> parseKiteCandles(String responseBody, String symbol, String interval) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonNode json = mapper.readTree(responseBody);
            JsonNode data = json.path("data").path("candles");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    // Kite returns: [timestamp, open, high, low, close, volume, ...]
                    if (node.isArray() && node.size() >= 6) {
                        Instant timestamp = parseKiteTimestamp(node.get(0).asText());
                        BigDecimal open = new BigDecimal(node.get(1).asText());
                        BigDecimal high = new BigDecimal(node.get(2).asText());
                        BigDecimal low = new BigDecimal(node.get(3).asText());
                        BigDecimal close = new BigDecimal(node.get(4).asText());
                        long volume = node.get(5).asLong();
                        candles.add(new Candle(symbol, interval, timestamp, open, high, low, close, volume));
                    }
                }
            }
        } catch (Exception e) {
            System.out.printf("    WARNING: Parse error: %s%n", e.getMessage());
        }
        return candles;
    }

    /**
     * Parses Kite timestamp format "2026-07-20T09:15:00+0530" into Instant.
     * The +0530 offset is not standard ISO-8601 (missing colon), so we fix it.
     */
    private static Instant parseKiteTimestamp(String ts) {
        try {
            // Fix timezone offset: "+0530" -> "+05:30"
            if (ts.length() > 5 && (ts.endsWith("+0530") || ts.endsWith("+05:30"))) {
                ts = ts.substring(0, ts.length() - 5) + "+05:30";
            } else if (ts.length() > 5 && ts.matches(".*[+-]\\d{4}$")) {
                ts = ts.substring(0, ts.length() - 2) + ":" + ts.substring(ts.length() - 2);
            }
            return Instant.parse(ts);
        } catch (Exception e) {
            // Fallback: try parsing as LocalDateTime with IST zone
            try {
                String cleanTs = ts.replaceAll("[+-]\\d{4}$", "");
                LocalDateTime ldt = LocalDateTime.parse(cleanTs, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private static String postKiteForm(String urlStr, String formData) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        byte[] bytes = formData.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

        try (var os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
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
            for (int i = 0; i < result.trades().size(); i++) {
                var t = result.trades().get(i);
                System.out.printf("    %d. %s %s | Entry: Rs.%.2f -> Exit: Rs.%.2f | Qty: %d | P&L: Rs.%.2f (%.2f%%) | %s -> %s%n",
                    i + 1, t.symbol(), t.direction(), t.entryPrice(), t.exitPrice(),
                    t.quantity(), t.pnl(), t.pnlPercent(),
                    LocalDateTime.ofInstant(t.entryTime(), IST).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    LocalDateTime.ofInstant(t.exitTime(), IST).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                );
            }
        } else {
            System.out.println("  No trades executed.");
        }
    }

    // ========== .env Loader ==========

    private static void loadEnv() {
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(".env", java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    switch (key) {
                        case "KITE_API_KEY" -> KITE_API_KEY = value;
                        case "KITE_API_SECRET" -> KITE_API_SECRET = value;
                        case "KITE_USER_ID" -> KITE_USER_ID = value;
                        case "KITE_PASSWORD" -> KITE_PASSWORD = value;
                        case "KITE_TOTP_SECRET" -> KITE_TOTP_SECRET = value;
                    }
                }
            }
            System.out.println("  -> Loaded .env configuration");
        } catch (Exception e) {
            System.err.println("WARNING: Could not load .env file: " + e.getMessage());
        }
    }
}
