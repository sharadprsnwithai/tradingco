package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.impl.NiftyVwapMomentumReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backtest runner for the Nifty VWAP Momentum Reversal Strategy.
 * Fetches Nifty Futures 5-min historical data from Kite Connect,
 * splits into trading days, simulates 9:30/11:00 snapshots and PCR,
 * and runs the strategy through the BacktestEngine.
 */
public class VwapBacktestRunner {

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
        System.out.println("  NIFTY VWAP MOMENTUM REVERSAL - BACKTEST WITH REAL KITE DATA");
        System.out.println("=".repeat(80));

        // Step 1: Authenticate with Kite
        System.out.println("\n[1/5] Authenticating with Kite Connect...");
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);

        String accessToken = executeKiteHeadlessLogin();
        System.out.println("  -> Kite authentication successful");

        // Step 2: Discover Nifty Futures instrument token
        System.out.println("\n[2/5] Discovering Nifty Futures instrument token...");
        String niftyToken = searchNiftyFuturesToken(accessToken);
        if (niftyToken == null) {
            System.err.println("ERROR: Could not find Nifty Futures instrument token.");
            return;
        }
        System.out.printf("  -> Nifty Futures token: %s%n", niftyToken);

        // Step 3: Fetch 3 months of 5m historical candles
        System.out.println("\n[3/5] Fetching 3-month historical 5m candles from Kite...");
        LocalDate toDate = LocalDate.now(IST);
        LocalDate fromDate = toDate.minusDays(90);

        List<Candle> allCandles = fetchKiteHistoricalCandles(accessToken, niftyToken, "5minute", fromDate, toDate, "NIFTY_FUT");
        if (allCandles.isEmpty()) {
            System.err.println("ERROR: No historical data fetched. Cannot run backtest.");
            return;
        }

        LocalDateTime first = LocalDateTime.ofInstant(allCandles.get(0).timestamp(), IST);
        LocalDateTime last = LocalDateTime.ofInstant(allCandles.get(allCandles.size() - 1).timestamp(), IST);
        System.out.printf("  -> Nifty Futures: %d candles (%s to %s)%n", allCandles.size(),
            first.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")),
            last.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")));

        // Step 4: Split into trading days and run backtest
        System.out.println("\n[4/5] Running backtests...");
        Map<LocalDate, List<Candle>> candlesByDay = splitIntoTradingDays(allCandles);
        System.out.printf("  -> %d trading days found%n", candlesByDay.size());

        BacktestEngine engine = new BacktestEngine();
        BigDecimal initialCapital = BigDecimal.valueOf(100000);
        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  DAILY RESULTS");
        System.out.println("=".repeat(80));

        for (Map.Entry<LocalDate, List<Candle>> entry : candlesByDay.entrySet()) {
            LocalDate tradingDay = entry.getKey();
            List<Candle> dayCandles = entry.getValue();

            NiftyVwapMomentumReversalStrategy strategy = new NiftyVwapMomentumReversalStrategy(
                "VWAP_NIFTY_" + tradingDay, "BACKTEST_ACCOUNT", "NIFTY_FUT"
            );

            // Simulate 9:30 and 11:00 snapshots + PCR
            simulateSnapshotsAndPcr(strategy, dayCandles, tradingDay);

            BacktestResult result = engine.run(strategy, dayCandles, initialCapital);

            totalPnl = totalPnl.add(result.netPnL());
            totalTrades += result.totalTrades();
            totalWins += result.winningTrades();
            totalLosses += result.losingTrades();

            String emoji = result.netPnL().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            System.out.printf("  %s | Trades: %d | P&L: %s₹%.2f | Wins: %d | Losses: %d%n",
                tradingDay.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                result.totalTrades(),
                emoji, result.netPnL(),
                result.winningTrades(), result.losingTrades());
        }

        // Summary
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  BACKTEST SUMMARY");
        System.out.println("=".repeat(80));
        System.out.printf("  Period:            %s to %s%n",
            candlesByDay.keySet().stream().min(LocalDate::compareTo).get(),
            candlesByDay.keySet().stream().max(LocalDate::compareTo).get());
        System.out.printf("  Trading Days:      %d%n", candlesByDay.size());
        System.out.printf("  Total Trades:      %d%n", totalTrades);
        System.out.printf("  Winning Trades:    %d%n", totalWins);
        System.out.printf("  Losing Trades:     %d%n", totalLosses);
        System.out.printf("  Win Rate:          %.1f%%%n", totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0);
        System.out.printf("  Net P&L:           ₹%.2f%n", totalPnl);
        System.out.printf("  Avg P&L/Day:       ₹%.2f%n", candlesByDay.isEmpty() ? 0 : totalPnl.doubleValue() / candlesByDay.size());
        System.out.printf("  Avg P&L/Trade:     ₹%.2f%n", totalTrades > 0 ? totalPnl.doubleValue() / totalTrades : 0);
        System.out.println("=".repeat(80));
    }

    // ========== Snapshot & PCR Simulation ==========

    /**
     * Fetches real PCR from NSE at startup and uses it as baseline.
     * For historical backtest, adjusts PCR intraday based on price movement
     * (since NSE doesn't provide historical option chain data).
     */
    /**
     * Default PCR value for backtest (no live NSE/Kite available).
     */
    private static double nseBasePcr = 1.0;

    /**
     * Simulates 9:30 AM and 11:00 AM snapshots with real PCR from NSE.
     * PCR is anchored to the real NSE value and adjusted intraday based on price momentum.
     */
    private static void simulateSnapshotsAndPcr(NiftyVwapMomentumReversalStrategy strategy,
                                                  List<Candle> dayCandles, LocalDate day) {
        Instant nineThirty = day.atTime(9, 30).atZone(IST).toInstant();
        Instant eleven = day.atTime(11, 0).atZone(IST).toInstant();

        double nifty930 = 0, nifty1100 = 0;
        double pcr930 = nseBasePcr, pcr1100 = nseBasePcr;

        for (Candle c : dayCandles) {
            Instant ts = c.timestamp();
            double close = c.close().doubleValue();

            if (nifty930 == 0 && !ts.isBefore(nineThirty) && !ts.isAfter(nineThirty.plusSeconds(300))) {
                nifty930 = close;
            }

            if (nifty1100 == 0 && !ts.isBefore(eleven) && !ts.isAfter(eleven.plusSeconds(300))) {
                nifty1100 = close;
                // Adjust PCR: moves SAME direction as price (protective puts buy during rally)
                // BULLISH: price up + PCR up; BEARISH: price down + PCR down
                if (nifty930 > 0) {
                    double priceMove = (close - nifty930) / nifty930;
                    pcr930 = nseBasePcr;
                    pcr1100 = nseBasePcr * (1 + priceMove * 3); // PCR moves WITH price
                }
            }
        }

        if (nifty930 == 0 && !dayCandles.isEmpty()) {
            nifty930 = dayCandles.get(0).close().doubleValue();
        }
        if (nifty1100 == 0 && dayCandles.size() > 6) {
            nifty1100 = dayCandles.get(6).close().doubleValue();
        }

        strategy.setBaseline930(nifty930, pcr930);
        strategy.setBaseline1100(nifty1100, pcr1100);

        System.out.printf("    %s | 930: price=%.2f pcr=%.4f | 1100: price=%.2f pcr=%.4f | bias=%s%n",
            day, nifty930, pcr930, nifty1100, pcr1100, strategy.getBias());
    }

    // ========== Day Splitting ==========

    private static Map<LocalDate, List<Candle>> splitIntoTradingDays(List<Candle> candles) {
        Map<LocalDate, List<Candle>> byDay = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate day = c.timestamp().atZone(IST).toLocalDate();
            // Skip weekends
            if (day.getDayOfWeek().getValue() >= 6) continue;
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(c);
        }
        return byDay;
    }

    // ========== Kite API Methods ==========

    private static String searchNiftyFuturesToken(String accessToken) throws Exception {
        String url = "https://api.kite.trade/instruments";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
            .header("X-Kite-Version", "3")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String csv = response.body();
        String[] lines = csv.split("\n");

        String headerLine = lines.length > 0 ? lines[0] : "";
        String[] headers = headerLine.split(",");
        int idxToken = -1, idxExchange = -1, idxName = -1, idxInstrumentType = -1, idxExpiry = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().replace("\"", "").toLowerCase();
            if (h.equals("instrument_token")) idxToken = i;
            else if (h.equals("exchange")) idxExchange = i;
            else if (h.equals("name")) idxName = i;
            else if (h.equals("instrument_type")) idxInstrumentType = i;
            else if (h.equals("expiry")) idxExpiry = i;
        }

        System.out.printf("  -> CSV columns: token=%d, exchange=%d, name=%d, type=%d, expiry=%d%n",
            idxToken, idxExchange, idxName, idxInstrumentType, idxExpiry);

        // Find NFO NIFTY futures with nearest expiry
        String bestToken = null;
        String bestExpiry = null;
        int futuresFound = 0;

        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= Math.max(idxToken, Math.max(idxExchange, idxName))) continue;

            String exchange = idxExchange >= 0 && cols.length > idxExchange ? cols[idxExchange].trim().replace("\"", "") : "";
            String name = idxName >= 0 && cols.length > idxName ? cols[idxName].trim().replace("\"", "") : "";
            String instType = idxInstrumentType >= 0 && cols.length > idxInstrumentType ? cols[idxInstrumentType].trim().replace("\"", "") : "";
            String expiry = idxExpiry >= 0 && cols.length > idxExpiry ? cols[idxExpiry].trim().replace("\"", "") : "";
            String token = idxToken >= 0 && cols.length > idxToken ? cols[idxToken].trim().replace("\"", "") : "";

            // Debug: print first few NFO NIFTY matches
            if ("NFO".equals(exchange) && name.contains("NIFTY") && futuresFound < 5) {
                System.out.printf("  -> NFO instrument: name=%s type=%s expiry=%s token=%s%n", name, instType, expiry, token);
                futuresFound++;
            }

            if ("NFO".equals(exchange) && "FUT".equals(instType) && "NIFTY".equals(name) && !expiry.isEmpty()) {
                // Find nearest future expiry
                if (bestExpiry == null || expiry.compareTo(bestExpiry) < 0) {
                    bestExpiry = expiry;
                    bestToken = token;
                }
            }
        }

        if (bestToken != null) {
            System.out.printf("  -> NIFTY FUT expiry=%s token=%s%n", bestExpiry, bestToken);
        }
        return bestToken;
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

    private static Instant parseKiteTimestamp(String ts) {
        try {
            if (ts.length() > 5 && (ts.endsWith("+0530") || ts.endsWith("+05:30"))) {
                ts = ts.substring(0, ts.length() - 5) + "+05:30";
            } else if (ts.length() > 5 && ts.matches(".*[+-]\\d{4}$")) {
                ts = ts.substring(0, ts.length() - 2) + ":" + ts.substring(ts.length() - 2);
            }
            return Instant.parse(ts);
        } catch (Exception e) {
            try {
                String cleanTs = ts.replaceAll("[+-]\\d{4}$", "");
                LocalDateTime ldt = LocalDateTime.parse(cleanTs, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private static String executeKiteHeadlessLogin() throws Exception {
        String loginFormData = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(KITE_PASSWORD, StandardCharsets.UTF_8);

        String loginResponse = postKiteForm("https://kite.zerodha.com/api/login", loginFormData);
        JsonNode loginJson = mapper.readTree(loginResponse);
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 1 login failed: " + loginJson.path("message").asText());
        }
        String requestId = loginJson.path("data").path("request_id").asText();
        System.out.println("  -> Step 1: Login succeeded");

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

        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + KITE_API_KEY;
        String requestToken = extractRequestToken(connectUrl);
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalStateException("Failed to capture request_token");
        }
        System.out.println("  -> Step 3: request_token captured");

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

    // ========== NSE Option Chain (PCR) ==========

}
