package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.impl.LowestVolumeReversalStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone backtest runner for LowestVolumeReversalStrategy using synthetic candle data.
 * Generates realistic intraday OHLCV data simulating Indian market hours (09:15-15:30 IST).
 *
 * Run with: ./gradlew backtestLvr
 */
public class LowestVolumeReversalBacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("  LOWEST VOLUME REVERSAL - SYNTHETIC BACKTEST");
        System.out.println("=".repeat(80));

        BacktestEngine engine = new BacktestEngine();
        BigDecimal initialCapital = BigDecimal.valueOf(100000);

        String[] symbols = {"NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK", "NSE:ICICIBANK"};
        Random random = new Random(42); // Deterministic seed for reproducibility

        for (String symbol : symbols) {
            System.out.printf("%n--- %s ---%n", symbol);
            List<Candle> candles = generateSyntheticCandles(symbol, 30, random);

            LocalDateTime first = LocalDateTime.ofInstant(candles.get(0).timestamp(), IST);
            LocalDateTime last = LocalDateTime.ofInstant(candles.get(candles.size() - 1).timestamp(), IST);
            System.out.printf("  Generated %d candles (%s to %s)%n", candles.size(), first.format(FMT), last.format(FMT));

            NseIndiaClient noOpNse = new NseIndiaClient(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                new ObjectMapper()
            );

            com.tradingbot.instrument.LotSizeService mockLotSize = new com.tradingbot.instrument.LotSizeService(
                null, null, org.springframework.web.reactive.function.client.WebClient.builder()
            ) {
                @Override public int getLotSize(String s) { return 250; }
                @Override public int getOrderQuantity(String s) { return 500; }
            };

            LowestVolumeReversalStrategy strategy = new LowestVolumeReversalStrategy(
                "LVR_SYNTH_" + symbol.replace("NSE:", ""),
                "SYNTH_ACCOUNT",
                symbol,
                2,     // max trades per day
                2.0,   // min RR ratio
                2,     // momentum candles
                noOpNse,
                mockLotSize
            );

            try {
                BacktestResult result = engine.run(strategy, candles, initialCapital);
                printResult(symbol, result);
            } catch (Exception e) {
                System.out.printf("  ERROR: %s%n", e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  SYNTHETIC BACKTEST COMPLETE");
        System.out.println("=".repeat(80));
    }

    /**
     * Generates realistic synthetic 5-minute candles for 30 trading days.
     * Each day has ~73 candles (09:15 to 15:30 = 375 minutes / 5 = 75 bars, minus first 2 for observation).
     * Simulates momentum phases, pullbacks, and volume patterns.
     */
    private static List<Candle> generateSyntheticCandles(String symbol, int tradingDays, Random random) {
        List<Candle> allCandles = new ArrayList<>();
        double basePrice = switch (symbol) {
            case "NSE:RELIANCE" -> 2500.0;
            case "NSE:TCS" -> 3800.0;
            case "NSE:INFY" -> 1600.0;
            case "NSE:HDFCBANK" -> 1700.0;
            case "NSE:ICICIBANK" -> 1200.0;
            default -> 2000.0;
        };

        for (int day = 0; day < tradingDays; day++) {
            Instant dayStart = Instant.parse("2025-07-21T03:45:00Z") // 09:15 IST
                .plusSeconds(day * 86400L);

            // Skip weekends
            LocalDateTime ldt = LocalDateTime.ofInstant(dayStart, IST);
            if (ldt.getDayOfWeek().getValue() >= 6) continue;

            double price = basePrice + (random.nextGaussian() * basePrice * 0.02);
            int candlesPerDay = 73; // 09:15 to 15:30

            // Decide daily pattern: trending up, trending down, or sideways
            double dailyBias = random.nextGaussian() * 0.001;

            // Generate momentum phases (2-3 consecutive same-direction candles)
            int momentumStart = 3 + random.nextInt(10); // Start after first few candles
            int momentumLength = 2 + random.nextInt(2); // 2-3 candles
            boolean momentumUp = random.nextBoolean();

            for (int bar = 0; bar < candlesPerDay; bar++) {
                Instant barTime = dayStart.plusSeconds(bar * 300L);

                // Base volatility
                double volatility = price * 0.003;
                double drift = dailyBias;

                // Apply momentum phase
                if (bar >= momentumStart && bar < momentumStart + momentumLength) {
                    drift = momentumUp ? price * 0.004 : -price * 0.004;
                    volatility *= 0.8;
                }

                // Generate OHLC
                double open = price;
                double change = drift + (random.nextGaussian() * volatility);
                double close = open + change;

                double high = Math.max(open, close) + Math.abs(random.nextGaussian() * volatility * 0.5);
                double low = Math.min(open, close) - Math.abs(random.nextGaussian() * volatility * 0.5);

                // Volume pattern: low during observation, spikes on momentum, lowest on pullbacks
                long baseVolume = 5000 + random.nextInt(20000);
                long volume;
                if (bar < 2) {
                    volume = baseVolume / 2; // Lower volume early
                } else if (bar >= momentumStart && bar < momentumStart + momentumLength) {
                    volume = baseVolume * 2; // Higher volume on momentum
                } else if (bar == momentumStart + momentumLength) {
                    volume = baseVolume / 4; // LOWEST volume on pullback candle
                } else {
                    volume = baseVolume;
                }

                // Ensure valid OHLC relationships
                double h = Math.max(high, Math.max(open, close));
                double l = Math.min(low, Math.min(open, close));

                allCandles.add(new Candle(
                    symbol, "5", barTime,
                    BigDecimal.valueOf(open).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(h).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(l).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(close).setScale(2, RoundingMode.HALF_UP),
                    volume
                ));

                price = close;
            }
        }
        return allCandles;
    }

    private static void printResult(String symbol, BacktestResult result) {
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
                    LocalDateTime.ofInstant(t.entryTime(), IST).format(FMT),
                    LocalDateTime.ofInstant(t.exitTime(), IST).format(FMT)
                );
            }
        } else {
            System.out.println("  No trades executed.");
        }
    }
}
