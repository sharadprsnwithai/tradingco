package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import org.apache.commons.codec.digest.DigestUtils;

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
 * Standalone Iron Fly backtest for last 13 months using real Kite historical data.
 * Uses Black-Scholes for option pricing. Tracks 1 lot per trade.
 *
 * Strategy per month:
 *  - On monthly expiry (last Thursday): enter Iron Fly
 *  - Short ATM CE + Short ATM PE, Long OTM CE hedge + Long OTM PE hedge
 *  - Daily evaluation: profit target 50%, stop loss -100%, expiry guard 4 days
 *  - On next expiry: close position
 */
public class IronFlyBacktestRunner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final double RISK_FREE_RATE = 0.06;
    private static final double DEFAULT_VOLATILITY = 0.22;
    private static final double PROFIT_TARGET_PCT = 4.0;   // 4% of deployed margin
    private static final double STOP_LOSS_PCT = 8.0;       // 8% of deployed margin
    private static final double MARGIN_FACTOR = 0.25;       // ~25% of notional (short straddle minus hedge benefit)
    private static final int EXPIRY_GUARD_DAYS = 4;
    private static final double SHORT_LEG_LOSS_PCT = 70.0;  // Roll short leg if premium increases 70%+
    private static final double HEDGE_PROFIT_PCT = 50.0;    // Roll hedge if premium increases 50%+
    private static final double ADJUSTMENT_TARGET_DELTA = 0.4;

    private static String KITE_API_KEY;
    private static String KITE_API_SECRET;
    private static String KITE_USER_ID;
    private static String KITE_PASSWORD;
    private static String KITE_TOTP_SECRET;

    public static void main(String[] args) throws Exception {
        loadEnv();
        int monthsBack = args.length > 0 ? Integer.parseInt(args[0]) : 24;

        System.out.println("=".repeat(90));
        System.out.printf("  IRON FLY BACKTEST - LAST %d MONTHS (2 YEARS) - 1 LOT%n", monthsBack);
        System.out.println("  Underlyings: NIFTY, RELIANCE, HDFCBANK");
        System.out.println("  Comparing: Mode A (roll losing short) vs Mode B (roll profitable hedge)");
        System.out.println("=".repeat(90));

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);

        System.out.println("\n[1/3] Authenticating with Kite Connect...");
        String accessToken = executeKiteHeadlessLogin();
        System.out.println("  -> Kite authentication successful");

        System.out.println("\n[2/3] Discovering instrument tokens...");
        Map<String, String> symbolToToken = new HashMap<>();
        String[][] mapping = {{"NIFTY 50", "NSE:NIFTY"}, {"RELIANCE", "NSE:RELIANCE"}, {"HDFCBANK", "NSE:HDFCBANK"}};
        for (String[] m : mapping) {
            String token = searchKiteInstrument(accessToken, m[0]);
            if (token != null) {
                symbolToToken.put(m[1], token);
                System.out.printf("  -> %s: token=%s%n", m[1], token);
            }
        }

        System.out.printf("%n[3/3] Fetching %d-month daily candles...%n", monthsBack);
        LocalDate toDate = LocalDate.now(IST);
        LocalDate fromDate = toDate.minusMonths(monthsBack);

        Map<String, List<Candle>> allCandles = new HashMap<>();
        for (Map.Entry<String, String> entry : symbolToToken.entrySet()) {
            String symbol = entry.getKey();
            String token = entry.getValue();
            List<Candle> candles = fetchKiteHistoricalCandles(accessToken, token, "day", fromDate, toDate, symbol);
            if (!candles.isEmpty()) {
                allCandles.put(symbol, candles);
                System.out.printf("  -> %s: %d daily candles (%s to %s)%n", symbol, candles.size(),
                    LocalDateTime.ofInstant(candles.get(0).timestamp(), IST).format(DateTimeFormatter.ofPattern("dd-MM-yyyy")),
                    LocalDateTime.ofInstant(candles.get(candles.size() - 1).timestamp(), IST).format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
            }
            Thread.sleep(300);
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  RUNNING IRON FLY BACKTEST — MODE A: ROLL LOSING SHORT LEG → 0.4Δ");
        System.out.println("=".repeat(90));

        // MODE A: Roll losing short leg
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  MODE A: ROLL LOSING SHORT LEG → 0.4Δ");
        System.out.println("=".repeat(90));
        double[] grandA = runAllUnderlyings(allCandles, false);

        // MODE B: Roll profitable hedge
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  MODE B: ROLL PROFITABLE HEDGE → 0.4Δ");
        System.out.println("=".repeat(90));
        double[] grandB = runAllUnderlyings(allCandles, true);

        // COMPARISON
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  COMPARISON: SHORT LEG ROLL vs HEDGE PROFIT ROLL");
        System.out.println("=".repeat(90));
        System.out.printf("  %-20s %15s %15s%n", "", "Mode A (Short)", "Mode B (Hedge)");
        System.out.printf("  %-20s %15s %15s%n", "-------------------", "---------------", "---------------");
        System.out.printf("  %-20s %,15.2f %,15.2f%n", "Total P&L", grandA[0], grandB[0]);
        System.out.printf("  %-20s %15.0f %15.0f%n", "Total Trades", grandA[1], grandB[1]);
        System.out.printf("  %-20s %15.0f %15.0f%n", "Wins", grandA[2], grandB[2]);
        System.out.printf("  %-20s %15.0f %15.0f%n", "Losses", grandA[3], grandB[3]);
        if (grandA[1] > 0) System.out.printf("  %-20s %14.1f%% %14.1f%%%n", "Win Rate", grandA[2]*100/grandA[1], grandB[2]*100/grandB[1]);
        if (grandA[1] > 0) System.out.printf("  %-20s %,15.2f %,15.2f%n", "Avg P&L/Trade", grandA[0]/grandA[1], grandB[0]/grandB[1]);
        System.out.println("=".repeat(90));
    }

    private static double[] runAllUnderlyings(Map<String, List<Candle>> allCandles, boolean hedgeMode) {
        double totalPnl = 0;
        int totalTrades = 0, wins = 0, losses = 0;
        for (Map.Entry<String, List<Candle>> entry : allCandles.entrySet()) {
            BacktestResult result = runIronFlyBacktest(entry.getKey(), entry.getValue(), hedgeMode);
            printResult(entry.getKey(), result);
            totalPnl += result.netPnL().doubleValue();
            totalTrades += result.totalTrades();
            wins += result.winningTrades();
            losses += result.losingTrades();
        }
        System.out.printf("%n  Grand: P&L Rs.%,.2f | Trades %d (W %d L %d) | Win Rate %.1f%%%n",
            totalPnl, totalTrades, wins, losses, totalTrades > 0 ? wins * 100.0 / totalTrades : 0);
        return new double[]{totalPnl, totalTrades, wins, losses};
    }

    private static BacktestResult runIronFlyBacktest(String symbol, List<Candle> candles, boolean hedgeMode) {
        System.out.printf("%n--- Iron Fly: %s ---%n", symbol);

        List<LocalDate> monthlyExpiries = getMonthlyExpiries(candles);
        System.out.printf("  Found %d monthly expiry dates%n", monthlyExpiries.size());

        double totalPnl = 0;
        int wins = 0;
        int losses = 0;
        List<String> tradeLog = new ArrayList<>();

        for (int i = 0; i < monthlyExpiries.size() - 1; i++) {
            LocalDate entryDate = monthlyExpiries.get(i);
            LocalDate exitDate = monthlyExpiries.get(i + 1);

            Candle entryCandle = findCandleOnDate(candles, entryDate);
            if (entryCandle == null) {
                System.out.printf("  [%d] No candle for %s, skipping%n", i + 1, entryDate);
                continue;
            }

            double spot = entryCandle.close().doubleValue();
            int roundTo = getAtmRoundTo(symbol);
            int atmStrike = (int) Math.round(spot / roundTo) * roundTo;

            double timeToExpiryYears = daysBetween(entryDate, exitDate) / 365.0;
            double callPremium = BlackScholesPricer.callPrice(spot, atmStrike, timeToExpiryYears, RISK_FREE_RATE, DEFAULT_VOLATILITY);
            double putPremium = BlackScholesPricer.putPrice(spot, atmStrike, timeToExpiryYears, RISK_FREE_RATE, DEFAULT_VOLATILITY);
            double straddlePremium = callPremium + putPremium;

            int longCallStrike = atmStrike + (int) Math.round(straddlePremium);
            int longPutStrike = atmStrike - (int) Math.round(straddlePremium);

            double hedgeTimeToExpiry = timeToExpiryYears;
            double longCallCost = BlackScholesPricer.callPrice(spot, longCallStrike, hedgeTimeToExpiry, RISK_FREE_RATE, DEFAULT_VOLATILITY);
            double longPutCost = BlackScholesPricer.putPrice(spot, longPutStrike, hedgeTimeToExpiry, RISK_FREE_RATE, DEFAULT_VOLATILITY);

            double netCredit = straddlePremium - longCallCost - longPutCost;

            // Margin per lot = spot * lotSize * 25% (short straddle margin minus hedge benefit)
            int lotSize = getLotSize(symbol);
            double marginPerLot = spot * lotSize * MARGIN_FACTOR;
            double profitTargetPnl = marginPerLot * PROFIT_TARGET_PCT / 100.0;  // 4% of margin
            double stopLossPnl = marginPerLot * STOP_LOSS_PCT / 100.0;         // 8% of margin

            System.out.printf("  [%d] %s: Entry ₹%.0f | ATM %d | Straddle ₹%.2f | Hedge %d/%d | Credit ₹%.2f | Margin ₹%.0f | Target ₹%.2f | SL ₹%.2f%n",
                i + 1, entryDate, spot, atmStrike, straddlePremium, longCallStrike, longPutStrike, netCredit, marginPerLot, profitTargetPnl, stopLossPnl);

            double positionPnl = 0;
            String exitReason = "EXPIRY";
            int adjustments = 0;
            String adjDetails = "";
            double shortCallEntry = callPremium;
            double shortPutEntry = putPremium;
            int shortCallStrike = atmStrike;
            int shortPutStrike = atmStrike;

            List<Candle> dailyCandles = getCandlesBetween(candles, entryDate, exitDate);
            for (int d = 1; d < dailyCandles.size(); d++) {
                Candle dayCandle = dailyCandles.get(d);
                LocalDate dayDate = dayCandle.timestamp().atZone(IST).toLocalDate();
                double daySpot = dayCandle.close().doubleValue();
                int daysToExpiry = daysBetween(dayDate, exitDate);

                if (daysToExpiry <= EXPIRY_GUARD_DAYS) {
                    exitReason = "EXPIRY_GUARD";
                    break;
                }

                double remainingTime = daysToExpiry / 365.0;
                if (remainingTime <= 0) remainingTime = 1.0 / 365.0;

                double curCall = BlackScholesPricer.callPrice(daySpot, shortCallStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                double curPut = BlackScholesPricer.putPrice(daySpot, shortPutStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                double curLongCall = BlackScholesPricer.callPrice(daySpot, longCallStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                double curLongPut = BlackScholesPricer.putPrice(daySpot, longPutStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);

                double mtm = (curCall + curPut) - (curLongCall + curLongPut) - netCredit;
                positionPnl = -mtm;
                double cumPnl = positionPnl * lotSize;

                if (cumPnl >= profitTargetPnl) { exitReason = "PROFIT_TARGET"; break; }
                if (cumPnl <= -stopLossPnl) { exitReason = "STOP_LOSS"; break; }

                // Roll short call — buy back losing leg, sell new at 0.4 delta
                if (shortCallEntry > 0 && daysToExpiry > EXPIRY_GUARD_DAYS + 2) {
                    double lossPct = (curCall - shortCallEntry) / shortCallEntry * 100;
                    if (lossPct >= SHORT_LEG_LOSS_PCT) {
                        int newStrike = findDeltaStrike(daySpot, remainingTime, true, roundTo);
                        double newPrem = BlackScholesPricer.callPrice(daySpot, newStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                        positionPnl -= (curCall - newPrem);
                        netCredit += (newPrem - shortCallEntry);
                        adjDetails += String.format(" CE %d→%d", shortCallStrike, newStrike);
                        shortCallEntry = newPrem;
                        shortCallStrike = newStrike;
                        adjustments++;
                    }
                }
                // Roll short put — buy back losing leg, sell new at 0.4 delta
                if (shortPutEntry > 0 && daysToExpiry > EXPIRY_GUARD_DAYS + 2) {
                    double lossPct = (curPut - shortPutEntry) / shortPutEntry * 100;
                    if (lossPct >= SHORT_LEG_LOSS_PCT) {
                        int newStrike = findDeltaStrike(daySpot, remainingTime, false, roundTo);
                        double newPrem = BlackScholesPricer.putPrice(daySpot, newStrike, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                        positionPnl -= (curPut - newPrem);
                        netCredit += (newPrem - shortPutEntry);
                        adjDetails += String.format(" PE %d→%d", shortPutStrike, newStrike);
                        shortPutEntry = newPrem;
                        shortPutStrike = newStrike;
                        adjustments++;
                    }
                }

                // MODE B: Roll profitable hedge to 0.4 delta
                if (hedgeMode && daysToExpiry > EXPIRY_GUARD_DAYS + 2) {
                    // Long call hedge profit
                    double longCallEntry = BlackScholesPricer.callPrice(entryCandle.close().doubleValue(), longCallStrike, timeToExpiryYears, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                    if (longCallEntry > 0) {
                        double hedgeProfitPct = (curLongCall - longCallEntry) / longCallEntry * 100;
                        if (hedgeProfitPct >= HEDGE_PROFIT_PCT) {
                            int newHedge = findDeltaStrike(daySpot, remainingTime, true, roundTo);
                            double newHedgePrem = BlackScholesPricer.callPrice(daySpot, newHedge, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                            positionPnl += (curLongCall - newHedgePrem); // profit from selling old - cost of new
                            netCredit -= (newHedgePrem - curLongCall);   // credit decreases (bought more expensive)
                            adjDetails += String.format(" LCE %d→%d", longCallStrike, newHedge);
                            longCallStrike = newHedge;
                            adjustments++;
                        }
                    }
                    // Long put hedge profit
                    double longPutEntry = BlackScholesPricer.putPrice(entryCandle.close().doubleValue(), longPutStrike, timeToExpiryYears, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                    if (longPutEntry > 0) {
                        double hedgeProfitPct = (curLongPut - longPutEntry) / longPutEntry * 100;
                        if (hedgeProfitPct >= HEDGE_PROFIT_PCT) {
                            int newHedge = findDeltaStrike(daySpot, remainingTime, false, roundTo);
                            double newHedgePrem = BlackScholesPricer.putPrice(daySpot, newHedge, remainingTime, RISK_FREE_RATE, DEFAULT_VOLATILITY);
                            positionPnl += (curLongPut - newHedgePrem);
                            netCredit -= (newHedgePrem - curLongPut);
                            adjDetails += String.format(" LPE %d→%d", longPutStrike, newHedge);
                            longPutStrike = newHedge;
                            adjustments++;
                        }
                    }
                }
            }

            double lotPnl = positionPnl * lotSize;
            totalPnl += lotPnl;
            if (lotPnl > 0) wins++; else losses++;

            String adjStr = adjustments > 0 ? " (" + adjustments + " adj:" + adjDetails.trim() + ")" : "";
            tradeLog.add(String.format("  %d. %s -> %s | Entry ₹%.0f | P&L Rs.%,.2f | %s%s",
                i + 1, entryDate, exitDate, spot, lotPnl, exitReason, adjStr));
        }

        System.out.println("\n  Trade Log:");
        tradeLog.forEach(System.out::println);

        return new BacktestResult(
            "IRON_FLY_" + symbol,
            BigDecimal.ZERO,
            BigDecimal.valueOf(totalPnl),
            BigDecimal.valueOf(totalPnl),
            wins + losses, wins, losses,
            wins + losses > 0 ? wins * 100.0 / (wins + losses) : 0,
            BigDecimal.valueOf(Math.max(0, totalPnl)),
            BigDecimal.valueOf(Math.abs(Math.min(0, totalPnl))),
            losses > 0 ? Math.max(0, totalPnl) / Math.abs(Math.min(0, totalPnl)) : totalPnl > 0 ? 999.0 : 0,
            BigDecimal.ZERO, 0,
            List.of()
        );
    }

    private static List<LocalDate> getMonthlyExpiries(List<Candle> candles) {
        List<LocalDate> expiries = new ArrayList<>();
        if (candles.isEmpty()) return expiries;

        java.util.Set<LocalDate> candleDates = new java.util.HashSet<>();
        for (Candle c : candles) {
            candleDates.add(c.timestamp().atZone(IST).toLocalDate());
        }

        LocalDate start = candles.get(0).timestamp().atZone(IST).toLocalDate();
        LocalDate end = candles.get(candles.size() - 1).timestamp().atZone(IST).toLocalDate();

        LocalDate current = start.withDayOfMonth(1);
        while (!current.isAfter(end)) {
            LocalDate targetDate = current.withDayOfMonth(current.lengthOfMonth());
            while (targetDate.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
                targetDate = targetDate.minusDays(1);
            }
            // If Thursday was a holiday, fall back to Wednesday / Tuesday
            while (!candleDates.contains(targetDate) && targetDate.isAfter(current)) {
                targetDate = targetDate.minusDays(1);
            }
            if (candleDates.contains(targetDate) && !targetDate.isBefore(start) && !targetDate.isAfter(end)) {
                expiries.add(targetDate);
            }
            current = current.plusMonths(1);
        }
        return expiries;
    }

    private static Candle findCandleOnDate(List<Candle> candles, LocalDate date) {
        Instant target = date.atStartOfDay(IST).toInstant();
        for (Candle c : candles) {
            LocalDate cDate = c.timestamp().atZone(IST).toLocalDate();
            if (cDate.equals(date)) return c;
        }
        return null;
    }

    private static List<Candle> getCandlesBetween(List<Candle> candles, LocalDate from, LocalDate to) {
        List<Candle> result = new ArrayList<>();
        for (Candle c : candles) {
            LocalDate cDate = c.timestamp().atZone(IST).toLocalDate();
            if (!cDate.isBefore(from) && !cDate.isAfter(to)) {
                result.add(c);
            }
        }
        return result;
    }

    private static int daysBetween(LocalDate a, LocalDate b) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(a, b);
    }

    private static int getLotSize(String symbol) {
        if (symbol.contains("NIFTY")) return 25;
        if (symbol.contains("RELIANCE")) return 250;
        if (symbol.contains("TCS")) return 175;
        if (symbol.contains("HDFCBANK")) return 550;
        return 250;
    }

    private static int getAtmRoundTo(String symbol) {
        if (symbol.contains("NIFTY")) return 50;
        return 50;
    }

    private static int findDeltaStrike(double spot, double timeToExpiry, boolean isCall, int roundTo) {
        // Walk OTM strikes to find ~0.4 delta
        for (int i = 1; i <= 20; i++) {
            int strike;
            if (isCall) {
                strike = (int) Math.round(spot / roundTo) * roundTo + (i * roundTo);
            } else {
                strike = (int) Math.round(spot / roundTo) * roundTo - (i * roundTo);
            }
            if (strike <= 0) continue;
            double delta = Math.abs(BlackScholesPricer.callDelta(spot, strike, timeToExpiry, RISK_FREE_RATE, DEFAULT_VOLATILITY));
            if (!isCall) delta = 1.0 - delta;
            if (Math.abs(delta - ADJUSTMENT_TARGET_DELTA) < 0.1) {
                return strike;
            }
        }
        // Fallback: 2 strikes OTM
        int atm = (int) Math.round(spot / roundTo) * roundTo;
        return isCall ? atm + (2 * roundTo) : atm - (2 * roundTo);
    }

    // ========== Kite API (same as BacktestRunner) ==========

    private static String executeKiteHeadlessLogin() throws Exception {
        String loginFormData = "user_id=" + URLEncoder.encode(KITE_USER_ID, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(KITE_PASSWORD, StandardCharsets.UTF_8);
        String loginResponse = postKiteForm("https://kite.zerodha.com/api/login", loginFormData);
        JsonNode loginJson = mapper.readTree(loginResponse);
        if (!"success".equalsIgnoreCase(loginJson.path("status").asText())) {
            throw new IllegalStateException("Kite login failed: " + loginJson.path("message").asText());
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
            throw new IllegalStateException("Kite 2FA failed: " + twoFaJson.path("message").asText());
        }

        String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + KITE_API_KEY;
        String requestToken = extractRequestToken(connectUrl);

        String checksum = DigestUtils.sha256Hex(KITE_API_KEY + requestToken + KITE_API_SECRET);
        String tokenFormData = "api_key=" + URLEncoder.encode(KITE_API_KEY, StandardCharsets.UTF_8)
            + "&request_token=" + URLEncoder.encode(requestToken, StandardCharsets.UTF_8)
            + "&checksum=" + URLEncoder.encode(checksum, StandardCharsets.UTF_8);
        String tokenResponse = postKiteForm("https://api.kite.trade/session/token", tokenFormData);
        JsonNode tokenJson = mapper.readTree(tokenResponse);
        return tokenJson.path("data").path("access_token").asText();
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
            } else break;
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
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private static String searchKiteInstrument(String accessToken, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.kite.trade/instruments"))
            .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
            .header("X-Kite-Version", "3")
            .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String[] lines = response.body().split("\n");
        String[] headers = lines[0].split(",");
        int idxToken = -1, idxExchange = -1, idxName = -1, idxSymbol = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("instrument_token")) idxToken = i;
            else if (h.equals("exchange")) idxExchange = i;
            else if (h.equals("name")) idxName = i;
            else if (h.equals("tradingsymbol")) idxSymbol = i;
        }
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= idxToken) continue;
            String exchange = idxExchange >= 0 && cols.length > idxExchange ? cols[idxExchange].trim() : "";
            String name = idxName >= 0 && cols.length > idxName ? cols[idxName].trim() : "";
            String symbol = idxSymbol >= 0 && cols.length > idxSymbol ? cols[idxSymbol].trim() : "";
            if (!"NSE".equals(exchange)) continue;
            if (query.equalsIgnoreCase(name) || query.equalsIgnoreCase(symbol)) {
                return cols[idxToken].trim();
            }
        }
        return null;
    }

    private static List<Candle> fetchKiteHistoricalCandles(String accessToken, String token,
                                                              String interval, LocalDate from, LocalDate to,
                                                              String symbol) {
        try {
            String url = String.format("https://api.kite.trade/instruments/historical/%s/%s?from=%s&to=%s",
                token, interval, from.format(DateTimeFormatter.ISO_LOCAL_DATE), to.format(DateTimeFormatter.ISO_LOCAL_DATE));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + KITE_API_KEY + ":" + accessToken)
                .header("X-Kite-Version", "3")
                .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseKiteCandles(response.body(), symbol, interval.equals("day") ? "1d" : interval);
        } catch (Exception e) {
            System.out.printf("    WARNING: Failed to fetch candles: %s%n", e.getMessage());
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
                        Instant ts = parseKiteTimestamp(node.get(0).asText());
                        candles.add(new Candle(symbol, interval, ts,
                            new BigDecimal(node.get(1).asText()),
                            new BigDecimal(node.get(2).asText()),
                            new BigDecimal(node.get(3).asText()),
                            new BigDecimal(node.get(4).asText()),
                            node.get(5).asLong()));
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
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
                String clean = ts.replaceAll("[+-]\\d{4}$", "");
                return LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(IST).toInstant();
            } catch (Exception e2) { return Instant.now(); }
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
        try (var os = conn.getOutputStream()) { os.write(bytes); os.flush(); }
        int code = conn.getResponseCode();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
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
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
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
        if (count < decoded.length) {
            byte[] result = new byte[count];
            System.arraycopy(decoded, 0, result, 0, count);
            return result;
        }
        return decoded;
    }

    private static void printResult(String symbol, BacktestResult result) {
        System.out.printf("%n  --- Iron Fly: %s ---%n", symbol);
        System.out.printf("  Net P&L:       Rs.%,.2f%n", result.netPnL());
        System.out.printf("  Total Trades:  %d (Wins: %d, Losses: %d)%n",
            result.totalTrades(), result.winningTrades(), result.losingTrades());
        if (result.totalTrades() > 0) {
            System.out.printf("  Win Rate:      %.1f%%%n", result.winningTrades() * 100.0 / result.totalTrades());
            System.out.printf("  Avg P&L/Trade: Rs.%,.2f%n", result.netPnL().doubleValue() / result.totalTrades());
        }
    }

    private static void printComparisonTable(Map<String, BacktestResult> resultsA, Map<String, BacktestResult> resultsB, double[] grandA, double[] grandB) {
        System.out.println("\n" + "=".repeat(102));
        System.out.println("   24-MONTH MONTHLY IRON FLY PERFORMANCE BREAKDOWN (AUG 2024 - AUG 2026)");
        System.out.println("=".repeat(102));
        System.out.printf("  %-16s | %-28s | %-28s | %-16s%n", "Underlying", "Mode A (Roll Losing Short)", "Mode B (Roll Profit Hedge)", "Winning Mode");
        System.out.printf("  %-16s | %-28s | %-28s | %-16s%n", "----------------", "----------------------------", "----------------------------", "----------------");

        for (String sym : resultsA.keySet()) {
            BacktestResult a = resultsA.get(sym);
            BacktestResult b = resultsB.get(sym);
            double diff = a.netPnL().subtract(b.netPnL()).doubleValue();
            String better = diff >= 0 ? "Mode A (+₹" + String.format("%.0f", diff) + ")" : "Mode B (+₹" + String.format("%.0f", -diff) + ")";

            double winRateA = a.totalTrades() > 0 ? a.winningTrades() * 100.0 / a.totalTrades() : 0;
            double winRateB = b.totalTrades() > 0 ? b.winningTrades() * 100.0 / b.totalTrades() : 0;

            System.out.printf("  %-16s | ₹%,10.2f (%2d/%2d, %4.1f%%) | ₹%,10.2f (%2d/%2d, %4.1f%%) | %-16s%n",
                sym, a.netPnL(), a.winningTrades(), a.totalTrades(), winRateA,
                b.netPnL(), b.winningTrades(), b.totalTrades(), winRateB, better);
        }

        System.out.println("  " + "-".repeat(98));
        double grandDiff = grandA[0] - grandB[0];
        String grandBetter = grandDiff >= 0 ? "Mode A (+₹" + String.format("%.0f", grandDiff) + ")" : "Mode B (+₹" + String.format("%.0f", -grandDiff) + ")";
        System.out.printf("  %-16s | ₹%,10.2f (%2d/%2d, %4.1f%%) | ₹%,10.2f (%2d/%2d, %4.1f%%) | %-16s%n",
            "GRAND TOTAL", grandA[0], (int)grandA[2], (int)grandA[1], grandA[2]*100.0/grandA[1],
            grandB[0], (int)grandB[2], (int)grandB[1], grandB[2]*100.0/grandB[1], grandBetter);
        System.out.println("=".repeat(102));
    }

    private static void loadEnv() {
        try (var reader = new java.io.BufferedReader(new java.io.FileReader(".env", StandardCharsets.UTF_8))) {
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
            System.err.println("WARNING: Could not load .env: " + e.getMessage());
        }
    }
}
