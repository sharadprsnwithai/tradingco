package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.impl.IntradayTrendMomentumOptionSellingStrategy;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;
import com.tradingbot.strategy.impl.NiftyVwapMomentumReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Diagnostic runner to check and trace signals generated on 1st Sept (and nearest trading day).
 */
public class ShoonyaTokenFinder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    private static String KITE_API_KEY;
    private static String KITE_API_SECRET;
    private static String KITE_USER_ID;
    private static String KITE_PASSWORD;
    private static String KITE_TOTP_SECRET;

    public static void main(String[] args) throws Exception {
        loadEnv();
        System.out.println("=".repeat(90));
        System.out.println("  SIGNAL CHECK FOR 1ST SEPTEMBER (SEPT 1 / SEPT 2 TRADING SESSIONS)");
        System.out.println("=".repeat(90));

        java.net.CookieHandler.setDefault(cookieManager);
        String kiteToken = executeKiteHeadlessLogin();
        System.out.println("  -> Authenticated with Kite Connect");

        // Fetch 5m, 15m, 60m candles for Sept 2024 (Sept 1 was Sunday, trading began Sept 2)
        // and Sept 2025 (Sept 1 Monday)
        String niftyToken = "256265"; // Nifty 50 Index

        LocalDate[] targetDates = {
            LocalDate.of(2024, 9, 2),  // Sept 2, 2024 (First trading day of Sept 2024)
            LocalDate.of(2025, 9, 1)   // Sept 1, 2025 (First trading day of Sept 2025)
        };

        for (LocalDate targetDate : targetDates) {
            System.out.println("\n" + "=".repeat(80));
            System.out.printf("  EVALUATING TRADING SESSION: %s (%s)%n", targetDate, targetDate.getDayOfWeek());
            System.out.println("=".repeat(80));

            List<Candle> day5m = fetchCandles(kiteToken, niftyToken, "5minute", targetDate, targetDate, "NSE:NIFTY");
            List<Candle> day15m = fetchCandles(kiteToken, niftyToken, "15minute", targetDate.minusDays(5), targetDate, "NSE:NIFTY");
            List<Candle> day60m = fetchCandles(kiteToken, niftyToken, "60minute", targetDate.minusDays(30), targetDate, "NSE:NIFTY");

            if (day5m.isEmpty()) {
                System.out.printf("  No market candles found for %s (Market Holiday / Weekend / Future Date).%n", targetDate);
                continue;
            }

            System.out.printf("  Candles fetched: 5m = %d, 15m = %d, 60m = %d%n", day5m.size(), day15m.size(), day60m.size());
            System.out.printf("  Day Range: Open = ₹%.2f | High = ₹%.2f | Low = ₹%.2f | Close = ₹%.2f%n%n",
                day5m.get(0).open().doubleValue(),
                day5m.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0),
                day5m.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0),
                day5m.get(day5m.size() - 1).close().doubleValue());

            // 1. Evaluate VWAP Strategy on this day
            evaluateVwapStrategy(day5m, targetDate);

            // 2. Evaluate Intraday Trend & Momentum Option Selling on this day
            evaluateIntradayTrendStrategy(day15m, day60m, targetDate);

            // 3. Evaluate Lowest Volume Reversal Strategy on stocks
            evaluateLvrStrategy(kiteToken, targetDate);
        }
    }

    private static void evaluateVwapStrategy(List<Candle> day5m, LocalDate day) {
        System.out.println("  ------------------------------------------------------------------");
        System.out.println("  [Strategy 1] NIFTY VWAP MOMENTUM REVERSAL STRATEGY (5m)");
        System.out.println("  ------------------------------------------------------------------");

        NiftyVwapMomentumReversalStrategy strategy = new NiftyVwapMomentumReversalStrategy(
            "VWAP_" + day, "BACKTEST_ACC", "NSE:NIFTY", 3.0
        );

        // Snapshot simulation
        Instant nineThirty = day.atTime(9, 30).atZone(IST).toInstant();
        Instant eleven = day.atTime(11, 0).atZone(IST).toInstant();
        double p930 = 0, p1100 = 0;
        for (Candle c : day5m) {
            if (p930 == 0 && !c.timestamp().isBefore(nineThirty)) p930 = c.close().doubleValue();
            if (p1100 == 0 && !c.timestamp().isBefore(eleven)) p1100 = c.close().doubleValue();
        }
        if (p930 == 0) p930 = day5m.get(0).close().doubleValue();
        if (p1100 == 0) p1100 = day5m.get(Math.min(6, day5m.size() - 1)).close().doubleValue();

        double pcr930 = 1.0;
        double pcr1100 = 1.0 * (1 + (p1100 - p930) / p930 * 3);
        strategy.setBaseline930(p930, pcr930);
        strategy.setBaseline1100(p1100, pcr1100);

        BacktestEngine engine = new BacktestEngine();
        BacktestResult res = engine.run(strategy, day5m, BigDecimal.valueOf(100000));

        System.out.printf("    9:30 Baseline: ₹%.2f (PCR: %.2f) | 11:00 Baseline: ₹%.2f (PCR: %.4f) | Bias: %s%n",
            p930, pcr930, p1100, pcr1100, strategy.getBias());
        System.out.printf("    Total Trades: %d | Wins: %d | Losses: %d | Net P&L: ₹%+.2f%n",
            res.totalTrades(), res.winningTrades(), res.losingTrades(), res.netPnL());

        if (res.trades() != null && !res.trades().isEmpty()) {
            for (BacktestTrade t : res.trades()) {
                LocalDateTime entryTime = LocalDateTime.ofInstant(t.entryTime(), IST);
                LocalDateTime exitTime = LocalDateTime.ofInstant(t.exitTime(), IST);
                System.out.printf("    -> %s %s | Entry: ₹%.2f at %s | Exit: ₹%.2f at %s | P&L: ₹%+.2f (%s -> %s)%n",
                    t.direction(), t.symbol(), t.entryPrice(), entryTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    t.exitPrice(), exitTime.format(DateTimeFormatter.ofPattern("HH:mm")), t.pnl(), t.entryTag(), t.exitTag());
            }
        } else {
            System.out.println("    -> No trade triggered (VWAP entry criteria / momentum filter not satisfied)");
        }
    }

    private static void evaluateIntradayTrendStrategy(List<Candle> day15m, List<Candle> day60m, LocalDate day) {
        System.out.println("\n  ------------------------------------------------------------------");
        System.out.println("  [Strategy 2] INTRADAY TREND OPTION SELLING (15m SuperTrend + 1h RSI)");
        System.out.println("  ------------------------------------------------------------------");

        List<Candle> combined = new ArrayList<>(day15m);
        combined.addAll(day60m);
        combined.sort(Comparator.comparing(Candle::timestamp));

        IntradayTrendMomentumOptionSellingStrategy strategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_INTRADAY", "BACKTEST_ACC", "NSE:NIFTY"
        );

        BacktestEngine engine = new BacktestEngine();
        BacktestResult res = engine.run(strategy, combined, BigDecimal.valueOf(100000));

        System.out.printf("    Total Trades: %d | Wins: %d | Losses: %d | Net P&L: ₹%+.2f%n",
            res.totalTrades(), res.winningTrades(), res.losingTrades(), res.netPnL());

        if (res.trades() != null && !res.trades().isEmpty()) {
            for (BacktestTrade t : res.trades()) {
                LocalDateTime entryTime = LocalDateTime.ofInstant(t.entryTime(), IST);
                LocalDateTime exitTime = LocalDateTime.ofInstant(t.exitTime(), IST);
                System.out.printf("    -> %s %s | Entry: ₹%.2f at %s | Exit: ₹%.2f at %s | P&L: ₹%+.2f (%s -> %s)%n",
                    t.direction(), t.symbol(), t.entryPrice(), entryTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    t.exitPrice(), exitTime.format(DateTimeFormatter.ofPattern("HH:mm")), t.pnl(), t.entryTag(), t.exitTag());
            }
        } else {
            System.out.println("    -> No trade triggered (SuperTrend and RSI directional alignment filter was flat/neutral)");
        }
    }

    private static void evaluateLvrStrategy(String accessToken, LocalDate day) throws Exception {
        System.out.println("\n  ------------------------------------------------------------------");
        System.out.println("  [Strategy 3] LOWEST VOLUME REVERSAL STRATEGY (F&O Basket 5m)");
        System.out.println("  ------------------------------------------------------------------");

        String[][] stocks = {
            {"RELIANCE", "738561", "NSE:RELIANCE"},
            {"TCS", "2953217", "NSE:TCS"},
            {"HDFCBANK", "341249", "NSE:HDFCBANK"},
            {"ICICIBANK", "1270529", "NSE:ICICIBANK"},
            {"SBIN", "779521", "NSE:SBIN"}
        };

        NseIndiaClient noOpClient = new NseIndiaClient(
            org.springframework.web.reactive.function.client.WebClient.builder(), mapper
        );
        com.tradingbot.instrument.LotSizeService mockLotService = new com.tradingbot.instrument.LotSizeService(
            null, null, org.springframework.web.reactive.function.client.WebClient.builder()
        ) {
            @Override public int getOrderQuantity(String s) { return 250; }
        };

        BacktestEngine engine = new BacktestEngine();
        int totalTrades = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (String[] stk : stocks) {
            List<Candle> candles = fetchCandles(accessToken, stk[1], "5minute", day, day, stk[2]);
            if (candles.isEmpty()) continue;

            LowestVolumeReversalStrategy s = new LowestVolumeReversalStrategy(
                "LVR_" + stk[0], "BACKTEST_ACC", stk[2], 2, 2.0, 2, noOpClient, mockLotService
            );

            BacktestResult res = engine.run(s, candles, BigDecimal.valueOf(100000));
            totalTrades += res.totalTrades();
            totalPnl = totalPnl.add(res.netPnL());

            if (res.totalTrades() > 0) {
                System.out.printf("    %-14s | Trades: %d | Net P&L: ₹%+.2f%n", stk[2], res.totalTrades(), res.netPnL());
                for (BacktestTrade t : res.trades()) {
                    LocalDateTime entryTime = LocalDateTime.ofInstant(t.entryTime(), IST);
                    LocalDateTime exitTime = LocalDateTime.ofInstant(t.exitTime(), IST);
                    System.out.printf("      -> %s | Entry: ₹%.2f at %s | Exit: ₹%.2f at %s | P&L: ₹%+.2f (%s)%n",
                        t.direction(), t.entryPrice(), entryTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                        t.exitPrice(), exitTime.format(DateTimeFormatter.ofPattern("HH:mm")), t.pnl(), t.exitTag());
                }
            } else {
                System.out.printf("    %-14s | Trades: 0 (No lowest-volume pullback confirmation)%n", stk[2]);
            }
        }
        System.out.printf("    LVR Day Summary: %d Trades | Total P&L: ₹%+.2f%n", totalTrades, totalPnl);
    }

    private static List<Candle> fetchCandles(String accessToken, String token, String interval,
                                             LocalDate from, LocalDate to, String canonical) {
        try {
            String url = String.format("https://api.kite.trade/instruments/historical/%s/%s?from=%s&to=%s",
                token, interval, from, to);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
                .header("X-Kite-Version", "3")
                .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data").path("candles");
            List<Candle> candles = new ArrayList<>();
            if (data.isArray()) {
                String tf = interval.replace("minute", "");
                for (JsonNode n : data) {
                    Instant ts = parseKiteTimestamp(n.get(0).asText());
                    candles.add(new Candle(canonical, tf, ts,
                        new BigDecimal(n.get(1).asText()),
                        new BigDecimal(n.get(2).asText()),
                        new BigDecimal(n.get(3).asText()),
                        new BigDecimal(n.get(4).asText()),
                        n.get(5).asLong()));
                }
            }
            return candles;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static Instant parseKiteTimestamp(String ts) {
        if (ts.length() > 5 && (ts.endsWith("+0530") || ts.endsWith("+05:30"))) {
            ts = ts.substring(0, ts.length() - 5) + "+05:30";
        }
        return Instant.parse(ts);
    }

    private static String postKiteForm(String urlStr, String formData) throws Exception {
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
        try (var br = new BufferedReader(new java.io.InputStreamReader(
            code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { conn.disconnect(); }
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

        String totp = generateTotpManual(KITE_TOTP_SECRET);
        String twoFaFormData = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&request_id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
            + "&twofa_value=" + URLEncoder.encode(totp, StandardCharsets.UTF_8)
            + "&twofa_type=totp&skip_session=";
        String twoFaResponse = postKiteForm("https://kite.zerodha.com/api/twofa", twoFaFormData);
        JsonNode twoFaJson = mapper.readTree(twoFaResponse);
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText())) {
            throw new IllegalStateException("Kite step 2 2FA failed: " + twoFaJson.path("message").asText());
        }

        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + KITE_API_KEY;
        String requestToken = extractRequestToken(connectUrl);
        String checksum = DigestUtils.sha256Hex(KITE_API_KEY + requestToken + KITE_API_SECRET);
        String tokenBody = "api_key=" + URLEncoder.encode(KITE_API_KEY, StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);
        String tokenResponse = postKiteForm("https://api.kite.trade/session/token", tokenBody);
        return mapper.readTree(tokenResponse).path("data").path("access_token").asText();
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
                    for (String p : location.substring(location.indexOf('?') + 1).split("&")) {
                        if (p.startsWith("request_token=")) return p.substring("request_token=".length());
                    }
                }
                currentUrl = location;
            } else break;
        }
        return null;
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
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(".env", StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\r", "").trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).split("#")[0].trim().replace("\"", "").replace("'", "");
                    if (key.equalsIgnoreCase("KITE_API_KEY")) KITE_API_KEY = value;
                    else if (key.equalsIgnoreCase("KITE_API_SECRET")) KITE_API_SECRET = value;
                    else if (key.equalsIgnoreCase("KITE_USER_ID")) KITE_USER_ID = value;
                    else if (key.equalsIgnoreCase("KITE_PASSWORD")) KITE_PASSWORD = value;
                    else if (key.equalsIgnoreCase("KITE_TOTP_SECRET")) KITE_TOTP_SECRET = value;
                }
            }
            System.out.printf("  Loaded KITE_USER_ID=%s, KITE_PASSWORD=%s, KITE_API_KEY=%s%n",
                KITE_USER_ID, KITE_PASSWORD != null ? "***" : "null", KITE_API_KEY != null ? KITE_API_KEY.substring(0, Math.min(4, KITE_API_KEY.length())) + "..." : "null");
        } catch (Exception e) { System.err.println("Could not load .env: " + e.getMessage()); }
    }
}
