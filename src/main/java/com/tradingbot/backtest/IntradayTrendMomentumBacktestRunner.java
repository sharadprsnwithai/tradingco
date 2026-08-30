package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.impl.IntradayTrendMomentumOptionSellingStrategy;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.File;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backtest runner for the Intraday Trend & Momentum Option Selling Strategy.
 * Fetches Nifty Futures 15-min and 1-hour historical data from Kite Connect,
 * and runs the strategy through the BacktestEngine.
 *
 * @see <a href="st_intraday_option_selling.md">Strategy Specification</a>
 */
public class IntradayTrendMomentumBacktestRunner {

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
        int daysBack = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        int totalDaysToFetch = daysBack + 15; // 15 days of warmup for SuperTrend & 1H RSI

        System.out.println("=".repeat(80));
        System.out.printf("  SUPERTREND + RSI INTRADAY OPTION SELLING - BACKTEST (LAST %d DAYS)%n", daysBack);
        System.out.println("=".repeat(80));

        List<Candle> allCandles15m = new ArrayList<>();
        List<Candle> allCandles1h = new ArrayList<>();

        // Try Kite API first
        try {
            System.out.println("\n[1/4] Attempting authentication with Kite Connect...");
            CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            java.net.CookieHandler.setDefault(cookieManager);

            String accessToken = executeKiteHeadlessLogin();
            System.out.println("  -> Kite authentication successful");

            System.out.println("\n[2/4] Discovering Nifty Futures instrument token...");
            String niftyToken = searchNiftyFuturesToken(accessToken);
            if (niftyToken != null) {
                System.out.printf("  -> Nifty Futures token: %s%n", niftyToken);
                LocalDate toDate = LocalDate.now(IST);
                LocalDate fromDate = toDate.minusDays(totalDaysToFetch);

                allCandles15m = fetchKiteHistoricalCandles(accessToken, niftyToken, "15minute", fromDate, toDate, "NIFTY_FUT")
                    .stream()
                    .map(c -> new Candle(c.symbol(), "15", c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();

                allCandles1h = fetchKiteHistoricalCandles(accessToken, niftyToken, "60minute", fromDate, toDate, "NIFTY_FUT")
                    .stream()
                    .map(c -> new Candle(c.symbol(), "60", c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            }
        } catch (Exception e) {
            System.out.printf("  -> Kite Connect live fetch unavailable (%s). Falling back to local data.%n", e.getMessage());
        }

        // Fallback to local 5m dataset aggregated into 15m and 60m
        if (allCandles15m.isEmpty() || allCandles1h.isEmpty()) {
            System.out.println("\n[2/4] Loading and aggregating real Nifty data from data/nifty_5m_6months.json...");
            allCandles15m = loadAndAggregateCandles("data/nifty_5m_6months.json", 3, "15", totalDaysToFetch);
            allCandles1h = loadAndAggregateCandles("data/nifty_5m_6months.json", 12, "60", totalDaysToFetch);
        }

        if (allCandles15m.isEmpty()) {
            System.err.println("ERROR: No historical data available for backtesting.");
            return;
        }

        LocalDateTime first = LocalDateTime.ofInstant(allCandles15m.get(0).timestamp(), IST);
        LocalDateTime last = LocalDateTime.ofInstant(allCandles15m.get(allCandles15m.size() - 1).timestamp(), IST);
        System.out.printf("  -> Dataset (including warmup): %d 15m candles, %d 1h candles (%s to %s)%n",
            allCandles15m.size(), allCandles1h.size(),
            first.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")),
            last.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")));

        // Run backtest with strategy instance
        System.out.println("\n[3/4] Running backtest...");

        IntradayTrendMomentumOptionSellingStrategy strategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_INTRADAY_BACKTEST", "BACKTEST_ACCOUNT", "NIFTY_FUT"
        );

        // Combine all candles and sort chronologically
        List<Candle> allCandles = new ArrayList<>(allCandles15m);
        allCandles.addAll(allCandles1h);
        allCandles.sort(Comparator.comparing(Candle::timestamp));

        BacktestEngine engine = new BacktestEngine();
        BigDecimal initialCapital = BigDecimal.valueOf(100000);

        System.out.printf("  -> Replaying %d total multi-timeframe candles...%n", allCandles.size());

        BacktestResult result = engine.run(strategy, allCandles, initialCapital);

        // Filter trades to the requested evaluation window (last daysBack days)
        LocalDate evaluationStart = allCandles15m.get(allCandles15m.size() - 1).timestamp().atZone(IST).toLocalDate().minusDays(daysBack);
        List<BacktestTrade> evalTrades = result.trades().stream()
            .filter(t -> !t.entryTime().atZone(IST).toLocalDate().isBefore(evaluationStart))
            .toList();

        BigDecimal evalPnl = evalTrades.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        int evalWins = (int) evalTrades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        int evalLosses = (int) evalTrades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) < 0).count();
        double winRate = evalTrades.isEmpty() ? 0 : (evalWins * 100.0 / evalTrades.size());

        // Print results
        System.out.println("\n[4/4] Backtest Complete");
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("  BACKTEST RESULTS (SUPERTREND OPTION SELLING - LAST %d DAYS)%n", daysBack);
        System.out.println("=".repeat(80));
        System.out.printf("  Evaluation Period: %s to %s%n",
            evaluationStart,
            allCandles.get(allCandles.size() - 1).timestamp().atZone(IST).toLocalDate());
        System.out.printf("  Total Trades:      %d%n", evalTrades.size());
        System.out.printf("  Winning Trades:    %d%n", evalWins);
        System.out.printf("  Losing Trades:     %d%n", evalLosses);
        System.out.printf("  Win Rate:          %.1f%%%n", winRate);
        System.out.printf("  Net P&L:           ₹%.2f%n", evalPnl);
        System.out.printf("  Final Capital:     ₹%.2f%n", initialCapital.add(evalPnl));
        System.out.println("=".repeat(80));

        // Print trade details if any
        if (!evalTrades.isEmpty()) {
            System.out.println("\n  TRADE DETAILS:");
            System.out.println("-".repeat(80));
            for (BacktestTrade trade : evalTrades) {
                String emoji = trade.pnl().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                System.out.printf("  %s | %s %s | Entry: ₹%.2f -> Exit: ₹%.2f | Qty: %d | P&L: %s₹%.2f | Reason: %s%n",
                    trade.entryTime().atZone(IST).toLocalDate(),
                    trade.direction(), trade.symbol(),
                    trade.entryPrice(), trade.exitPrice(),
                    trade.quantity(), emoji, trade.pnl(), trade.exitTag() != null ? trade.exitTag() : "EXIT");
            }
        } else {
            System.out.println("\n  No trades executed during evaluation period.");
        }
    }

    private static List<Candle> loadAndAggregateCandles(String filePath, int stride, String targetTf, int daysBack) {
        File f = new File(filePath);
        if (!f.exists()) return List.of();
        try {
            JsonNode root = mapper.readTree(f);
            List<Candle> raw5m = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            for (JsonNode row : root) {
                if (row.isArray() && row.size() >= 6) {
                    String timeStr = row.get(0).asText();
                    Instant ts = ZonedDateTime.parse(timeStr, fmt).toInstant();
                    BigDecimal open = BigDecimal.valueOf(row.get(1).asDouble());
                    BigDecimal high = BigDecimal.valueOf(row.get(2).asDouble());
                    BigDecimal low = BigDecimal.valueOf(row.get(3).asDouble());
                    BigDecimal close = BigDecimal.valueOf(row.get(4).asDouble());
                    long volume = row.get(5).asLong();
                    raw5m.add(new Candle("NIFTY_FUT", "5", ts, open, high, low, close, volume));
                }
            }
            if (raw5m.isEmpty()) return List.of();

            LocalDate maxDate = raw5m.stream()
                .map(c -> c.timestamp().atZone(IST).toLocalDate())
                .max(LocalDate::compareTo).orElse(LocalDate.now(IST));
            LocalDate cutoff = maxDate.minusDays(daysBack);

            Map<LocalDate, List<Candle>> byDay = new LinkedHashMap<>();
            for (Candle c : raw5m) {
                LocalDate d = c.timestamp().atZone(IST).toLocalDate();
                if (!d.isBefore(cutoff)) {
                    byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
                }
            }

            List<Candle> aggregated = new ArrayList<>();
            for (List<Candle> dayList : byDay.values()) {
                for (int i = 0; i < dayList.size(); i += stride) {
                    int end = Math.min(i + stride, dayList.size());
                    List<Candle> group = dayList.subList(i, end);
                    if (group.isEmpty()) continue;

                    BigDecimal open = group.get(0).open();
                    BigDecimal close = group.get(group.size() - 1).close();
                    BigDecimal high = group.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(open);
                    BigDecimal low = group.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(open);
                    long volume = group.stream().mapToLong(Candle::volume).sum();
                    Instant ts = group.get(0).timestamp();

                    aggregated.add(new Candle("NIFTY_FUT", targetTf, ts, open, high, low, close, volume));
                }
            }
            return aggregated;
        } catch (Exception e) {
            System.err.println("Error reading/aggregating JSON candles: " + e.getMessage());
            return List.of();
        }
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

        String bestToken = null;
        String bestExpiry = null;

        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= Math.max(idxToken, Math.max(idxExchange, idxName))) continue;

            String exchange = idxExchange >= 0 && cols.length > idxExchange ? cols[idxExchange].trim().replace("\"", "") : "";
            String name = idxName >= 0 && cols.length > idxName ? cols[idxName].trim().replace("\"", "") : "";
            String instType = idxInstrumentType >= 0 && cols.length > idxInstrumentType ? cols[idxInstrumentType].trim().replace("\"", "") : "";
            String expiry = idxExpiry >= 0 && cols.length > idxExpiry ? cols[idxExpiry].trim().replace("\"", "") : "";
            String token = idxToken >= 0 && cols.length > idxToken ? cols[idxToken].trim().replace("\"", "") : "";

            if ("NFO".equals(exchange) && "FUT".equals(instType) && "NIFTY".equals(name) && !expiry.isEmpty()) {
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
}
