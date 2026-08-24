package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.shoonya.ShoonyaAuthenticator;
import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import com.tradingbot.instrument.LotSizeService;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.resilience.BrokerBulkheadManager;
import com.tradingbot.model.Candle;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.impl.IntradayTrendMomentumOptionSellingStrategy;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;
import com.tradingbot.strategy.impl.NiftyVwapMomentumReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.web.reactive.function.client.WebClient;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Consolidated backtest runner for ALL THREE intraday strategies (the code modified in the
 * latest iteration) over the last ~1 month of REAL market data.
 *
 * Data sources (both attempted, as requested):
 *   - Kite Connect  : primary source. Fetches NIFTY FUT 5m/15m/1h and a basket of F&O stock 5m.
 *   - Shoonya       : best-effort. Uses the project's ShoonyaAuthenticator + ShoonyaHistoricalDataService
 *                     to pull NIFTY FUT 5m. If the Shoonya API is IP-whitelist blocked from this host
 *                     (INVALID_IP), it is reported and Kite data is used for the backtest.
 *
 * Run with: ./gradlew backtestAllIntraday
 */
public class IntradayAllBacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Hysteresis band (underlying points) applied to VWAP-cross entry/exit + bias, to make
     *  the strategy robust to tiny broker-feed OHLC differences. */
    private static final double VWAP_TRIGGER_TOLERANCE = 3.0;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    // Kite credentials
    private static String KITE_API_KEY;
    private static String KITE_API_SECRET;
    private static String KITE_USER_ID;
    private static String KITE_PASSWORD;
    private static String KITE_TOTP_SECRET;

    // Shoonya credentials
    private static String SHOONYA_CLIENT_ID;
    private static String SHOONYA_SECRET_KEY;
    private static String SHOONYA_USER_ID;
    private static String SHOONYA_ACCOUNT_ID;
    private static String SHOONYA_PASSWORD;
    private static String SHOONYA_TOTP_SECRET;
    private static String SHOONYA_VENDOR_CODE;
    private static String SHOONYA_API_KEY;

    // F&O stock basket for the Lowest Volume Reversal strategy
    private static final String[] STOCK_NAMES = {
        "RELIANCE", "TCS", "INFY", "HDFCBANK", "ICICIBANK", "HDFC", "WIPRO",
        "BHARTIARTL", "ITC", "KOTAKBANK", "AXISBANK", "MARUTI", "TATAMOTORS", "SUNPHARMA", "TITAN"
    };
    private static final String[] STOCK_CANONICAL = {
        "NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK", "NSE:ICICIBANK", "NSE:HDFC", "NSE:WIPRO",
        "NSE:BHARTIARTL", "NSE:ITC", "NSE:KOTAKBANK", "NSE:AXISBANK", "NSE:MARUTI", "NSE:TATAMOTORS", "NSE:SUNPHARMA", "NSE:TITAN"
    };

    public static void main(String[] args) throws Exception {
        loadEnv();
        System.out.println("=".repeat(90));
        System.out.println("  ALL-INTRAday STRATEGIES BACKTEST (1 MONTH REAL DATA) - MODIFIED CODE");
        System.out.println("=".repeat(90));

        int daysBack = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        LocalDate toDate = LocalDate.now(IST);
        LocalDate fromDate = toDate.minusDays(daysBack);
        System.out.printf("%nPeriod: %s to %s (%d days) | Source: Kite (primary), Shoonya (best-effort)%n",
            fromDate, toDate, daysBack);

        // ---- Kite auth ----
        System.out.println("\n[1/5] Authenticating with Kite Connect...");
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);
        String kiteToken = executeKiteHeadlessLogin();
        System.out.println("  -> Kite authentication successful");

        // ---- Discover tokens ----
        System.out.println("\n[2/5] Discovering instrument tokens...");
        String niftyFutToken = searchNiftyFuturesToken(kiteToken);
        if (niftyFutToken == null) {
            System.err.println("ERROR: Could not resolve NIFTY FUT token. Aborting.");
            return;
        }
        System.out.printf("  -> NIFTY FUT token: %s%n", niftyFutToken);

        Map<String, String> stockTokens = new LinkedHashMap<>();
        for (int i = 0; i < STOCK_NAMES.length; i++) {
            String t = searchKiteInstrument(kiteToken, STOCK_NAMES[i]);
            if (t != null) stockTokens.put(STOCK_CANONICAL[i], t);
            System.out.printf("  -> %s: %s%n", STOCK_CANONICAL[i], t != null ? t : "NOT FOUND");
            Thread.sleep(350);
        }

        // ---- Fetch data ----
        System.out.println("\n[3/5] Fetching 1-month historical candles from Kite...");
        List<Candle> nifty5m = fetchKite(kiteToken, niftyFutToken, "5minute", fromDate, toDate, "NIFTY_FUT");
        List<Candle> nifty15m = fetchKite(kiteToken, niftyFutToken, "15minute", fromDate, toDate, "NIFTY_FUT");
        List<Candle> nifty1h = fetchKite(kiteToken, niftyFutToken, "60minute", fromDate, toDate, "NIFTY_FUT");
        System.out.printf("  -> NIFTY_FUT 5m=%d, 15m=%d, 1h=%d%n", nifty5m.size(), nifty15m.size(), nifty1h.size());

        Map<String, List<Candle>> stockCandles = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : stockTokens.entrySet()) {
            List<Candle> c = fetchKite(kiteToken, e.getValue(), "5minute", fromDate, toDate, e.getKey());
            if (!c.isEmpty()) stockCandles.put(e.getKey(), c);
            System.out.printf("  -> %s: %d 5m candles%n", e.getKey(), c.size());
            Thread.sleep(350);
        }

        // ---- Run backtests (modified code) ----
        System.out.println("\n[4/5] Running backtests on MODIFIED strategies...");
        BacktestEngine engine = new BacktestEngine();
        BigDecimal capital = BigDecimal.valueOf(100000);

        // (A) Lowest Volume Reversal - per stock
        runLvr(engine, capital, stockCandles);

        // (B) Nifty VWAP Momentum Reversal - per day with snapshot/PCR simulation
        Map<LocalDate, BigDecimal> kiteVwapPnl = new LinkedHashMap<>();
        if (!nifty5m.isEmpty()) kiteVwapPnl = runVwap(engine, capital, nifty5m);

        // (C) Intraday Trend & Momentum Option Selling - 15m + 1h combined
        if (!nifty15m.isEmpty()) runIntradayTrend(engine, capital, nifty15m, nifty1h);

        // ---- Shoonya best-effort attempt ----
        System.out.println("\n[5/5] Best-effort Shoonya historical data attempt...");
        Map<LocalDate, BigDecimal> shoonyaVwapPnl = attemptShoonya(fromDate, toDate);

        // ---- Cross-broker per-day P&L comparison (Kite vs Shoonya) ----
        if (!shoonyaVwapPnl.isEmpty()) {
            printVwapSideBySide(kiteVwapPnl, shoonyaVwapPnl);
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  ALL BACKTESTS COMPLETE");
        System.out.println("=".repeat(90));
    }

    // ========== (A) Lowest Volume Reversal ==========

    private static void runLvr(BacktestEngine engine, BigDecimal capital, Map<String, List<Candle>> stockCandles) {
        System.out.println("\n" + "-".repeat(90));
        System.out.println("  STRATEGY 1: LOWEST VOLUME REVERSAL (modified: last-traded-price hard exit, MARKET/MIS exits)");
        System.out.println("-".repeat(90));

        NseIndiaClient noOpNse = new NseIndiaClient(WebClient.builder(), new ObjectMapper());
        LotSizeService mockLot = new LotSizeService(null, null, WebClient.builder()) {
            @Override public int getLotSize(String s) { return 250; }
            @Override public int getOrderQuantity(String s) { return 500; }
        };

        int totalTrades = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (Map.Entry<String, List<Candle>> e : stockCandles.entrySet()) {
            LowestVolumeReversalStrategy s = new LowestVolumeReversalStrategy(
                "LVR_" + e.getKey().replace("NSE:", ""), "BACKTEST_ACCOUNT", e.getKey(),
                2, 2.0, 2, noOpNse, mockLot);
            try {
                BacktestResult r = engine.run(s, e.getValue(), capital);
                totalTrades += r.totalTrades();
                totalPnl = totalPnl.add(r.netPnL());
                System.out.printf("  %-14s | Trades: %3d | P&L: %11.2f | WinRate: %5.1f%%%n",
                    e.getKey(), r.totalTrades(), r.netPnL(), r.winRatePercent());
            } catch (Exception ex) {
                System.out.printf("  %-14s | ERROR: %s%n", e.getKey(), ex.getMessage());
            }
        }
        System.out.printf("  %-14s | Total Trades: %3d | Net P&L: %11.2f%n", "LVR SUMMARY", totalTrades, totalPnl);
    }

    // ========== (B) Nifty VWAP Momentum Reversal ==========

    private static Map<LocalDate, BigDecimal> runVwap(BacktestEngine engine, BigDecimal capital, List<Candle> nifty5m) {
        System.out.println("\n" + "-".repeat(90));
        System.out.println("  STRATEGY 2: NIFTY VWAP MOMENTUM REVERSAL (modified: stop-loss ALWAYS ON after grace, bias fallback)");
        System.out.println("-".repeat(90));

        Map<LocalDate, List<Candle>> byDay = splitIntoTradingDays(nifty5m);
        System.out.printf("  -> %d trading days (trigger tolerance = %.1f pts)%n", byDay.size(), VWAP_TRIGGER_TOLERANCE);

        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0, totalWins = 0, totalLosses = 0;
        Map<LocalDate, BigDecimal> dayPnl = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<Candle>> e : byDay.entrySet()) {
            NiftyVwapMomentumReversalStrategy s = new NiftyVwapMomentumReversalStrategy(
                "VWAP_" + e.getKey(), "BACKTEST_ACCOUNT", "NIFTY_FUT", VWAP_TRIGGER_TOLERANCE);
            simulateSnapshotsAndPcr(s, e.getValue(), e.getKey());
            BacktestResult r = engine.run(s, e.getValue(), capital);
            totalPnl = totalPnl.add(r.netPnL());
            totalTrades += r.totalTrades();
            totalWins += r.winningTrades();
            totalLosses += r.losingTrades();
            dayPnl.put(e.getKey(), r.netPnL());
            String sign = r.netPnL().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            System.out.printf("  %s | Trades: %2d | P&L: %s%10.2f%n",
                e.getKey(), r.totalTrades(), sign, r.netPnL());
        }
        System.out.printf("  VWAP SUMMARY | Trading Days: %d | Trades: %d | Wins: %d | Losses: %d | Net P&L: %.2f%n",
            byDay.size(), totalTrades, totalWins, totalLosses, totalPnl);
        return dayPnl;
    }

    private static void printVwapSideBySide(Map<LocalDate, BigDecimal> kite,
                                            Map<LocalDate, BigDecimal> shoonya) {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  CROSS-BROKER VWAP P&L (Kite vs Shoonya) — same modified code, same window");
        System.out.println("=".repeat(90));
        System.out.printf("  %-12s | %14s | %14s | %14s%n", "Date", "Kite P&L", "Shoonya P&L", "Diff");
        System.out.println("  " + "-".repeat(86));
        BigDecimal kTot = BigDecimal.ZERO, sTot = BigDecimal.ZERO, dTot = BigDecimal.ZERO;
        int kDays = 0, sDays = 0, matchingDays = 0;
        Set<LocalDate> all = new TreeSet<>();
        all.addAll(kite.keySet());
        all.addAll(shoonya.keySet());
        for (LocalDate d : all) {
            BigDecimal k = kite.getOrDefault(d, null);
            BigDecimal s = shoonya.getOrDefault(d, null);
            if (k != null) { kTot = kTot.add(k); kDays++; }
            if (s != null) { sTot = sTot.add(s); sDays++; }
            if (k != null && s != null) {
                BigDecimal diff = k.subtract(s);
                dTot = dTot.add(diff);
                matchingDays++;
                System.out.printf("  %-12s | %+14.2f | %+14.2f | %+14.2f%n", d, k, s, diff);
            } else {
                String kStr = k != null ? String.format("%+14.2f", k) : "            n/a";
                String sStr = s != null ? String.format("%+14.2f", s) : "            n/a";
                String dStr = (k != null && s != null) ? String.format("%+14.2f", k.subtract(s)) : "            n/a";
                System.out.printf("  %-12s | %14s | %14s | %14s%n", d, kStr, sStr, dStr);
            }
        }
        System.out.println("  " + "-".repeat(86));
        System.out.printf("  %-12s | %+14.2f | %+14.2f | %+14.2f%n", "TOTAL", kTot, sTot, dTot);
        System.out.printf("  (Kite: %d days | Shoonya: %d days | %d matching days)%n", kDays, sDays, matchingDays);
        double avgAbsDiff = matchingDays > 0 ? dTot.abs().doubleValue() / matchingDays : 0;
        System.out.printf("  Avg |Kite-Shoonya| per matching day: %.2f (lower = more feed-robust)%n", avgAbsDiff);
    }

    // ========== (C) Intraday Trend & Momentum Option Selling ==========

    private static void runIntradayTrend(BacktestEngine engine, BigDecimal capital, List<Candle> nifty15m, List<Candle> nifty1h) {
        System.out.println("\n" + "-".repeat(90));
        System.out.println("  STRATEGY 3: INTRADAY TREND & MOMENTUM OPTION SELLING (modified: live premium refresh re-entry, EOD 15:00)");
        System.out.println("-".repeat(90));

        List<Candle> all = new ArrayList<>(nifty15m);
        if (!nifty1h.isEmpty()) all.addAll(nifty1h);
        all.sort(Comparator.comparing(Candle::timestamp));

        IntradayTrendMomentumOptionSellingStrategy s = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_INTRADAY_ALL", "BACKTEST_ACCOUNT", "NIFTY_FUT");
        BacktestResult r = engine.run(s, all, capital);
        System.out.printf("  Period: %s to %s%n",
            all.get(0).timestamp().atZone(IST).toLocalDate(),
            all.get(all.size() - 1).timestamp().atZone(IST).toLocalDate());
        System.out.printf("  Candles: %d | Trades: %d | Wins: %d | Losses: %d%n",
            all.size(), r.totalTrades(), r.winningTrades(), r.losingTrades());
        System.out.printf("  Win Rate: %.1f%% | Net P&L: %.2f | Profit Factor: %.2f | Max DD: %.2f (%.1f%%)%n",
            r.winRatePercent(), r.netPnL(), r.profitFactor(), r.maxDrawdown(), r.maxDrawdownPercent());
        if (r.trades() != null && !r.trades().isEmpty()) {
            System.out.println("  TRADES:");
            for (BacktestTrade t : r.trades()) {
                System.out.printf("    %s | %s %s | Entry: %.2f -> Exit: %.2f | Qty: %d | P&L: %.2f | %s%n",
                    t.entryTime().atZone(IST).toLocalDate(), t.direction(), t.symbol(),
                    t.entryPrice(), t.exitPrice(), t.quantity(), t.pnl(), t.exitTag());
            }
        } else {
            System.out.println("  No trades executed during the period.");
        }
    }

    // ========== Shoonya best-effort ==========

    private static Map<LocalDate, BigDecimal> attemptShoonya(LocalDate fromDate, LocalDate toDate) {
        try {
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

            ShoonyaAuthenticator auth = new ShoonyaAuthenticator(cfg, WebClient.builder(), new ObjectMapper());
            System.out.println("  -> Attempting Shoonya login...");
            String token = auth.authenticate().block();
            if (token == null || token.isBlank()) {
                System.out.println("  -> Shoonya login did not return a token. Skipping Shoonya data.");
                return Map.of();
            }
            System.out.println("  -> Shoonya login OK. Attempting NIFTY FUT 5m TPSeries...");

            String userKey = auth.getSUserToken() != null ? auth.getSUserToken() : token;
            String shoonyaToken = searchShoonyaNiftyFutToken(userKey);
            if (shoonyaToken == null) {
                System.out.println("  -> Could not resolve Shoonya NIFTY FUT token. Skipping Shoonya backtest.");
                return Map.of();
            }
            // Fetch TPSeries directly (jKey-only, matching the working SearchScrip auth) to
            // avoid the ShoonyaHistoricalDataService 401 (spurious Authorization header).
            List<Candle> shoonyaCandles = fetchShoonyaTpseries(userKey, "NFO", shoonyaToken, "5", fromDate, toDate);
            if (shoonyaCandles == null || shoonyaCandles.isEmpty()) {
                System.out.println("  -> Shoonya returned no candles (likely IP-whitelist blocked). Kite data used for backtest.");
                return Map.of();
            }
            System.out.printf("  -> Shoonya returned %d NIFTY_FUT 5m candles.%n", shoonyaCandles.size());

            // Cross-check: run VWAP on Shoonya data too (same modified code + same tolerance)
            Map<LocalDate, List<Candle>> byDay = splitIntoTradingDays(shoonyaCandles);
            BacktestEngine engine = new BacktestEngine();
            BigDecimal cap = BigDecimal.valueOf(100000);
            BigDecimal pnl = BigDecimal.ZERO;
            int trades = 0;
            Map<LocalDate, BigDecimal> shoonyaDayPnl = new LinkedHashMap<>();
            for (Map.Entry<LocalDate, List<Candle>> d : byDay.entrySet()) {
                NiftyVwapMomentumReversalStrategy s = new NiftyVwapMomentumReversalStrategy(
                    "VWAP_SHOONYA_" + d.getKey(), "BACKTEST_ACCOUNT", "NIFTY_FUT", VWAP_TRIGGER_TOLERANCE);
                simulateSnapshotsAndPcr(s, d.getValue(), d.getKey());
                BacktestResult r = engine.run(s, d.getValue(), cap);
                pnl = pnl.add(r.netPnL());
                trades += r.totalTrades();
                shoonyaDayPnl.put(d.getKey(), r.netPnL());
            }
            System.out.printf("  -> Shoonya VWAP backtest: Trades=%d | Net P&L=%.2f%n", trades, pnl);
            return shoonyaDayPnl;
        } catch (Exception e) {
            System.out.printf("  -> Shoonya attempt failed: %s%n", e.getMessage());
            System.out.println("     (Kite data was used for the backtest above.)");
            return Map.of();
        }
    }

    // ========== Snapshot & PCR simulation (VWAP) ==========

    private static double nseBasePcr = 1.0;

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
                if (nifty930 > 0) {
                    double priceMove = (close - nifty930) / nifty930;
                    pcr930 = nseBasePcr;
                    pcr1100 = nseBasePcr * (1 + priceMove * 3);
                }
            }
        }
        if (nifty930 == 0 && !dayCandles.isEmpty()) nifty930 = dayCandles.get(0).close().doubleValue();
        if (nifty1100 == 0 && dayCandles.size() > 6) nifty1100 = dayCandles.get(6).close().doubleValue();

        strategy.setBaseline930(nifty930, pcr930);
        strategy.setBaseline1100(nifty1100, pcr1100);
    }

    // ========== Day splitting ==========

    private static Map<LocalDate, List<Candle>> splitIntoTradingDays(List<Candle> candles) {
        Map<LocalDate, List<Candle>> byDay = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate day = c.timestamp().atZone(IST).toLocalDate();
            if (day.getDayOfWeek().getValue() >= 6) continue;
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(c);
        }
        return byDay;
    }

    // ========== Kite fetch helpers ==========

    private static List<Candle> fetchKite(String accessToken, String token, String interval,
                                          LocalDate from, LocalDate to, String symbol) {
        try {
            String url = String.format("https://api.kite.trade/instruments/historical/%s/%s?from=%s&to=%s",
                token, interval, from.format(DateTimeFormatter.ISO_LOCAL_DATE), to.format(DateTimeFormatter.ISO_LOCAL_DATE));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
                .header("X-Kite-Version", "3")
                .GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String tf = interval.replace("minute", "");
            return parseKiteCandles(res.body(), symbol, tf);
        } catch (Exception e) {
            System.out.printf("    WARNING: Kite fetch failed (%s): %s%n", interval, e.getMessage());
            return List.of();
        }
    }

    private static List<Candle> parseKiteCandles(String body, String symbol, String tf) {
        List<Candle> out = new ArrayList<>();
        try {
            JsonNode json = mapper.readTree(body);
            JsonNode data = json.path("data").path("candles");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    if (n.isArray() && n.size() >= 6) {
                        Instant ts = parseKiteTs(n.get(0).asText());
                        out.add(new Candle(symbol, tf, ts,
                            new BigDecimal(n.get(1).asText()),
                            new BigDecimal(n.get(2).asText()),
                            new BigDecimal(n.get(3).asText()),
                            new BigDecimal(n.get(4).asText()),
                            n.get(5).asLong()));
                    }
                }
            }
        } catch (Exception e) {
            System.out.printf("    WARNING: parse error: %s%n", e.getMessage());
        }
        return out;
    }

    private static Instant parseKiteTs(String ts) {
        try {
            if (ts.endsWith("+0530") || ts.endsWith("+05:30")) ts = ts.substring(0, ts.length() - 5) + "+05:30";
            else if (ts.matches(".*[+-]\\d{4}$")) ts = ts.substring(0, ts.length() - 2) + ":" + ts.substring(ts.length() - 2);
            return Instant.parse(ts);
        } catch (Exception e) {
            try {
                String clean = ts.replaceAll("[+-]\\d{4}$", "");
                LocalDateTime ldt = LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private static String searchNiftyFuturesToken(String accessToken) throws Exception {
        String csv = downloadInstruments(accessToken);
        String[] lines = csv.split("\n");
        String[] headers = lines[0].split(",");
        int iTok = -1, iEx = -1, iNm = -1, iTy = -1, iExp = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().replace("\"", "").toLowerCase();
            if (h.equals("instrument_token")) iTok = i;
            else if (h.equals("exchange")) iEx = i;
            else if (h.equals("name")) iNm = i;
            else if (h.equals("instrument_type")) iTy = i;
            else if (h.equals("expiry")) iExp = i;
        }
        String best = null, bestExp = null;
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split(",");
            if (c.length <= Math.max(iTok, Math.max(iEx, iNm))) continue;
            String ex = iEx >= 0 ? c[iEx].trim().replace("\"", "") : "";
            String nm = iNm >= 0 ? c[iNm].trim().replace("\"", "") : "";
            String ty = iTy >= 0 ? c[iTy].trim().replace("\"", "") : "";
            String exp = iExp >= 0 ? c[iExp].trim().replace("\"", "") : "";
            String tk = iTok >= 0 ? c[iTok].trim().replace("\"", "") : "";
            if ("NFO".equals(ex) && "FUT".equals(ty) && "NIFTY".equals(nm) && !exp.isEmpty()) {
                if (bestExp == null || exp.compareTo(bestExp) < 0) { bestExp = exp; best = tk; }
            }
        }
        return best;
    }

    private static String searchKiteInstrument(String accessToken, String query) throws Exception {
        String csv = downloadInstruments(accessToken);
        String[] lines = csv.split("\n");
        String[] headers = lines[0].split(",");
        int iTok = -1, iEx = -1, iNm = -1, iTs = -1, iTy = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("instrument_token")) iTok = i;
            else if (h.equals("exchange")) iEx = i;
            else if (h.equals("name")) iNm = i;
            else if (h.equals("tradingsymbol")) iTs = i;
            else if (h.equals("instrument_type")) iTy = i;
        }
        String best = null;
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split(",");
            if (c.length <= iTok) continue;
            String ex = iEx >= 0 ? c[iEx].trim() : "";
            String nm = iNm >= 0 ? c[iNm].trim() : "";
            String ts = iTs >= 0 ? c[iTs].trim() : "";
            String ty = iTy >= 0 ? c[iTy].trim() : "";
            if (!"NSE".equals(ex)) continue;
            if (query.equalsIgnoreCase(nm) || query.equalsIgnoreCase(ts)) {
                if ("EQ".equals(ty) || ty.isEmpty() || query.equalsIgnoreCase(nm)) {
                    best = c[iTok].trim();
                    if (query.equalsIgnoreCase(nm) && ty.isEmpty()) return best;
                }
            }
        }
        return best;
    }

    private static String downloadInstruments(String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.kite.trade/instruments"))
            .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
            .header("X-Kite-Version", "3")
            .GET().build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ========== Shoonya instrument lookup (best-effort) ==========

    private static List<Candle> fetchShoonyaTpseries(String userKey, String exchange, String token,
                                                     String timeframe, LocalDate fromDate, LocalDate toDate) {
        try {
            // Align exactly with the Kite window (IST) for an apples-to-apples comparison.
            long startEpoch = fromDate.atStartOfDay(IST).toEpochSecond();
            long endEpoch = toDate.plusDays(1).atStartOfDay(IST).toEpochSecond();
            Map<String, Object> jData = Map.of(
                "uid", SHOONYA_USER_ID, "exch", exchange, "token", token,
                "st", String.valueOf(startEpoch), "et", String.valueOf(endEpoch), "intrv", timeframe);
            String body = "jData=" + mapper.writeValueAsString(jData) + "&jKey=" + userKey;
            WebClient wc = WebClient.builder()
                .baseUrl("https://api.shoonya.com")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
            String resp = wc.post()
                .uri("/NorenWClientAPI/TPSeries")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            JsonNode root = mapper.readTree(resp);
            List<Candle> candles = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    if (!"Ok".equalsIgnoreCase(n.path("stat").asText("Ok")) && n.has("into")) continue;
                    if (!n.has("into")) continue;
                    BigDecimal open = new BigDecimal(n.path("into").asText("0"));
                    BigDecimal high = new BigDecimal(n.path("inth").asText("0"));
                    BigDecimal low = new BigDecimal(n.path("intl").asText("0"));
                    BigDecimal close = new BigDecimal(n.path("intc").asText("0"));
                    long vol = n.path("v").asLong(0);
                    Instant ts = n.has("ssboe") && n.path("ssboe").asLong() > 0
                        ? Instant.ofEpochSecond(n.path("ssboe").asLong())
                        : Instant.now();
                    candles.add(new Candle("NIFTY_FUT", timeframe, ts, open, high, low, close, vol));
                }
            } else if (root.isObject() && "Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
                System.out.printf("    (Shoonya TPSeries error: %s)%n", root.path("emsg").asText());
            }
            candles.sort(Comparator.comparing(Candle::timestamp));
            return candles;
        } catch (Exception e) {
            System.out.printf("    (Shoonya TPSeries fetch failed: %s)%n", e.getMessage());
            return List.of();
        }
    }

    private static String searchShoonyaNiftyFutToken(String sessionToken) {
        try {
            // Shoonya exposes symbol search via the NorenAPI SearchScrip endpoint.
            WebClient wc = WebClient.builder().baseUrl("https://api.shoonya.com").build();
            String jData = mapper.writeValueAsString(Map.of(
                "uid", SHOONYA_USER_ID, "exch", "NFO", "stext", "NIFTY"));
            String body = "jData=" + jData + "&jKey=" + sessionToken;
            String resp = wc.post()
                .uri("/NorenWClientAPI/SearchScrip")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            System.out.printf("    (SearchScrip raw: %s)%n", resp.length() > 300 ? resp.substring(0, 300) + "..." : resp);
            JsonNode root = mapper.readTree(resp);
            JsonNode values = root.path("values");
            if (values.isArray()) {
                String bestToken = null, bestSym = null;
                for (JsonNode n : values) {
                    String tsym = n.path("tsym").asText("");
                    String tk = n.path("token").asText("");
                    // Standard Nifty futures: e.g. NIFTY25AUG26F (exclude FIN/NXT50 variants)
                    if (tsym.startsWith("NIFTY") && tsym.endsWith("F")
                        && !tsym.contains("FPI") && !tsym.contains("NXT")) {
                        if (bestToken == null) { bestToken = tk; bestSym = tsym; }
                    }
                }
                if (bestToken != null) {
                    System.out.printf("    (Shoonya NIFTY FUT: %s token=%s)%n", bestSym, bestToken);
                    return bestToken;
                }
            }
        } catch (Exception e) {
            System.out.printf("    (Shoonya SearchScrip lookup failed: %s)%n", e.getMessage());
        }
        return null;
    }

    // ========== Kite headless login ==========

    private static String executeKiteHeadlessLogin() throws Exception {
        String loginForm = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(KITE_PASSWORD, StandardCharsets.UTF_8);
        JsonNode loginJson = mapper.readTree(postKiteForm("https://kite.zerodha.com/api/login", loginForm));
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText()))
            throw new IllegalStateException("Kite login failed: " + loginJson.path("message").asText());
        String requestId = loginJson.path("data").path("request_id").asText();

        String totp = generateTotp(KITE_TOTP_SECRET);
        String twoFa = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&request_id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
            + "&twofa_value=" + URLEncoder.encode(totp, StandardCharsets.UTF_8)
            + "&twofa_type=totp&skip_session=";
        JsonNode twoFaJson = mapper.readTree(postKiteForm("https://kite.zerodha.com/api/twofa", twoFa));
        if (!"success".equalsIgnoreCase(twoFaJson.path("status").asText()))
            throw new IllegalStateException("Kite 2FA failed: " + twoFaJson.path("message").asText());

        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + KITE_API_KEY;
        String requestToken = extractRequestToken(connectUrl);
        if (requestToken == null || requestToken.isBlank())
            throw new IllegalStateException("Failed to capture Kite request_token");

        String checksum = DigestUtils.sha256Hex(KITE_API_KEY + requestToken + KITE_API_SECRET);
        String tokenForm = "api_key=" + URLEncoder.encode(KITE_API_KEY, StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);
        JsonNode tokenJson = mapper.readTree(postKiteForm("https://api.kite.trade/session/token", tokenForm));
        if (!"success".equalsIgnoreCase(tokenJson.path("status").asText()))
            throw new IllegalStateException("Kite token exchange failed: " + tokenJson.path("message").asText());
        return tokenJson.path("data").path("access_token").asText();
    }

    private static String extractRequestToken(String targetUrl) throws Exception {
        String current = targetUrl;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URI(current).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();
            int status = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc != null && !loc.isBlank()) {
                if (loc.contains("request_token=")) return parseQp(loc, "request_token");
                current = loc;
            } else break;
        }
        return null;
    }

    private static String parseQp(String url, String param) {
        try {
            URI uri = new URI(url);
            String q = uri.getQuery();
            if (q != null) for (String p : q.split("&")) {
                String[] kv = p.split("=", 2);
                if (kv.length == 2 && kv[0].equals(param))
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
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

    private static String generateTotp(String secret) throws Exception {
        byte[] key = base32Decode(secret);
        long time = System.currentTimeMillis() / 1000L / 30L;
        byte[] tb = new byte[8];
        long t = time;
        for (int i = 7; i >= 0; i--) { tb[i] = (byte) (t & 0xFF); t >>= 8; }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(tb);
        int off = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[off] & 0x7F) << 24) | ((hash[off + 1] & 0xFF) << 16)
            | ((hash[off + 2] & 0xFF) << 8) | (hash[off + 3] & 0xFF);
        return String.format("%06d", binary % 1000000);
    }

    private static byte[] base32Decode(String encoded) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        encoded = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] dec = new byte[encoded.length() * 5 / 8];
        int buf = 0, bits = 0, cnt = 0;
        for (char c : encoded.toCharArray()) {
            int v = a.indexOf(c);
            if (v < 0) continue;
            buf = (buf << 5) | v; bits += 5;
            if (bits >= 8) { dec[cnt++] = (byte) (buf >> (bits - 8)); bits -= 8; }
        }
        if (cnt < dec.length) { byte[] r = new byte[cnt]; System.arraycopy(dec, 0, r, 0, cnt); return r; }
        return dec;
    }

    // ========== .env loader ==========

    private static void loadEnv() {
        try (var r = new BufferedReader(new FileReader(".env", StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    switch (k) {
                        case "KITE_API_KEY" -> KITE_API_KEY = v;
                        case "KITE_API_SECRET" -> KITE_API_SECRET = v;
                        case "KITE_USER_ID" -> KITE_USER_ID = v;
                        case "KITE_PASSWORD" -> KITE_PASSWORD = v;
                        case "KITE_TOTP_SECRET" -> KITE_TOTP_SECRET = v;
                        case "SHOONYA_CLIENT_ID" -> SHOONYA_CLIENT_ID = v;
                        case "SHOONYA_SECRET_KEY" -> SHOONYA_SECRET_KEY = v;
                        case "SHOONYA_USER_ID" -> SHOONYA_USER_ID = v;
                        case "SHOONYA_ACCOUNT_ID" -> SHOONYA_ACCOUNT_ID = v;
                        case "SHOONYA_PASSWORD" -> SHOONYA_PASSWORD = v;
                        case "SHOONYA_TOTP_SECRET" -> SHOONYA_TOTP_SECRET = v;
                        case "SHOONYA_VENDOR_CODE" -> SHOONYA_VENDOR_CODE = v;
                        case "SHOONYA_API_KEY" -> SHOONYA_API_KEY = v;
                    }
                }
            }
            System.out.println("  -> Loaded .env configuration");
        } catch (Exception e) {
            System.err.println("WARNING: Could not load .env: " + e.getMessage());
        }
    }
}
