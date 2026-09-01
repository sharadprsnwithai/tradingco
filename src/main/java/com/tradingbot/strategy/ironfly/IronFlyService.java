package com.tradingbot.strategy.ironfly;

import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Position;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.telegram.TelegramBotService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IronFlyService {

    private static final Logger log = LoggerFactory.getLogger(IronFlyService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern OPTION_SYMBOL_PATTERN = Pattern.compile(".*?(\\d+)(CE|PE)$");

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
        @Value("${ironfly.max-concurrent:${ironfly.maxConcurrent:3}}") int maxConcurrent,
        @Value("${ironfly.discovery-window-mins:${ironfly.discoveryWindowMins:30}}") int discoveryWindowMins,
        @Value("${ironfly.min-straddle-pct-of-spot:${ironfly.minStraddlePctOfSpot:2.0}}") double minStraddlePctOfSpot,
        @Value("${ironfly.min-oi:${ironfly.minOI:1000}}") int minOI,
        @Value("${ironfly.max-bid-ask-spread-pct:${ironfly.maxBidAskSpreadPct:1.5}}") double maxBidAskSpreadPct
    ) {
        this.optionChainProvider = optionChainProvider;
        this.dailyAnalyzer = dailyAnalyzer;
        this.adjustmentHandler = adjustmentHandler;
        this.instrumentMaster = instrumentMaster;
        this.brokerRegistry = brokerRegistry;
        this.positionManager = positionManager;
        this.telegramBot = telegramBot;
        this.dbService = dbService;
        this.underlyings = Arrays.stream(underlyingsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        this.maxConcurrent = maxConcurrent;
        this.discoveryWindowMins = discoveryWindowMins;
        this.minStraddlePctOfSpot = minStraddlePctOfSpot;
        this.minOI = minOI;
        this.maxBidAskSpreadPct = maxBidAskSpreadPct;
    }

    @PostConstruct
    public void init() {
        dbService.initSchema();
        dbService.findActivePositions()
            .doOnNext(pos -> {
                String key = pos.underlying().toUpperCase();
                activePositions.put(key, pos);
                log.info("[IronFly] Restored active position for {}", key);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                null,
                e -> log.warn("[IronFly] Error hydrating active positions on startup: {}", e.getMessage())
            );
    }

    /**
     * Sends Iron Fly entry recommendations for candidate underlyings.
     * Evaluates ATM straddle premium, OI, spread, and generates short + hedge strikes.
     */
    public Mono<Void> sendRecommendations() {
        log.info("[IronFly] Evaluating entry recommendations for: {}", underlyings);
        return Flux.fromIterable(underlyings)
            .filter(u -> {
                IronFlyPosition existing = activePositions.get(u.toUpperCase());
                return existing == null || existing.status() == IronFlyStatus.CLOSED;
            })
            .flatMap(this::evaluateAndRecommend)
            .then();
    }

    private Mono<Void> evaluateAndRecommend(String underlying) {
        LocalDate expiry = getNextMonthlyExpiry(underlying);
        return optionChainProvider.getOptionChain(underlying, expiry.toString())
            .zipWith(optionChainProvider.getSpotPrice(underlying))
            .flatMap(tuple -> {
                OptionChain chain = tuple.getT1();
                double spot = tuple.getT2();
                if (chain == null || chain.isEmpty()) {
                    log.warn("[IronFly] No option chain data for {} (expiry {}) — no contracts matched; "
                        + "check instrument master sync / expiry resolution", underlying, expiry);
                    return Mono.empty();
                }
                if (spot <= 0) {
                    log.warn("[IronFly] No spot price for {} — quote API unavailable", underlying);
                    return Mono.empty();
                }

                int strikeStep = determineStrikeStep(underlying, spot);
                int atmStrike = (int) Math.round(spot / strikeStep) * strikeStep;

                StrikeQuote ceQuote = chain.getCall(atmStrike);
                StrikeQuote peQuote = chain.getPut(atmStrike);
                if (ceQuote == null || peQuote == null) {
                    log.warn("[IronFly] Missing quotes for ATM {} on {}", atmStrike, underlying);
                    return Mono.empty();
                }

                BigDecimal straddlePremium = ceQuote.ltp().add(peQuote.ltp());
                double straddlePct = straddlePremium.doubleValue() / spot * 100.0;
                if (straddlePct < minStraddlePctOfSpot) {
                    log.info("[IronFly] {} skipped: straddle {}% < {}%",
                        underlying, String.format("%.1f", straddlePct), String.format("%.1f", minStraddlePctOfSpot));
                    return Mono.empty();
                }
                if (ceQuote.openInterest() < minOI || peQuote.openInterest() < minOI) {
                    log.info("[IronFly] {} skipped: OI below threshold", underlying);
                    return Mono.empty();
                }

                int roundedOffset = (int) (Math.round(straddlePremium.doubleValue() / strikeStep) * strikeStep);
                if (roundedOffset < strikeStep) roundedOffset = strikeStep;

                int longCallStrike = atmStrike + roundedOffset;
                int longPutStrike = atmStrike - roundedOffset;

                // Snap to nearest available strikes in chain if exact strike is absent
                if (!chain.calls().containsKey(longCallStrike)) {
                    final int targetCall = longCallStrike;
                    longCallStrike = chain.calls().keySet().stream()
                        .filter(s -> s >= targetCall)
                        .min(Integer::compareTo)
                        .orElse(targetCall);
                }
                if (!chain.puts().containsKey(longPutStrike)) {
                    final int targetPut = longPutStrike;
                    longPutStrike = chain.puts().keySet().stream()
                        .filter(s -> s <= targetPut)
                        .max(Integer::compareTo)
                        .orElse(targetPut);
                }

                StrikeQuote longCallQuote = chain.getCall(longCallStrike);
                StrikeQuote longPutQuote = chain.getPut(longPutStrike);
                double netCredit = straddlePremium.doubleValue()
                    - (longCallQuote != null ? longCallQuote.ltp().doubleValue() : 0.0)
                    - (longPutQuote != null ? longPutQuote.ltp().doubleValue() : 0.0);

                String msg = formatRecommendation(underlying, atmStrike, spot, straddlePremium.doubleValue(),
                    longCallStrike, longPutStrike, netCredit, expiry);

                log.info("[IronFly] Recommendation generated for {}: ATM {} net credit {}",
                    underlying, atmStrike, String.format("%.2f", netCredit));
                return telegramBot.sendAlert(msg).then();
            })
            .onErrorResume(e -> {
                log.error("[IronFly] Error evaluating {}: {}", underlying, e.getMessage(), e);
                return Mono.empty();
            });
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
                    IronFlyPosition existing = activePositions.get(underlying.toUpperCase());
                    if (existing != null && existing.status() != IronFlyStatus.CLOSED) continue;
                    discoverForUnderlying(underlying, positions);
                }
            })
            .then();
    }

    private void discoverForUnderlying(String underlying, List<Position> brokerPositions) {
        String uUpper = underlying.toUpperCase();
        List<Position> optionLegs = brokerPositions.stream()
            .filter(p -> p.symbol() != null && p.symbol().toUpperCase().contains(uUpper))
            .filter(p -> p.netQuantity() != 0)
            .toList();

        if (optionLegs.size() < 4) {
            log.debug("[IronFly] {} has {} option legs (need 4), skipping", underlying, optionLegs.size());
            return;
        }

        log.info("[IronFly] Found {} option legs for {}", optionLegs.size(), underlying);

        OptionLeg shortCall = null;
        OptionLeg shortPut = null;
        OptionLeg longCallHedge = null;
        OptionLeg longPutHedge = null;

        for (Position p : optionLegs) {
            String sym = p.symbol().toUpperCase();
            boolean isCall = sym.endsWith("CE") || sym.contains("CE");
            boolean isPut = sym.endsWith("PE") || sym.contains("PE");
            boolean isShort = p.netQuantity() < 0;
            int lotSize = Math.abs(p.netQuantity());
            BigDecimal entryPrice = isShort
                ? (p.sellAveragePrice() != null && p.sellAveragePrice().compareTo(BigDecimal.ZERO) > 0 ? p.sellAveragePrice() : p.buyAveragePrice())
                : (p.buyAveragePrice() != null && p.buyAveragePrice().compareTo(BigDecimal.ZERO) > 0 ? p.buyAveragePrice() : p.sellAveragePrice());
            if (entryPrice == null) entryPrice = BigDecimal.ZERO;
            BigDecimal currentPrice = p.ltp() != null && p.ltp().compareTo(BigDecimal.ZERO) > 0 ? p.ltp() : entryPrice;
            int strike = extractStrike(p.symbol());

            OptionType type = isCall ? OptionType.CE : OptionType.PE;
            OptionLeg leg = new OptionLeg(p.symbol(), strike, type, isShort, entryPrice, currentPrice, 0.0, lotSize);

            if (isShort && isCall && shortCall == null) shortCall = leg;
            else if (isShort && isPut && shortPut == null) shortPut = leg;
            else if (!isShort && isCall && longCallHedge == null) longCallHedge = leg;
            else if (!isShort && isPut && longPutHedge == null) longPutHedge = leg;
        }

        BigDecimal netCredit = BigDecimal.ZERO;
        if (shortCall != null && shortPut != null) {
            BigDecimal shortCredits = shortCall.entryPrice().add(shortPut.entryPrice());
            BigDecimal longDebits = (longCallHedge != null ? longCallHedge.entryPrice() : BigDecimal.ZERO)
                .add(longPutHedge != null ? longPutHedge.entryPrice() : BigDecimal.ZERO);
            netCredit = shortCredits.subtract(longDebits);
        }

        int totalLots = shortCall != null ? shortCall.lotSize() : (optionLegs.get(0) != null ? Math.abs(optionLegs.get(0).netQuantity()) : 0);
        BigDecimal entrySpot = shortCall != null ? BigDecimal.valueOf(shortCall.strike()) : BigDecimal.ZERO;

        IronFlyPosition position = new IronFlyPosition(
            uUpper, shortCall, shortPut, longCallHedge, longPutHedge,
            entrySpot, netCredit, netCredit,
            totalLots, IronFlyStatus.DISCOVERED, Instant.now(), null, List.of()
        );

        activePositions.put(uUpper, position);
        dbService.savePosition(position)
            .doOnNext(id -> {
                positionDbIds.put(uUpper, id);
                log.info("[IronFly] {} position discovered and saved with rehydrated legs (DB ID: {})", uUpper, id);
            })
            .subscribe();
    }

    private int extractStrike(String symbol) {
        if (symbol == null) return 0;
        Matcher m = OPTION_SYMBOL_PATTERN.matcher(symbol.trim());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * Refreshes active position leg quotes with live market prices prior to daily evaluation.
     */
    public Mono<IronFlyPosition> refreshPositionQuotes(IronFlyPosition position) {
        if (position == null || position.status() == IronFlyStatus.CLOSED) {
            return Mono.justOrEmpty(position);
        }

        LocalDate expiry = getNextMonthlyExpiry(position.underlying());
        return optionChainProvider.getOptionChain(position.underlying(), expiry.toString())
            .zipWith(optionChainProvider.getSpotPrice(position.underlying()))
            .map(tuple -> {
                OptionChain chain = tuple.getT1();
                double liveSpot = tuple.getT2();

                OptionLeg shortCall = refreshLeg(position.shortCall(), chain);
                OptionLeg shortPut = refreshLeg(position.shortPut(), chain);
                OptionLeg longCall = refreshLeg(position.longCallHedge(), chain);
                OptionLeg longPut = refreshLeg(position.longPutHedge(), chain);

                BigDecimal entrySpot = position.entrySpotPrice() != null && position.entrySpotPrice().compareTo(BigDecimal.ZERO) > 0
                    ? position.entrySpotPrice()
                    : (liveSpot > 0 ? BigDecimal.valueOf(liveSpot) : BigDecimal.ZERO);

                return new IronFlyPosition(
                    position.underlying(), shortCall, shortPut, longCall, longPut,
                    entrySpot, position.netCredit(), position.originalCredit(),
                    position.totalLotSize(), position.status(), position.createdAt(), position.closedAt(), position.adjustmentHistory()
                );
            })
            .defaultIfEmpty(position);
    }

    private OptionLeg refreshLeg(OptionLeg leg, OptionChain chain) {
        if (leg == null) return null;
        StrikeQuote quote = chain.getQuote(leg.strike(), leg.optionType());
        if (quote != null && quote.ltp() != null && quote.ltp().compareTo(BigDecimal.ZERO) > 0) {
            return new OptionLeg(
                leg.symbol(), leg.strike(), leg.optionType(), leg.isShort(),
                leg.entryPrice(), quote.ltp(), quote.delta() != 0.0 ? quote.delta() : leg.delta(), leg.lotSize()
            );
        }
        return leg;
    }

    public Mono<Void> runDailyEvaluation() {
        log.info("[IronFly] Running daily evaluation on {} active positions", activePositions.size());
        return Flux.fromIterable(new ArrayList<>(activePositions.entrySet()))
            .filter(entry -> entry.getValue().status() != IronFlyStatus.CLOSED)
            .flatMap(entry -> refreshPositionQuotes(entry.getValue())
                .doOnNext(updatedPos -> {
                    String underlying = entry.getKey();
                    activePositions.put(underlying, updatedPos);
                    int daysToExpiry = dailyAnalyzer.getDaysToExpiry(getNextMonthlyExpiry(underlying));
                    DailyAnalyzer.EvaluationResult result = dailyAnalyzer.evaluate(updatedPos, daysToExpiry);

                    if (result.isExit()) {
                        sendExitAlert(underlying, result);
                    } else if (result.isAdjust()) {
                        String action = result.action().name();
                        String side = (action.contains("LONG_CALL") || action.contains("SHORT_CALL")) ? "CALL" : "PUT";
                        sendAdjustmentAlert(underlying, result, side);
                    }
                })
            )
            .then(Mono.fromRunnable(this::sendDailySummary));
    }

    private void sendDailySummary() {
        StringBuilder summary = new StringBuilder("📊 *IRON FLY DAILY SUMMARY*\n\n");
        boolean hasPositions = false;
        for (Map.Entry<String, IronFlyPosition> entry : activePositions.entrySet()) {
            String underlying = entry.getKey();
            IronFlyPosition position = entry.getValue();
            if (position.status() == IronFlyStatus.CLOSED) continue;
            hasPositions = true;
            int daysToExpiry = dailyAnalyzer.getDaysToExpiry(getNextMonthlyExpiry(underlying));
            summary.append(String.format("*%s* [%s]\n  Credit: ₹%.2f | MTM: ₹%.2f\n  BE: ₹%.2f / ₹%.2f | DTE: %d\n\n",
                underlying, position.status(),
                position.getCurrentNetCredit(), position.getTotalMtm(),
                position.getUpperBreakeven(), position.getLowerBreakeven(), daysToExpiry));
        }
        if (hasPositions) {
            telegramBot.sendAlert(summary.toString()).subscribe();
        }
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

        int strikeStep = determineStrikeStep(key, spot);
        int atmStrike = (int) Math.round(spot / strikeStep) * strikeStep;
        double deployedMargin = spot * lotSize * 0.25;
        double targetAmount = deployedMargin * 0.04;
        double slAmount = deployedMargin * 0.08;

        IronFlyPosition position = new IronFlyPosition(
            key, null, null, null, null,
            BigDecimal.valueOf(spot),   // Correct: entrySpotPrice
            BigDecimal.valueOf(credit), // Correct: netCredit
            BigDecimal.valueOf(credit), // Correct: originalCredit
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
        String key = underlying.toUpperCase();
        String msg = String.format(
            "⚠️ *IRON FLY EXIT SIGNAL — %s*\n\n*Trigger:* %s\n*Reason:* %s\n\n_Close all 4 legs now on your broker._",
            underlying, result.action(), result.reason());
        telegramBot.sendAlert(msg).subscribe();

        IronFlyPosition pos = activePositions.get(key);
        if (pos != null) {
            IronFlyPosition closedPos = new IronFlyPosition(
                pos.underlying(), pos.shortCall(), pos.shortPut(), pos.longCallHedge(), pos.longPutHedge(),
                pos.entrySpotPrice(), pos.netCredit(), pos.originalCredit(),
                pos.totalLotSize(), IronFlyStatus.CLOSED, pos.createdAt(), Instant.now(), pos.adjustmentHistory()
            );
            activePositions.put(key, closedPos);
            Long dbId = positionDbIds.get(key);
            if (dbId != null) {
                dbService.closePosition(dbId).subscribe();
                log.info("[IronFly] Closed position persisted to DB for {} (DB ID: {})", key, dbId);
            }
        }
    }

    private void sendAdjustmentAlert(String underlying, DailyAnalyzer.EvaluationResult result, String side) {
        String action = result.action().name();
        boolean isHedge = action.contains("LONG");
        AdjustmentSide adjSide = "CALL".equalsIgnoreCase(side) ? AdjustmentSide.CALL : AdjustmentSide.PUT;
        String key = underlying.toUpperCase();

        optionChainProvider.getSpotPrice(underlying)
            .flatMap(spot -> {
                LocalDate expiry = getNextMonthlyExpiry(key);
                return optionChainProvider.getOptionChain(key, expiry.toString())
                    .doOnNext(chain -> {
                        IronFlyPosition pos = activePositions.get(key);
                        BigDecimal currentNetCredit = pos != null ? pos.getCurrentNetCredit() : BigDecimal.ZERO;
                        int lotSize = pos != null ? pos.totalLotSize() : 25;

                        AdjustmentHandler.AdjustmentStrikeSelection selection = adjustmentHandler.selectStrikes(
                            chain, adjSide, spot, currentNetCredit, lotSize
                        );

                        String actionType = isHedge ? "Sell profitable hedge" : "Buy back losing leg";
                        String msg = String.format(
                            "🔧 *IRON FLY ADJUSTMENT — %s*\n\n*Trigger:* %s\n*Reason:* %s\n\n*Action:* %s %s, sell new %s @ %d (premium ₹%.2f), buy hedge @ %d (premium ₹%.2f)\n*Credit Delta:* ₹%.2f\n\n_Please adjust manually._",
                            underlying, result.action(), result.reason(), actionType, side, side,
                            selection.newShortStrike(), selection.shortPremium(),
                            selection.newLongStrike(), selection.hedgePremium(),
                            selection.creditDelta());
                        telegramBot.sendAlert(msg).subscribe();

                        // Persist adjustment and update status
                        Long dbId = positionDbIds.get(key);
                        if (dbId != null && pos != null) {
                            OptionLeg newShortCall = pos.shortCall();
                            OptionLeg newLongCallHedge = pos.longCallHedge();
                            OptionLeg newShortPut = pos.shortPut();
                            OptionLeg newLongPutHedge = pos.longPutHedge();

                            if (adjSide == AdjustmentSide.CALL) {
                                newShortCall = new OptionLeg(
                                    underlying + "_" + selection.newShortStrike() + "CE",
                                    selection.newShortStrike(),
                                    OptionType.CE,
                                    true,
                                    selection.shortPremium(),
                                    selection.shortPremium(),
                                    0.25,
                                    lotSize
                                );
                                newLongCallHedge = new OptionLeg(
                                    underlying + "_" + selection.newLongStrike() + "CE",
                                    selection.newLongStrike(),
                                    OptionType.CE,
                                    false,
                                    selection.hedgePremium(),
                                    selection.hedgePremium(),
                                    0.10,
                                    lotSize
                                );
                            } else {
                                newShortPut = new OptionLeg(
                                    underlying + "_" + selection.newShortStrike() + "PE",
                                    selection.newShortStrike(),
                                    OptionType.PE,
                                    true,
                                    selection.shortPremium(),
                                    selection.shortPremium(),
                                    -0.25,
                                    lotSize
                                );
                                newLongPutHedge = new OptionLeg(
                                    underlying + "_" + selection.newLongStrike() + "PE",
                                    selection.newLongStrike(),
                                    OptionType.PE,
                                    false,
                                    selection.hedgePremium(),
                                    selection.hedgePremium(),
                                    -0.10,
                                    lotSize
                                );
                            }

                            IronFlyPosition adjustedPos = new IronFlyPosition(
                                pos.underlying(), newShortCall, newShortPut, newLongCallHedge, newLongPutHedge,
                                pos.entrySpotPrice(), pos.netCredit().add(selection.creditDelta()), pos.originalCredit(),
                                pos.totalLotSize(), IronFlyStatus.ADJUSTED, pos.createdAt(), pos.closedAt(), pos.adjustmentHistory()
                            );
                            activePositions.put(key, adjustedPos);
                            dbService.updatePositionStatus(dbId, "ADJUSTED", adjustedPos.getCurrentNetCredit().doubleValue()).subscribe();
                            AdjustmentRecord rec = new AdjustmentRecord(
                                adjSide, Instant.now(), pos.getAtmStrike(), selection.newShortStrike(),
                                0, selection.newLongStrike(), selection.creditDelta(), result.reason()
                            );
                            dbService.saveAdjustment(dbId, rec).subscribe();
                            log.info("[IronFly] Adjustment persisted to DB for {} (DB ID: {})", key, dbId);
                        }
                    });
            })
            .subscribe();
    }

    private int determineStrikeStep(String underlying, double spot) {
        if (underlying != null) {
            String u = underlying.toUpperCase();
            if (u.contains("BANKNIFTY")) return 100;
            if (u.contains("NIFTY") || u.contains("FINNIFTY")) return 50;
        }
        if (spot >= 10000) return 100;
        if (spot >= 5000) return 50;
        if (spot >= 2000) return 20;
        if (spot >= 1000) return 10;
        if (spot >= 500) return 5;
        return 50;
    }

    private String formatRecommendation(
        String underlying, int atmStrike, double spot, double straddlePremium,
        int longCallStrike, int longPutStrike, double netCredit, LocalDate expiry
    ) {
        return String.format(
            "🦅 *IRON FLY RECOMMENDATION — %s*\n*Expiry:* %s\n*ATM Strike:* %d (Spot: ₹%.2f)\n*Straddle:* ₹%.2f\n\n*Entry:*\n• Sell %d CE\n• Sell %d PE\n• Buy %d CE\n• Buy %d PE\n\n*Net Credit:* ₹%.2f\n*BE:* ₹%.2f / ₹%.2f\n\n_Execute manually on your broker._",
            underlying, expiry.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
            atmStrike, spot, straddlePremium,
            atmStrike, atmStrike, longCallStrike, longPutStrike,
            netCredit, atmStrike + netCredit, atmStrike - netCredit);
    }

    /**
     * Monthly expiry resolved from the instrument master (the contract dump is the source
     * of truth — NSE monthly expiries are the last TUESDAY of the month since Sep 2025,
     * and a hard-coded weekday silently breaks whenever NSE changes the regime again).
     * Falls back to the last-Tuesday heuristic when the master is unavailable.
     */
    private LocalDate getNextMonthlyExpiry(String underlying) {
        LocalDate today = LocalDate.now(IST_ZONE);
        if (underlying != null && instrumentMaster != null) {
            try {
                List<String> expiries = instrumentMaster.findUpcomingExpiries(underlying.toUpperCase(), "CE", 12)
                    .collectList()
                    .block(java.time.Duration.ofSeconds(3));
                if (expiries != null && !expiries.isEmpty()) {
                    // Monthly contract = the last expiry of the month (weekly expiries precede it).
                    LocalDate thisMonth = lastExpiryInMonth(expiries, today);
                    if (thisMonth != null && !today.isAfter(thisMonth)) {
                        return thisMonth;
                    }
                    LocalDate nextMonth = lastExpiryInMonth(expiries, today.plusMonths(1));
                    if (nextMonth != null) {
                        return nextMonth;
                    }
                }
            } catch (Exception e) {
                log.warn("[IronFly] Monthly expiry lookup failed for {}: {} — using last-Tuesday fallback",
                    underlying, e.getMessage());
            }
        }
        LocalDate expiry = lastWeekdayOfMonth(today, java.time.DayOfWeek.TUESDAY);
        if (today.isAfter(expiry)) {
            expiry = lastWeekdayOfMonth(today.plusMonths(1), java.time.DayOfWeek.TUESDAY);
        }
        return expiry;
    }

    /** Returns the latest expiry date in the given month from the list, or null if none. */
    private static LocalDate lastExpiryInMonth(List<String> expiries, LocalDate month) {
        LocalDate best = null;
        for (String e : expiries) {
            try {
                LocalDate d = LocalDate.parse(e);
                if (d.getYear() == month.getYear() && d.getMonth() == month.getMonth()
                    && (best == null || d.isAfter(best))) {
                    best = d;
                }
            } catch (Exception ignored) {
                // malformed expiry row — skip
            }
        }
        return best;
    }

    /** Returns the last occurrence of the given weekday within the month of the supplied date. */
    private static LocalDate lastWeekdayOfMonth(LocalDate dayInMonth, java.time.DayOfWeek dow) {
        LocalDate d = dayInMonth.withDayOfMonth(dayInMonth.lengthOfMonth());
        while (d.getDayOfWeek() != dow) {
            d = d.minusDays(1);
        }
        return d;
    }
}
