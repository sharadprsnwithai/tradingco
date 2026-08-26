package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.impl.IntradayTrendMomentumOptionSellingStrategy;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;
import com.tradingbot.strategy.impl.NiftyVwapMomentumReversalStrategy;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consolidated 1-Month Backtest Runner for ALL THREE Intraday Strategies
 * using real historical market data from Shoonya (Finvasia NorenAPI).
 *
 * Strategies evaluated:
 *   1. Lowest Volume Reversal Strategy (F&O Stock basket: RELIANCE, TCS, INFY, HDFCBANK, ICICIBANK, SBIN - 5m)
 *   2. Nifty VWAP Momentum Reversal Strategy (NIFTY 50 - 5m with daily VWAP, 9:30 & 11:00 snapshots + PCR)
 *   3. Intraday Trend & Momentum Option Selling Strategy (NIFTY 50 - 15m & 60m with SuperTrend + RSI + MACD / live premium refresh)
 *
 * Run with: ./gradlew shoonyaBacktest
 */
public class ShoonyaBacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int[] KEY_OFFSETS = {83, 50, 97, 114, 110, 46, 27, 93};
    private static final double VWAP_TRIGGER_TOLERANCE = 3.0;

    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    // Shoonya credentials from .env
    private static String SHOONYA_USER_ID;
    private static String SHOONYA_ACCOUNT_ID;
    private static String SHOONYA_CLIENT_ID;
    private static String SHOONYA_SECRET_KEY;
    private static String SHOONYA_PASSWORD;
    private static String SHOONYA_TOTP_SECRET;
    private static String SHOONYA_API_KEY;
    private static String SHOONYA_VENDOR_CODE;

    private static String sUserToken;

    // F&O Stock Basket for Lowest Volume Reversal
    private static final String[][] STOCK_BASKET = {
        {"NSE:RELIANCE", "2885", "RELIANCE"},
        {"NSE:TCS", "11536", "TCS"},
        {"NSE:INFY", "1594", "INFY"},
        {"NSE:HDFCBANK", "1333", "HDFCBANK"},
        {"NSE:ICICIBANK", "4963", "ICICIBANK"},
        {"NSE:SBIN", "3045", "SBIN"}
    };

    private static final String NIFTY_TOKEN = "26000";
    private static final String NIFTY_SYMBOL = "NSE:NIFTY";

    public static void main(String[] args) throws Exception {
        loadEnv();

        System.out.println("=".repeat(92));
        System.out.println("   SHOONYA (FINVASIA NorenAPI) 1-MONTH BACKTEST - 3 INTRADAY STRATEGIES");
        System.out.println("=".repeat(92));

        // Step 1: Authenticate with Shoonya
        System.out.println("\n[1/5] Authenticating with Shoonya API...");
        authenticateShoonya();
        System.out.println("  -> Shoonya authentication successful (Session Token: " + sUserToken.substring(0, 8) + "...)");

        // Step 2: Date window setup (last 30 calendar days)
        int daysBack = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        LocalDate toDate = LocalDate.now(IST);
        LocalDate fromDate = toDate.minusDays(daysBack);
        long endEpoch = Instant.now().getEpochSecond();
        long startEpoch = endEpoch - (long) daysBack * 24 * 3600;

        System.out.printf("%n[2/5] Fetching 1-Month Historical Data from Shoonya (%s to %s, %d days)...%n",
            fromDate, toDate, daysBack);

        // Fetch NIFTY data (5m, 15m) and aggregate 60m
        System.out.println("  -> Fetching NIFTY 50 Index candles from Shoonya...");
        List<Candle> nifty5m = fetchShoonyaTPSeries("NSE", NIFTY_TOKEN, startEpoch, endEpoch, "5", NIFTY_SYMBOL);
        Thread.sleep(400);
        List<Candle> nifty15m = fetchShoonyaTPSeries("NSE", NIFTY_TOKEN, startEpoch, endEpoch, "15", NIFTY_SYMBOL);
        Thread.sleep(400);
        List<Candle> nifty60m = aggregateTo60m(nifty15m);

        System.out.printf("     NIFTY 50: 5m = %d candles, 15m = %d candles, 60m (aggregated) = %d candles%n",
            nifty5m.size(), nifty15m.size(), nifty60m.size());

        // Fetch Stock Basket 5m data
        System.out.println("  -> Fetching F&O Stock Basket 5m candles...");
        Map<String, List<Candle>> stockCandles = new LinkedHashMap<>();
        for (String[] stock : STOCK_BASKET) {
            String canonical = stock[0];
            String token = stock[1];
            List<Candle> candles = fetchShoonyaTPSeries("NSE", token, startEpoch, endEpoch, "5", canonical);
            if (!candles.isEmpty()) {
                stockCandles.put(canonical, candles);
                LocalDateTime first = LocalDateTime.ofInstant(candles.get(0).timestamp(), IST);
                LocalDateTime last = LocalDateTime.ofInstant(candles.get(candles.size() - 1).timestamp(), IST);
                System.out.printf("     %-16s (token=%-5s): %4d candles (%s to %s)%n",
                    canonical, token, candles.size(),
                    first.format(DateTimeFormatter.ofPattern("dd-MM HH:mm")),
                    last.format(DateTimeFormatter.ofPattern("dd-MM HH:mm")));
            } else {
                System.out.printf("     %-16s (token=%-5s): NO DATA%n", canonical, token);
            }
            Thread.sleep(400);
        }

        // Step 3: Run Strategy 1 - Lowest Volume Reversal Strategy
        System.out.println("\n[3/5] Executing Strategy 1: Lowest Volume Reversal...");
        BacktestEngine engine = new BacktestEngine();
        BigDecimal stockCapital = BigDecimal.valueOf(100000);
        List<BacktestResult> lvrResults = runLowestVolumeReversal(engine, stockCapital, stockCandles);

        // Step 4: Run Strategy 2 - Nifty VWAP Momentum Reversal Strategy
        System.out.println("\n[4/5] Executing Strategy 2: Nifty VWAP Momentum Reversal...");
        BigDecimal vwapCapital = BigDecimal.valueOf(100000);
        BacktestResult vwapSummary = runNiftyVwapStrategy(engine, vwapCapital, nifty5m);

        // Step 5: Run Strategy 3 - Intraday Trend & Momentum Option Selling Strategy
        System.out.println("\n[5/5] Executing Strategy 3: Intraday Trend & Momentum Option Selling...");
        BigDecimal trendCapital = BigDecimal.valueOf(100000);
        BacktestResult trendResult = runIntradayTrendMomentum(engine, trendCapital, nifty15m, nifty60m);

        // Final Consolidated Portfolio Report
        printConsolidatedReport(lvrResults, vwapSummary, trendResult);
    }

    // =========================================================================
    // Strategy 1: Lowest Volume Reversal
    // =========================================================================

    private static List<BacktestResult> runLowestVolumeReversal(BacktestEngine engine,
                                                               BigDecimal capital,
                                                               Map<String, List<Candle>> stockCandles) {
        System.out.println("=".repeat(92));
        System.out.println("  STRATEGY 1: LOWEST VOLUME REVERSAL (F&O Stock Basket, 5m Timeframe)");
        System.out.println("=".repeat(92));

        NseIndiaClient noOpNseClient = new NseIndiaClient(
            org.springframework.web.reactive.function.client.WebClient.builder(), mapper
        );

        com.tradingbot.instrument.LotSizeService mockLotSizeService = new com.tradingbot.instrument.LotSizeService(
            null, null, org.springframework.web.reactive.function.client.WebClient.builder()
        ) {
            @Override public int getLotSize(String s) { return 250; }
            @Override public int getOrderQuantity(String s) { return 250; }
        };

        List<BacktestResult> results = new ArrayList<>();
        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0, totalWins = 0, totalLosses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO, grossLoss = BigDecimal.ZERO;

        for (Map.Entry<String, List<Candle>> entry : stockCandles.entrySet()) {
            String symbol = entry.getKey();
            List<Candle> candles = entry.getValue();

            LowestVolumeReversalStrategy strategy = new LowestVolumeReversalStrategy(
                "LVR_" + symbol.replace("NSE:", ""), "SHOONYA_ACCOUNT", symbol, 2, 2.0, 2, noOpNseClient, mockLotSizeService
            );

            try {
                BacktestResult result = engine.run(strategy, candles, capital);
                results.add(result);
                totalPnl = totalPnl.add(result.netPnL());
                totalTrades += result.totalTrades();
                totalWins += result.winningTrades();
                totalLosses += result.losingTrades();
                grossProfit = grossProfit.add(result.grossProfit());
                grossLoss = grossLoss.add(result.grossLoss());

                printResultRow(symbol, result);
            } catch (Exception e) {
                System.out.printf("  ERROR backtesting %s: %s%n", symbol, e.getMessage());
            }
        }

        double winRate = totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0.0;
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
            : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.9 : 1.0);

        System.out.println("  " + "-".repeat(88));
        System.out.printf("  %-16s | Trades: %3d | Win Rate: %5.1f%% (W:%2d L:%2d) | Gross Profit: %+10.2f | Gross Loss: %10.2f | Net P&L: %+10.2f%n",
            "LVR BASKET TOTAL", totalTrades, winRate, totalWins, totalLosses, grossProfit, grossLoss, totalPnl);
        System.out.printf("  Basket Profit Factor: %.2f%n", profitFactor);

        return results;
    }

    // =========================================================================
    // Strategy 2: Nifty VWAP Momentum Reversal
    // =========================================================================

    private static BacktestResult runNiftyVwapStrategy(BacktestEngine engine,
                                                      BigDecimal capital,
                                                      List<Candle> nifty5m) {
        System.out.println("=".repeat(92));
        System.out.println("  STRATEGY 2: NIFTY VWAP MOMENTUM REVERSAL (5m Timeframe, Daily Reset & Snapshots)");
        System.out.println("=".repeat(92));

        if (nifty5m.isEmpty()) {
            System.out.println("  No NIFTY 5m candles available for backtest.");
            return new BacktestResult("VWAP_NIFTY", capital, capital, BigDecimal.ZERO,
                0, 0, 0, 0.0, BigDecimal.ZERO, BigDecimal.ZERO, 1.0, BigDecimal.ZERO, 0.0, List.of());
        }

        Map<LocalDate, List<Candle>> candlesByDay = splitIntoTradingDays(nifty5m);
        System.out.printf("  Trading Days: %d | Total 5m Candles: %d%n%n", candlesByDay.size(), nifty5m.size());

        BigDecimal totalPnl = BigDecimal.ZERO;
        int totalTrades = 0, totalWins = 0, totalLosses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO, grossLoss = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<BacktestTrade> allTrades = new ArrayList<>();

        System.out.printf("  %-12s | %-6s | %-6s | %-6s | %-8s | %-12s | %-12s%n",
            "Date", "Trades", "Wins", "Losses", "WinRate", "Day P&L (Rs)", "Cum P&L (Rs)");
        System.out.println("  " + "-".repeat(78));

        for (Map.Entry<LocalDate, List<Candle>> entry : candlesByDay.entrySet()) {
            LocalDate day = entry.getKey();
            List<Candle> dayCandles = entry.getValue();

            NiftyVwapMomentumReversalStrategy strategy = new NiftyVwapMomentumReversalStrategy(
                "VWAP_" + day, "BACKTEST_ACCOUNT", NIFTY_SYMBOL, VWAP_TRIGGER_TOLERANCE
            );

            simulateSnapshotsAndPcr(strategy, dayCandles, day);

            BacktestResult dayResult = engine.run(strategy, dayCandles, capital);
            totalPnl = totalPnl.add(dayResult.netPnL());
            totalTrades += dayResult.totalTrades();
            totalWins += dayResult.winningTrades();
            totalLosses += dayResult.losingTrades();
            grossProfit = grossProfit.add(dayResult.grossProfit());
            grossLoss = grossLoss.add(dayResult.grossLoss());
            if (dayResult.maxDrawdown().compareTo(maxDrawdown) > 0) {
                maxDrawdown = dayResult.maxDrawdown();
            }
            allTrades.addAll(dayResult.trades());

            double dayWinRate = dayResult.totalTrades() > 0
                ? (dayResult.winningTrades() * 100.0 / dayResult.totalTrades()) : 0.0;

            System.out.printf("  %-12s | %6d | %6d | %6d | %7.1f%% | %+12.2f | %+12.2f%n",
                day, dayResult.totalTrades(), dayResult.winningTrades(), dayResult.losingTrades(),
                dayWinRate, dayResult.netPnL(), totalPnl);
        }

        double winRate = totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0.0;
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
            : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.9 : 1.0);

        System.out.println("  " + "-".repeat(78));
        System.out.printf("  %-12s | %6d | %6d | %6d | %7.1f%% | %+12.2f | Gross Profit: %.2f | Gross Loss: %.2f%n",
            "VWAP TOTAL", totalTrades, totalWins, totalLosses, winRate, totalPnl, grossProfit, grossLoss);
        System.out.printf("  Profit Factor: %.2f | Max Drawdown: Rs.%.2f%n", profitFactor, maxDrawdown);

        double maxDdPct = capital.compareTo(BigDecimal.ZERO) > 0
            ? maxDrawdown.multiply(BigDecimal.valueOf(100)).divide(capital, 2, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        return new BacktestResult("VWAP_NIFTY_SUMMARY", capital, capital.add(totalPnl), totalPnl,
            totalTrades, totalWins, totalLosses, winRate, grossProfit, grossLoss, profitFactor,
            maxDrawdown, maxDdPct, allTrades);
    }

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

        if (nifty930 == 0 && !dayCandles.isEmpty()) {
            nifty930 = dayCandles.get(0).close().doubleValue();
        }
        if (nifty1100 == 0 && dayCandles.size() > 6) {
            nifty1100 = dayCandles.get(Math.min(6, dayCandles.size() - 1)).close().doubleValue();
        }

        strategy.setBaseline930(nifty930, pcr930);
        strategy.setBaseline1100(nifty1100, pcr1100);
    }

    private static List<Candle> aggregateTo60m(List<Candle> candles15m) {
        if (candles15m == null || candles15m.isEmpty()) return Collections.emptyList();

        List<Candle> candles60m = new ArrayList<>();
        Map<LocalDate, List<Candle>> byDay = splitIntoTradingDays(candles15m);

        for (List<Candle> dayCandles : byDay.values()) {
            for (int i = 0; i < dayCandles.size(); i += 4) {
                int endIdx = Math.min(i + 4, dayCandles.size());
                List<Candle> block = dayCandles.subList(i, endIdx);
                if (block.isEmpty()) continue;

                Candle first = block.get(0);
                Candle last = block.get(block.size() - 1);

                BigDecimal high = first.high();
                BigDecimal low = first.low();
                long totalVolume = 0;

                for (Candle c : block) {
                    if (c.high().compareTo(high) > 0) high = c.high();
                    if (c.low().compareTo(low) < 0) low = c.low();
                    totalVolume += c.volume();
                }

                candles60m.add(new Candle(
                    first.symbol(),
                    "60",
                    first.timestamp(),
                    first.open(),
                    high,
                    low,
                    last.close(),
                    totalVolume
                ));
            }
        }
        return candles60m;
    }

    private static Map<LocalDate, List<Candle>> splitIntoTradingDays(List<Candle> candles) {
        Map<LocalDate, List<Candle>> byDay = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate day = c.timestamp().atZone(IST).toLocalDate();
            if (day.getDayOfWeek().getValue() >= 6) continue; // Skip weekends
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(c);
        }
        return byDay;
    }

    // =========================================================================
    // Strategy 3: Intraday Trend & Momentum Option Selling
    // =========================================================================

    private static BacktestResult runIntradayTrendMomentum(BacktestEngine engine,
                                                           BigDecimal capital,
                                                           List<Candle> nifty15m,
                                                           List<Candle> nifty60m) {
        System.out.println("=".repeat(92));
        System.out.println("  STRATEGY 3: INTRADAY TREND & MOMENTUM OPTION SELLING (15m + 60m Multi-Timeframe)");
        System.out.println("=".repeat(92));

        if (nifty15m.isEmpty()) {
            System.out.println("  No NIFTY 15m candles available for backtest.");
            return new BacktestResult("ST_INTRADAY", capital, capital, BigDecimal.ZERO,
                0, 0, 0, 0.0, BigDecimal.ZERO, BigDecimal.ZERO, 1.0, BigDecimal.ZERO, 0.0, List.of());
        }

        // Sort 60m candles slightly before 15m candles if same timestamp so 1h buffer is populated first
        List<Candle> combined = new ArrayList<>();
        for (Candle c : nifty60m) {
            combined.add(new Candle(c.symbol(), c.timeframe(), c.timestamp().minusMillis(50), c.open(), c.high(), c.low(), c.close(), c.volume()));
        }
        combined.addAll(nifty15m);
        combined.sort(Comparator.comparing(Candle::timestamp));

        System.out.printf("  Multi-Timeframe Feed: %d candles (15m: %d, 60m: %d)%n",
            combined.size(), nifty15m.size(), nifty60m.size());

        // Diagnostic: compute ST & RSI directly on the Shoonya feeds
        double[] highs15m = nifty15m.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
        double[] lows15m = nifty15m.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
        double[] closes15m = nifty15m.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] closes60m = nifty60m.stream().mapToDouble(c -> c.close().doubleValue()).toArray();

        double[] st = com.tradingbot.strategy.TechnicalIndicators.calculateSuperTrend(highs15m, lows15m, closes15m, 7, 3.0);
        double rsi = com.tradingbot.strategy.TechnicalIndicators.calculateRsi(closes60m, 14);
        System.out.printf("  [Diagnostic] 15m candles: %d, 60m candles: %d | Last ST: %.2f | Last 60m RSI: %.2f%n",
            highs15m.length, closes60m.length, st[st.length - 1], rsi);

        IntradayTrendMomentumOptionSellingStrategy strategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_INTRADAY_SHOONYA", "BACKTEST_ACCOUNT", NIFTY_SYMBOL
        );

        BacktestResult result = engine.run(strategy, combined, capital);
        printSingleResult("Intraday Trend Option Selling (NIFTY)", result);
        if (result.trades() != null && !result.trades().isEmpty()) {
            for (BacktestTrade t : result.trades()) {
                System.out.printf("    %s | %-5s %s | Entry: ₹%-8.2f -> Exit: ₹%-8.2f | P&L: %+9.2f (%s -> %s)%n",
                    t.entryTime().atZone(IST).toLocalDate(), t.direction(), t.symbol(),
                    t.entryPrice(), t.exitPrice(), t.pnl(), t.entryTag(), t.exitTag());
            }
        }

        return result;
    }

    // =========================================================================
    // Consolidated Performance Reporting
    // =========================================================================

    private static void printResultRow(String symbol, BacktestResult r) {
        String sign = r.netPnL().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        System.out.printf("  %-16s | Trades: %3d | Win Rate: %5.1f%% (W:%2d L:%2d) | Profit Factor: %5.2f | Max DD: %8.2f | Net P&L: %s%10.2f%n",
            symbol, r.totalTrades(), r.winRatePercent(), r.winningTrades(), r.losingTrades(),
            r.profitFactor(), r.maxDrawdown(), sign, r.netPnL());
    }

    private static void printSingleResult(String label, BacktestResult r) {
        String sign = r.netPnL().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        System.out.println("  " + "-".repeat(88));
        System.out.printf("  %-38s | Trades: %3d | Win Rate: %5.1f%% (W:%2d L:%2d)%n",
            label, r.totalTrades(), r.winRatePercent(), r.winningTrades(), r.losingTrades());
        System.out.printf("  Gross Profit: Rs.%-12.2f | Gross Loss: Rs.%-12.2f | Profit Factor: %.2f%n",
            r.grossProfit(), r.grossLoss(), r.profitFactor());
        System.out.printf("  Max Drawdown: Rs.%-12.2f (%.2f%%) | Net P&L: %sRs.%.2f%n",
            r.maxDrawdown(), r.maxDrawdownPercent(), sign, r.netPnL());
        System.out.println("  " + "-".repeat(88));
    }

    private static void printConsolidatedReport(List<BacktestResult> lvrResults,
                                                BacktestResult vwapResult,
                                                BacktestResult trendResult) {
        System.out.println("\n" + "=".repeat(92));
        System.out.println("   CONSOLIDATED 1-MONTH INTRADAY PERFORMANCE SUMMARY (SHOONYA DATA)");
        System.out.println("=".repeat(92));

        BigDecimal lvrPnl = BigDecimal.ZERO, lvrProfit = BigDecimal.ZERO, lvrLoss = BigDecimal.ZERO;
        int lvrTrades = 0, lvrWins = 0, lvrLosses = 0;
        BigDecimal lvrMaxDd = BigDecimal.ZERO;
        for (BacktestResult r : lvrResults) {
            lvrPnl = lvrPnl.add(r.netPnL());
            lvrProfit = lvrProfit.add(r.grossProfit());
            lvrLoss = lvrLoss.add(r.grossLoss());
            lvrTrades += r.totalTrades();
            lvrWins += r.winningTrades();
            lvrLosses += r.losingTrades();
            if (r.maxDrawdown().compareTo(lvrMaxDd) > 0) lvrMaxDd = r.maxDrawdown();
        }
        double lvrWinRate = lvrTrades > 0 ? (lvrWins * 100.0 / lvrTrades) : 0.0;
        double lvrPf = lvrLoss.compareTo(BigDecimal.ZERO) > 0
            ? lvrProfit.divide(lvrLoss, 2, RoundingMode.HALF_UP).doubleValue()
            : (lvrProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.9 : 1.0);

        System.out.printf("  %-42s | Trades: %3d | Win Rate: %5.1f%% | PF: %5.2f | Max DD: Rs.%8.2f | P&L: %+12.2f%n",
            "1. Lowest Volume Reversal (Stocks)", lvrTrades, lvrWinRate, lvrPf, lvrMaxDd, lvrPnl);

        System.out.printf("  %-42s | Trades: %3d | Win Rate: %5.1f%% | PF: %5.2f | Max DD: Rs.%8.2f | P&L: %+12.2f%n",
            "2. Nifty VWAP Momentum Reversal", vwapResult.totalTrades(), vwapResult.winRatePercent(),
            vwapResult.profitFactor(), vwapResult.maxDrawdown(), vwapResult.netPnL());

        System.out.printf("  %-42s | Trades: %3d | Win Rate: %5.1f%% | PF: %5.2f | Max DD: Rs.%8.2f | P&L: %+12.2f%n",
            "3. Intraday Trend & Momentum Option Selling", trendResult.totalTrades(), trendResult.winRatePercent(),
            trendResult.profitFactor(), trendResult.maxDrawdown(), trendResult.netPnL());

        int grandTrades = lvrTrades + vwapResult.totalTrades() + trendResult.totalTrades();
        int grandWins = lvrWins + vwapResult.winningTrades() + trendResult.winningTrades();
        int grandLosses = lvrLosses + vwapResult.losingTrades() + trendResult.losingTrades();
        double grandWinRate = grandTrades > 0 ? (grandWins * 100.0 / grandTrades) : 0.0;
        BigDecimal grandProfit = lvrProfit.add(vwapResult.grossProfit()).add(trendResult.grossProfit());
        BigDecimal grandLoss = lvrLoss.add(vwapResult.grossLoss()).add(trendResult.grossLoss());
        BigDecimal grandPnl = lvrPnl.add(vwapResult.netPnL()).add(trendResult.netPnL());
        double grandPf = grandLoss.compareTo(BigDecimal.ZERO) > 0
            ? grandProfit.divide(grandLoss, 2, RoundingMode.HALF_UP).doubleValue()
            : (grandProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.9 : 1.0);
        BigDecimal grandMaxDd = lvrMaxDd.add(vwapResult.maxDrawdown()).add(trendResult.maxDrawdown());

        System.out.println("  " + "=".repeat(88));
        System.out.printf("  %-42s | Trades: %3d | Win Rate: %5.1f%% (W:%2d L:%2d)%n",
            "PORTFOLIO TOTAL", grandTrades, grandWinRate, grandWins, grandLosses);
        System.out.printf("  Gross Profit: Rs.%+14.2f | Gross Loss: Rs.%14.2f | Profit Factor: %.2f%n",
            grandProfit, grandLoss, grandPf);
        System.out.printf("  Portfolio Max DD: Rs.%11.2f | NET PORTFOLIO P&L: %+15.2f%n",
            grandMaxDd, grandPnl);
        System.out.println("=".repeat(92));
    }

    // =========================================================================
    // Shoonya Authentication Flow (Headless QuickAuth + GenAcsTok)
    // =========================================================================

    private static void authenticateShoonya() throws Exception {
        // Step 1: Compute derived appkey
        StringBuilder keyBuilder = new StringBuilder(SHOONYA_USER_ID).append("|");
        for (int p = 0; p < KEY_OFFSETS.length; p++) {
            keyBuilder.append((char) (KEY_OFFSETS[p] + p));
        }
        String appkey = DigestUtils.sha256Hex(keyBuilder.toString());
        String pwdSha = DigestUtils.sha256Hex(SHOONYA_PASSWORD);
        String totp = generateTotp(SHOONYA_TOTP_SECRET);
        String vc = (SHOONYA_VENDOR_CODE != null && !SHOONYA_VENDOR_CODE.isBlank()) ? SHOONYA_VENDOR_CODE : "NOREN_API";

        Map<String, Object> quickAuthPayload = new LinkedHashMap<>();
        quickAuthPayload.put("apkversion", "W2_20250926");
        quickAuthPayload.put("uid", SHOONYA_USER_ID);
        quickAuthPayload.put("pwd", pwdSha);
        quickAuthPayload.put("factor2", totp);
        quickAuthPayload.put("appkey", appkey);
        quickAuthPayload.put("imei", "12345678-1234-1234-1234-123456789abc");
        quickAuthPayload.put("addldivinf", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        quickAuthPayload.put("source", "API");
        quickAuthPayload.put("vc", vc);
        quickAuthPayload.put("app_key", SHOONYA_CLIENT_ID);

        String quickAuthBody = "jData=" + mapper.writeValueAsString(quickAuthPayload);
        String quickAuthResp = postForm("https://api.shoonya.com/NorenWClientAPI/QuickAuth", quickAuthBody);
        JsonNode qj = mapper.readTree(quickAuthResp);
        if (!"Ok".equalsIgnoreCase(qj.path("stat").asText())) {
            throw new IllegalStateException("Shoonya QuickAuth failed: " + qj.path("emsg").asText(quickAuthResp));
        }
        String code = qj.path("code").asText();

        // Step 2: GenAcsTok
        String checksum = DigestUtils.sha256Hex(SHOONYA_CLIENT_ID + SHOONYA_SECRET_KEY + code);
        Map<String, Object> genAcsPayload = new LinkedHashMap<>();
        genAcsPayload.put("client_id", SHOONYA_CLIENT_ID);
        genAcsPayload.put("code", code);
        genAcsPayload.put("checksum", checksum);

        String genAcsBody = "jData=" + mapper.writeValueAsString(genAcsPayload);
        String genAcsResp = postForm("https://api.shoonya.com/NorenWClientAPI/GenAcsTok", genAcsBody);
        JsonNode gj = mapper.readTree(genAcsResp);
        if (!"Ok".equalsIgnoreCase(gj.path("stat").asText())) {
            throw new IllegalStateException("Shoonya GenAcsTok failed: " + gj.path("emsg").asText(genAcsResp));
        }
        sUserToken = gj.path("susertoken").asText(gj.path("access_token").asText());
    }

    // =========================================================================
    // Shoonya TPSeries Historical Data Fetching & Parsing
    // =========================================================================

    private static List<Candle> fetchShoonyaTPSeries(String exchange, String token,
                                                     long startEpoch, long endEpoch,
                                                     String interval, String canonicalSymbol) {
        for (int retry = 0; retry < 2; retry++) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("uid", SHOONYA_USER_ID);
                payload.put("exch", exchange);
                payload.put("token", token);
                payload.put("st", String.valueOf(startEpoch));
                payload.put("et", String.valueOf(endEpoch));
                payload.put("intrv", interval);

                String jDataStr = mapper.writeValueAsString(payload);
                String formBody = "jData=" + jDataStr + "&jKey=" + sUserToken;

                String response = postForm("https://api.shoonya.com/NorenWClientAPI/TPSeries", formBody);
                if (response.contains("Session Expired") || response.contains("Invalid Session Key")) {
                    System.out.println("    [Shoonya] Session expired during fetch, re-authenticating...");
                    authenticateShoonya();
                    continue;
                }
                return parseShoonyaCandles(response, canonicalSymbol, interval);
            } catch (Exception e) {
                System.out.printf("    WARNING: Failed to fetch Shoonya TPSeries for %s: %s%n", canonicalSymbol, e.getMessage());
            }
        }
        return Collections.emptyList();
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

                        // Extract interval/candle volume (intv) with fallback to day cumulative volume (v)
                        long volume = 0;
                        if (node.has("intv") && !node.path("intv").asText().isEmpty()) {
                            volume = node.path("intv").asLong(0);
                        } else if (node.has("v") && !node.path("v").asText().isEmpty()) {
                            volume = node.path("v").asLong(0);
                        }

                        Instant timestamp = parseShoonyaTimestamp(node);
                        candles.add(new Candle(symbol, interval, timestamp, open, high, low, close, volume));
                    }
                }
            } else if (root.isObject() && "Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
                System.out.println("    WARNING: Shoonya TPSeries error: " + root.path("emsg").asText("Unknown"));
            }
        } catch (Exception e) {
            System.out.printf("    WARNING: Failed to parse Shoonya TPSeries response: %s%n", e.getMessage());
        }

        // Sort chronologically ascending
        candles.sort(Comparator.comparing(Candle::timestamp));
        return candles;
    }

    private static Instant parseShoonyaTimestamp(JsonNode node) {
        if (node.has("ssboe")) {
            long epochSeconds = node.path("ssboe").asLong();
            if (epochSeconds > 0) {
                return Instant.ofEpochSecond(epochSeconds);
            }
        }
        String timeStr = node.path("time").asText("");
        if (!timeStr.isEmpty()) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
                LocalDateTime ldt = LocalDateTime.parse(timeStr, formatter);
                return ldt.atZone(IST).toInstant();
            } catch (Exception ignored) {
                try {
                    DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
                    LocalDateTime ldt = LocalDateTime.parse(timeStr, formatter2);
                    return ldt.atZone(IST).toInstant();
                } catch (Exception ignored2) {}
            }
        }
        return Instant.now();
    }

    private static String postForm(String urlStr, String formData) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(urlStr))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .POST(HttpRequest.BodyPublishers.ofString(formData, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    // =========================================================================
    // Helpers: Env loader & TOTP Generator
    // =========================================================================

    private static void loadEnv() {
        File envFile = new File(".env");
        if (!envFile.exists()) {
            System.err.println("WARNING: .env file not found, checking system properties / env vars");
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || !line.contains("=")) continue;
                    int eqIdx = line.indexOf('=');
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim().replace("\"", "").replace("'", "");
                    System.setProperty(key, val);
                }
            } catch (Exception e) {
                System.err.println("WARNING: Failed to read .env: " + e.getMessage());
            }
        }

        SHOONYA_USER_ID = getProp("SHOONYA_USER_ID");
        SHOONYA_ACCOUNT_ID = getProp("SHOONYA_ACCOUNT_ID");
        SHOONYA_CLIENT_ID = getProp("SHOONYA_CLIENT_ID");
        SHOONYA_SECRET_KEY = getProp("SHOONYA_SECRET_KEY");
        SHOONYA_PASSWORD = getProp("SHOONYA_PASSWORD");
        SHOONYA_TOTP_SECRET = getProp("SHOONYA_TOTP_SECRET");
        SHOONYA_API_KEY = getProp("SHOONYA_API_KEY");
        SHOONYA_VENDOR_CODE = getProp("SHOONYA_VENDOR_CODE");
    }

    private static String getProp(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) val = System.getenv(key);
        return val != null ? val.trim() : "";
    }

    private static String generateTotp(String secret) throws Exception {
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
        int otp = binary % 1000000;
        return String.format("%06d", otp);
    }

    private static byte[] base32Decode(String base32) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int val = base32Chars.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bytes.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return bytes.toByteArray();
    }
}
