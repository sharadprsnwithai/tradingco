package com.tradingbot.strategy.ironfly;

import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Position;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IronFlyService {

    private static final Logger log = LoggerFactory.getLogger(IronFlyService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final OptionChainProvider optionChainProvider;
    private final DailyAnalyzer dailyAnalyzer;
    private final AdjustmentHandler adjustmentHandler;
    private final InstrumentMasterService instrumentMaster;
    private final BrokerAdapterRegistry brokerRegistry;
    private final PositionManagerService positionManager;
    private final TelegramBotService telegramBot;
    private final IronFlyDbService dbService;

    private final List<String> underlyings;
    private final int maxConcurrent;
    private final int discoveryWindowMins;
    private final double minStraddlePctOfSpot;
    private final int minOI;
    private final double maxBidAskSpreadPct;

    private final Map<String, IronFlyPosition> activePositions = new ConcurrentHashMap<>();
    private final Map<String, Long> positionDbIds = new ConcurrentHashMap<>();

    public IronFlyService(
        OptionChainProvider optionChainProvider,
        DailyAnalyzer dailyAnalyzer,
        AdjustmentHandler adjustmentHandler,
        InstrumentMasterService instrumentMaster,
        BrokerAdapterRegistry brokerRegistry,
        PositionManagerService positionManager,
        @Lazy TelegramBotService telegramBot,
        IronFlyDbService dbService,
        @Value("${ironfly.underlyings:NIFTY,RELIANCE,HDFCBANK}") String underlyingsCsv,
        @Value("${ironfly.maxConcurrent:3}") int maxConcurrent,
        @Value("${ironfly.discoveryWindowMins:30}") int discoveryWindowMins,
        @Value("${ironfly.minStraddlePctOfSpot:2.0}") double minStraddlePctOfSpot,
        @Value("${ironfly.minOI:1000}") int minOI,
        @Value("${ironfly.maxBidAskSpreadPct:1.5}") double maxBidAskSpreadPct
    ) {
        this.optionChainProvider = optionChainProvider;
        this.dailyAnalyzer = dailyAnalyzer;
        this.adjustmentHandler = adjustmentHandler;
        this.instrumentMaster = instrumentMaster;
        this.brokerRegistry = brokerRegistry;
        this.positionManager = positionManager;
        this.telegramBot = telegramBot;
        this.dbService = dbService;
        this.underlyings = List.of(underlyingsCsv.split(","));
        this.maxConcurrent = maxConcurrent;
        this.discoveryWindowMins = discoveryWindowMins;
        this.minStraddlePctOfSpot = minStraddlePctOfSpot;
        this.minOI = minOI;
        this.maxBidAskSpreadPct = maxBidAskSpreadPct;
    }

    public Mono<Void> sendRecommendations() {
        log.info("[IronFly] Sending entry recommendations for {}", underlyings);
        int currentActive = (int) activePositions.values().stream()
            .filter(p -> p.status() != IronFlyStatus.CLOSED).count();
        if (currentActive >= maxConcurrent) {
            log.info("[IronFly] Max concurrent positions ({}) reached, skipping", maxConcurrent);
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            for (String underlying : underlyings) {
                IronFlyPosition existing = activePositions.get(underlying);
                if (existing != null && existing.status() != IronFlyStatus.CLOSED) {
                    log.debug("[IronFly] {} already has active position, skipping", underlying);
                    continue;
                }
                evaluateAndRecommend(underlying);
            }
        }).then();
    }

    private void evaluateAndRecommend(String underlying) {
        LocalDate expiry = getNextMonthlyExpiry();
        optionChainProvider.getOptionChain(underlying, expiry.toString())
            .zipWith(optionChainProvider.getSpotPrice(underlying))
            .doOnNext(tuple -> {
                Map<Integer, StrikeQuote> chain = tuple.getT1();
                double spot = tuple.getT2();
                if (chain.isEmpty() || spot <= 0) {
                    log.warn("[IronFly] No chain data or spot for {}", underlying);
                    return;
                }
                int atmStrike = chain.keySet().stream()
                    .min(Comparator.comparingInt(s -> Math.abs(s - (int) Math.round(spot / 50) * 50)))
                    .orElse((int) Math.round(spot / 50) * 50);
                StrikeQuote ceQuote = chain.get(atmStrike);
                StrikeQuote peQuote = chain.get(atmStrike);
                if (ceQuote == null || peQuote == null) {
                    log.warn("[IronFly] Missing quotes for ATM {} on {}", atmStrike, underlying);
                    return;
                }
                BigDecimal straddlePremium = ceQuote.ltp().add(peQuote.ltp());
                double straddlePct = straddlePremium.doubleValue() / spot * 100;
                if (straddlePct < minStraddlePctOfSpot) {
                    log.info("[IronFly] {} skipped: straddle {:.1f}% < {:.1f}%", underlying, straddlePct, minStraddlePctOfSpot);
                    return;
                }
                if (ceQuote.openInterest() < minOI || peQuote.openInterest() < minOI) {
                    log.info("[IronFly] {} skipped: OI below threshold", underlying);
                    return;
                }
                int longCallStrike = atmStrike + straddlePremium.intValue();
                int longPutStrike = atmStrike - straddlePremium.intValue();
                StrikeQuote longCallQuote = chain.get(longCallStrike);
                StrikeQuote longPutQuote = chain.get(longPutStrike);
                double netCredit = straddlePremium.doubleValue()
                    - (longCallQuote != null ? longCallQuote.ltp().doubleValue() : 0)
                    - (longPutQuote != null ? longPutQuote.ltp().doubleValue() : 0);
                String msg = formatRecommendation(underlying, atmStrike, spot, straddlePremium.doubleValue(),
                    longCallStrike, longPutStrike, netCredit, expiry);
                telegramBot.sendAlert(msg).subscribe();
                log.info("[IronFly] Recommendation sent for {}: ATM {} net credit {}", underlying, atmStrike, netCredit);
            })
            .doOnError(e -> log.error("[IronFly] Error evaluating {}: {}", underlying, e.getMessage()))
            .subscribe();
    }

    public Mono<Void> discoverPositions() {
        log.info("[IronFly] Discovering positions from broker");
        return brokerRegistry.getAll()
            .filter(com.tradingbot.adapter.BrokerAdapter::isEnabled)
            .take(1)
            .singleOrEmpty()
            .flatMap(adapter -> adapter.getPositions())
            .doOnNext(positions -> {
                for (String underlying : underlyings) {
                    IronFlyPosition existing = activePositions.get(underlying);
                    if (existing != null && existing.status() != IronFlyStatus.CLOSED) continue;
                    discoverForUnderlying(underlying, positions);
                }
            })
            .then();
    }

    private void discoverForUnderlying(String underlying, List<Position> brokerPositions) {
        List<Position> optionLegs = brokerPositions.stream()
            .filter(p -> p.symbol() != null && p.symbol().toUpperCase().contains(underlying.toUpperCase()))
            .filter(p -> p.netQuantity() != 0)
            .toList();
        if (optionLegs.size() < 4) {
            log.debug("[IronFly] {} has {} option legs (need 4), skipping", underlying, optionLegs.size());
            return;
        }
        log.info("[IronFly] Found {} option legs for {}", optionLegs.size(), underlying);
        IronFlyPosition position = new IronFlyPosition(
            underlying, null, null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, IronFlyStatus.DISCOVERED, Instant.now(), null, List.of()
        );
        activePositions.put(underlying, position);
        dbService.savePosition(position)
            .doOnNext(id -> {
                positionDbIds.put(underlying, id);
                log.info("[IronFly] {} position discovered and saved (DB ID: {})", underlying, id);
            })
            .subscribe();
    }

    public Mono<Void> runDailyEvaluation() {
        log.info("[IronFly] Running daily evaluation on {} active positions", activePositions.size());
        return Mono.fromRunnable(() -> {
            StringBuilder summary = new StringBuilder("\ud83d\udcca *IRON FLY DAILY SUMMARY*\n\n");
            boolean hasPositions = false;
            for (Map.Entry<String, IronFlyPosition> entry : activePositions.entrySet()) {
                String underlying = entry.getKey();
                IronFlyPosition position = entry.getValue();
                if (position.status() == IronFlyStatus.CLOSED) continue;
                hasPositions = true;
                int daysToExpiry = dailyAnalyzer.getDaysToExpiry(getNextMonthlyExpiry());
                DailyAnalyzer.EvaluationResult result = dailyAnalyzer.evaluate(position, daysToExpiry);
                if (result.isExit()) {
                    sendExitAlert(underlying, result);
                } else if (result.isAdjust()) {
                    String action = result.action().name();
                    String side;
                    if (action.contains("LONG_CALL") || action.contains("SHORT_CALL")) {
                        side = "CALL";
                    } else {
                        side = "PUT";
                    }
                    sendAdjustmentAlert(underlying, result, side);
                }
                summary.append(String.format("*%s* [%s]\n  Credit: ₹%.2f | MTM: ₹%.2f\n  BE: ₹%.2f / ₹%.2f | DTE: %d\n\n",
                    underlying, position.status(),
                    position.getCurrentNetCredit(), position.getTotalMtm(),
                    position.getUpperBreakeven(), position.getLowerBreakeven(), daysToExpiry));
            }
            if (hasPositions) {
                telegramBot.sendAlert(summary.toString()).subscribe();
            }
        }).then();
    }

    public Map<String, IronFlyPosition> getActivePositions() {
        return Map.copyOf(activePositions);
    }

    /**
     * Books a manual Iron Fly entry with user-reported credit and spot price.
     * Format: /ironfly book RELIANCE credit=47.26 spot=1390 lot=250
     */
    public String bookManualEntry(String underlying, double credit, double spot, int lotSize) {
        String key = underlying.toUpperCase();
        IronFlyPosition existing = activePositions.get(key);
        if (existing != null && existing.status() != IronFlyStatus.CLOSED) {
            return "Already have an active Iron Fly on " + key + ". Close it first.";
        }
        int atmStrike = (int) Math.round(spot / 50.0) * 50;
        double deployedMargin = spot * lotSize * 0.25;
        double targetAmount = deployedMargin * 0.04;
        double slAmount = deployedMargin * 0.08;

        IronFlyPosition position = new IronFlyPosition(
            key, null, null, null, null,
            BigDecimal.valueOf(credit), BigDecimal.valueOf(spot), BigDecimal.ZERO,
            lotSize, IronFlyStatus.TRACKING, Instant.now(), null, List.of()
        );
        activePositions.put(key, position);
        dbService.savePosition(position)
            .doOnNext(id -> {
                positionDbIds.put(key, id);
                log.info("[IronFly] {} manual entry saved (DB ID: {})", key, id);
            })
            .subscribe();

        return String.format(
            "IRON FLY BOOKED — %s\n\nATM: %d | Spot: ₹%.2f\nCredit: ₹%.2f\nLot Size: %d\nDeployed Margin: ₹%.2f\nProfit Target (4%%): ₹%.2f\nStop Loss (8%%): ₹%.2f\n\nTracking started. Daily eval at 15:00 IST.",
            key, atmStrike, spot, credit, lotSize, deployedMargin, targetAmount, slAmount);
    }

    private void sendExitAlert(String underlying, DailyAnalyzer.EvaluationResult result) {
        String msg = String.format(
            "\u26a0\ufe0f *IRON FLY EXIT SIGNAL \u2014 %s*\n\n*Trigger:* %s\n*Reason:* %s\n\n_Close all 4 legs now on your broker._",
            underlying, result.action(), result.reason());
        telegramBot.sendAlert(msg).subscribe();
    }

    private void sendAdjustmentAlert(String underlying, DailyAnalyzer.EvaluationResult result, String side) {
        String action = result.action().name();
        boolean isHedge = action.contains("LONG");

        // Find current spot and 0.4 delta strike
        optionChainProvider.getSpotPrice(underlying)
            .doOnNext(spot -> {
                LocalDate expiry = getNextMonthlyExpiry();
                optionChainProvider.getOptionChain(underlying, expiry.toString())
                    .doOnNext(chain -> {
                        int newStrike = findDeltaStrike(chain, spot, side, expiry);
                        String actionType = isHedge ? "Sell profitable hedge" : "Buy back losing leg";
                        String msg = String.format(
                            "\ud83d\udd27 *IRON FLY ADJUSTMENT \u2014 %s*\n\n*Trigger:* %s\n*Reason:* %s\n\n*Action:* %s %s, sell new %s @ %d (0.4 delta)\n\n_Please adjust manually._",
                            underlying, result.action(), result.reason(), actionType, side, side, newStrike);
                        telegramBot.sendAlert(msg).subscribe();
                    })
                    .subscribe();
            })
            .subscribe();
    }

    private int findDeltaStrike(Map<Integer, StrikeQuote> chain, double spot, String side, LocalDate expiry) {
        OptionType type = "CALL".equals(side) ? OptionType.CE : OptionType.PE;
        double targetDelta = 0.4;
        double timeToExpiry = dailyAnalyzer.getDaysToExpiry(expiry) / 365.0;
        if (timeToExpiry <= 0) timeToExpiry = 1.0 / 365.0;

        return chain.values().stream()
            .filter(q -> q.optionType() == type)
            .filter(q -> {
                double d = Math.abs(q.delta());
                if ("PUT".equals(side)) d = 1.0 - d;
                return Math.abs(d - targetDelta) < 0.15;
            })
            .min(Comparator.comparingDouble(q -> {
                double d = Math.abs(q.delta());
                if ("PUT".equals(side)) d = 1.0 - d;
                return Math.abs(d - targetDelta);
            }))
            .map(StrikeQuote::strike)
            .orElseGet(() -> {
                int atm = (int) Math.round(spot / 50) * 50;
                return "CALL".equals(side) ? atm + 100 : atm - 100;
            });
    }

    private void sendDailyStatus(String underlying, IronFlyPosition position) {
        BigDecimal mtm = position.getTotalMtm();
        BigDecimal credit = position.getCurrentNetCredit();
        double mtmPct = credit.doubleValue() != 0 ? mtm.doubleValue() / Math.abs(credit.doubleValue()) * 100 : 0;
        String msg = String.format(
            "\ud83d\udcca *IRON FLY STATUS \u2014 %s*\n\n*ATM:* %d | *Credit:* \u20b9%.2f\n*MTM:* \u20b9%.2f (%.1f%%)\n*BE:* \u20b9%.2f / \u20b9%.2f\n*Adjustments:* %d",
            underlying, position.getAtmStrike(), credit, mtm, mtmPct,
            position.getUpperBreakeven(), position.getLowerBreakeven(), position.getAdjustmentCount());
        telegramBot.sendAlert(msg).subscribe();
    }

    private String formatRecommendation(String underlying, int atmStrike, double spot,
                                          double straddlePremium, int longCallStrike, int longPutStrike,
                                          double netCredit, LocalDate expiry) {
        return String.format(
            "\ud83c\udfaf *IRON FLY \u2014 %s*\n\n*Expiry:* %s\n*ATM:* %d (Spot: \u20b9%.2f)\n*Straddle:* \u20b9%.2f\n\n*Entry:*\n\u2022 Sell %d CE\n\u2022 Sell %d PE\n\u2022 Buy %d CE\n\u2022 Buy %d PE\n\n*Net Credit:* \u20b9%.2f\n*BE:* \u20b9%.2f / \u20b9%.2f\n\n_Execute manually on your broker._",
            underlying, expiry.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
            atmStrike, spot, straddlePremium,
            atmStrike, atmStrike, longCallStrike, longPutStrike,
            netCredit, atmStrike + netCredit, atmStrike - netCredit);
    }

    private LocalDate getNextMonthlyExpiry() {
        LocalDate today = LocalDate.now(IST_ZONE);
        LocalDate lastThursday = today.withDayOfMonth(today.lengthOfMonth());
        while (lastThursday.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            lastThursday = lastThursday.minusDays(1);
        }
        if (today.isAfter(lastThursday)) {
            LocalDate nextMonth = today.plusMonths(1);
            lastThursday = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());
            while (lastThursday.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
                lastThursday = lastThursday.minusDays(1);
            }
        }
        return lastThursday;
    }
}
