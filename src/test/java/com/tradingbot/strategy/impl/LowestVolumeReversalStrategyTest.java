package com.tradingbot.strategy.impl;

import com.tradingbot.instrument.LotSizeService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LowestVolumeReversalStrategyTest {

    private LowestVolumeReversalStrategy strategy;
    private List<Signal> emittedSignals;
    private NseIndiaClient nseClient;
    private LotSizeService lotSizeService;

    @BeforeEach
    void setUp() {
        emittedSignals = new ArrayList<>();
        nseClient = mock(NseIndiaClient.class);
        when(nseClient.fetchGainers()).thenReturn(Mono.just(List.of()));
        when(nseClient.fetchLosers()).thenReturn(Mono.just(List.of()));

        lotSizeService = mock(LotSizeService.class);
        when(lotSizeService.getOrderQuantity("NSE:RELIANCE")).thenReturn(500);
        when(lotSizeService.getOrderQuantity("NSE:TCS")).thenReturn(350);
        when(lotSizeService.getOrderQuantity("NSE:INFY")).thenReturn(400);

        strategy = new LowestVolumeReversalStrategy(
            "LVR_TEST", "KITE_123", "NSE:RELIANCE,NSE:TCS,NSE:INFY",
            2, 2.0, 2, nseClient, lotSizeService
        );

        StrategyContext context = new StrategyContext() {
            @Override public String getStrategyId() { return "LVR_TEST"; }
            @Override public String getAssignedAccountId() { return "KITE_123"; }
            @Override public void emitSignal(Signal signal) { emittedSignals.add(signal); }
            @Override public Optional<Candle> getLastCandle(String s, String tf) { return Optional.empty(); }
            @Override public List<Candle> getHistoricalCandles(String s, String tf, int n) { return Collections.emptyList(); }
            @Override public double[] getClosePrices(String s, String tf) { return new double[0]; }
            @Override public Instant now() { return Instant.now(); }
        };
        strategy.init(context);
    }

    @Test
    void testCompleteLongTradeLifecycle() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:RELIANCE";

        strategy.onCandle(c(sym, t0, 3000, 3010, 2990, 3005, 5000));
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertEquals(1, st.dayCandles.size());

        // GREEN momentum x2
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3005, 3030, 3000, 3025, 6000));
        assertEquals(LowestVolumeReversalStrategy.Direction.LONG, st.pendingDirection);
        assertEquals(1, st.consecutiveMomentum);

        strategy.onCandle(c(sym, t0.plusSeconds(600), 3025, 3050, 3020, 3045, 7000));
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_PULLBACK, st.setupPhase);

        // RED pullback, lowest vol
        strategy.onCandle(c(sym, t0.plusSeconds(900), 3045, 3048, 3030, 3035, 1000));
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_ENTRY, st.setupPhase);
        assertNotNull(st.pullbackCandle);

        // Entry trigger: close > pullback high (3048)
        strategy.onCandle(c(sym, t0.plusSeconds(1200), 3040, 3070, 3038, 3065, 8000));
        assertEquals(LowestVolumeReversalStrategy.TradePosition.IN_TRADE, st.position);
        assertEquals(1, emittedSignals.size());
        assertEquals(SignalType.ENTRY_LONG, emittedSignals.get(0).signalType());

        // 1:2 RR partial: target = 3048 + 2*(3048-3030) = 3084
        strategy.onTick(Tick.builder().symbol(sym).ltp(new BigDecimal("3090")).timestamp(t0.plusSeconds(1230)).build());
        assertEquals(2, emittedSignals.size());
        assertEquals(SignalType.EXIT_PARTIAL_LONG, emittedSignals.get(1).signalType());
        assertEquals(250, emittedSignals.get(1).quantity()); // 500 / 2 = 250 (2 lots of 250)
        assertTrue(st.partialExitBooked);
        assertEquals(st.entryPrice, st.trailingStopLoss);

        // Trailing stop hit
        strategy.onTick(Tick.builder().symbol(sym).ltp(new BigDecimal("3045")).timestamp(t0.plusSeconds(1260)).build());
        assertEquals(3, emittedSignals.size());
        assertEquals(SignalType.EXIT_LONG, emittedSignals.get(2).signalType());
        assertEquals(LowestVolumeReversalStrategy.TradePosition.FLAT, st.position);
    }

    @Test
    void testCompleteShortTradeLifecycle() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:TCS";

        strategy.onCandle(c(sym, t0, 3500, 3510, 3490, 3505, 4000));
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3505, 3480, 3470, 3475, 5000));

        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertEquals(LowestVolumeReversalStrategy.Direction.SHORT, st.pendingDirection);

        strategy.onCandle(c(sym, t0.plusSeconds(600), 3475, 3460, 3440, 3445, 6000));
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_PULLBACK, st.setupPhase);

        // GREEN pullback, lowest vol
        strategy.onCandle(c(sym, t0.plusSeconds(900), 3445, 3460, 3440, 3455, 800));
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_ENTRY, st.setupPhase);

        // Entry trigger: close < pullback low (3440)
        strategy.onCandle(c(sym, t0.plusSeconds(1200), 3450, 3455, 3420, 3425, 7000));
        assertEquals(LowestVolumeReversalStrategy.TradePosition.IN_TRADE, st.position);
        assertEquals(SignalType.ENTRY_SHORT, emittedSignals.get(0).signalType());

        // 1:2 RR partial: target = 3440 - 2*(3460-3440) = 3400
        strategy.onTick(Tick.builder().symbol(sym).ltp(new BigDecimal("3390")).timestamp(t0.plusSeconds(1230)).build());
        assertEquals(SignalType.EXIT_PARTIAL_SHORT, emittedSignals.get(1).signalType());
        assertTrue(st.partialExitBooked);

        // Trailing stop hit
        strategy.onTick(Tick.builder().symbol(sym).ltp(new BigDecimal("3450")).timestamp(t0.plusSeconds(1260)).build());
        assertEquals(SignalType.EXIT_SHORT, emittedSignals.get(2).signalType());
        assertEquals(LowestVolumeReversalStrategy.TradePosition.FLAT, st.position);
    }

    @Test
    void testFirstCandleDisqualification() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:RELIANCE";

        // First candle moves 7% (3000 -> 3210)
        strategy.onCandle(c(sym, t0, 3000, 3210, 2990, 3200, 10000));
        assertTrue(strategy.getState(sym).disqualified);

        strategy.onCandle(c(sym, t0.plusSeconds(300), 3200, 3220, 3190, 3215, 5000));
        assertEquals(0, emittedSignals.size());
    }

    @Test
    void testNonLowestVolumePullbackSkipped() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:RELIANCE";

        strategy.onCandle(c(sym, t0, 3000, 3010, 2990, 3005, 5000));
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3005, 3030, 3000, 3025, 6000));
        strategy.onCandle(c(sym, t0.plusSeconds(600), 3025, 3050, 3020, 3045, 7000));

        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_PULLBACK, st.setupPhase);

        // RED pullback but vol 8000 is NOT lowest (5000, 6000, 7000 exist)
        strategy.onCandle(c(sym, t0.plusSeconds(900), 3045, 3048, 3030, 3035, 8000));
        assertEquals(LowestVolumeReversalStrategy.SetupPhase.WAITING_FOR_PULLBACK, st.setupPhase);
        assertNull(st.pullbackCandle);
    }

    @Test
    void testHardExitAt1500UsesLastTradedPriceNotHigh() {
        String sym = "NSE:RELIANCE";
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        st.position = LowestVolumeReversalStrategy.TradePosition.IN_TRADE;
        st.direction = LowestVolumeReversalStrategy.Direction.LONG;
        st.remainingQuantity = 10;
        st.highestPrice = new BigDecimal("3050");   // must NOT be used for exit price
        st.lastLtp = new BigDecimal("3030");          // last traded price

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.LVR_HARD_EXIT));

        assertEquals(1, emittedSignals.size());
        Signal exit = emittedSignals.get(0);
        assertEquals(SignalType.EXIT_LONG, exit.signalType());
        assertEquals("LVR_EXIT_HARD_EXIT_15:00", exit.tag());
        assertEquals(0, new BigDecimal("3030").compareTo(exit.price())); // uses lastLtp, not highestPrice
        assertEquals(com.tradingbot.model.enums.OrderType.MARKET, exit.orderType());
        assertEquals(com.tradingbot.model.enums.ProductType.MIS, exit.productType());
        assertEquals(com.tradingbot.model.enums.BookType.INTRADAY, exit.bookType());
        assertEquals(LowestVolumeReversalStrategy.TradePosition.FLAT, st.position);
    }

    @Test
    void testHardExitShortUsesLastTradedPrice() {
        String sym = "NSE:TCS";
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        st.position = LowestVolumeReversalStrategy.TradePosition.IN_TRADE;
        st.direction = LowestVolumeReversalStrategy.Direction.SHORT;
        st.remainingQuantity = 8;
        st.highestPrice = new BigDecimal("3500");   // wrong price for a short exit
        st.lastLtp = new BigDecimal("3440");          // last traded price

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.LVR_HARD_EXIT));

        assertEquals(1, emittedSignals.size());
        Signal exit = emittedSignals.get(0);
        assertEquals(SignalType.EXIT_SHORT, exit.signalType());
        assertEquals(0, new BigDecimal("3440").compareTo(exit.price())); // uses lastLtp
        assertEquals(com.tradingbot.model.enums.OrderType.MARKET, exit.orderType());
        assertEquals(com.tradingbot.model.enums.ProductType.MIS, exit.productType());
        assertEquals(com.tradingbot.model.enums.BookType.INTRADAY, exit.bookType());
    }

    @Test
    void testHardExitFallsBackToLastCandleWhenNoTick() {
        String sym = "NSE:INFY";
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        st.position = LowestVolumeReversalStrategy.TradePosition.IN_TRADE;
        st.direction = LowestVolumeReversalStrategy.Direction.LONG;
        st.remainingQuantity = 5;
        st.lastLtp = null; // no tick seen
        st.dayCandles.add(c(sym, Instant.parse("2024-12-18T09:30:00Z"), 1500, 1510, 1490, 1505, 5000));

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.LVR_HARD_EXIT));

        assertEquals(1, emittedSignals.size());
        // Falls back to last candle close (1505), not highestPrice (null/0)
        assertEquals(0, new BigDecimal("1505").compareTo(emittedSignals.get(0).price()));
    }

    @Test
    void testIntradaySquareOffStillClosesPositions() {
        String sym = "NSE:RELIANCE";
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        st.position = LowestVolumeReversalStrategy.TradePosition.IN_TRADE;
        st.direction = LowestVolumeReversalStrategy.Direction.LONG;
        st.remainingQuantity = 10;
        st.lastLtp = new BigDecimal("3030");

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_SQUARE_OFF));

        assertEquals(1, emittedSignals.size());
        assertEquals(SignalType.EXIT_LONG, emittedSignals.get(0).signalType());
    }

    @Test
    void testMomentumDirectionChangeResets() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:RELIANCE";

        strategy.onCandle(c(sym, t0, 3000, 3010, 2990, 3005, 5000));
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3005, 3030, 3000, 3025, 6000));

        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertEquals(LowestVolumeReversalStrategy.Direction.LONG, st.pendingDirection);
        assertEquals(1, st.consecutiveMomentum);

        // RED candle changes direction
        strategy.onCandle(c(sym, t0.plusSeconds(600), 3025, 3028, 3000, 3005, 5000));
        assertEquals(LowestVolumeReversalStrategy.Direction.SHORT, st.pendingDirection);
        assertEquals(1, st.consecutiveMomentum);
    }

    @Test
    void testDailyReset() {
        String sym = "NSE:RELIANCE";
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        st.position = LowestVolumeReversalStrategy.TradePosition.IN_TRADE;
        st.dayCandles.add(c(sym, Instant.now(), 3000, 3010, 2990, 3005, 5000));

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_CLOSE));

        assertEquals(LowestVolumeReversalStrategy.TradePosition.FLAT, st.position);
        assertTrue(st.dayCandles.isEmpty());
        assertFalse(st.disqualified);
    }

    @Test
    void testStrategyDisabledSkipsProcessing() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        strategy.setEnabled(false);

        strategy.onCandle(c("NSE:RELIANCE", t0, 3000, 3010, 2990, 3005, 5000));
        assertEquals(0, emittedSignals.size());
        assertTrue(strategy.getState("NSE:RELIANCE").dayCandles.isEmpty());
    }

    @Test
    void testIgnoresWrongTimeframe() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        Candle c15m = new Candle("NSE:RELIANCE", "15", t0,
            new BigDecimal("3000"), new BigDecimal("3010"), new BigDecimal("2990"),
            new BigDecimal("3005"), 5000L);
        strategy.onCandle(c15m);
        assertTrue(strategy.getState("NSE:RELIANCE").dayCandles.isEmpty());
    }

    @Test
    void testTopGainerRejectsShortMomentumAndAcceptsLong() {
        // Mock gainer selection
        com.tradingbot.nse.NseGainerLoser gainer = new com.tradingbot.nse.NseGainerLoser(
            "RELIANCE", "EQ", 3000, 50, 2.5, 2950, 3010, 2940, 2950, 100000L
        );
        when(nseClient.fetchGainers()).thenReturn(Mono.just(List.of(gainer)));
        when(nseClient.fetchLosers()).thenReturn(Mono.just(List.of()));

        // Trigger stock selection scan at 09:26
        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.STOCK_SELECTION_SCAN));
        assertTrue(strategy.getLongCandidates().contains("NSE:RELIANCE"));
        assertFalse(strategy.getShortCandidates().contains("NSE:RELIANCE"));

        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:RELIANCE";

        // First candle
        strategy.onCandle(c(sym, t0, 3000, 3010, 2990, 3005, 5000));

        // Red candle -> should NOT start SHORT momentum for a Top Gainer
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3005, 3010, 2980, 2985, 6000));
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertNull(st.pendingDirection);
        assertEquals(0, st.consecutiveMomentum);

        // Green candle -> DOES start LONG momentum for Top Gainer
        strategy.onCandle(c(sym, t0.plusSeconds(600), 2985, 3020, 2980, 3015, 7000));
        assertEquals(LowestVolumeReversalStrategy.Direction.LONG, st.pendingDirection);
        assertEquals(1, st.consecutiveMomentum);
    }

    @Test
    void testTopLoserRejectsLongMomentumAndAcceptsShort() {
        // Mock loser selection
        com.tradingbot.nse.NseGainerLoser loser = new com.tradingbot.nse.NseGainerLoser(
            "TCS", "EQ", 3500, -80, -2.5, 3580, 3590, 3490, 3580, 100000L
        );
        when(nseClient.fetchGainers()).thenReturn(Mono.just(List.of()));
        when(nseClient.fetchLosers()).thenReturn(Mono.just(List.of(loser)));

        // Trigger stock selection scan at 09:26
        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.STOCK_SELECTION_SCAN));
        assertTrue(strategy.getShortCandidates().contains("NSE:TCS"));
        assertFalse(strategy.getLongCandidates().contains("NSE:TCS"));

        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        String sym = "NSE:TCS";

        // First candle
        strategy.onCandle(c(sym, t0, 3500, 3510, 3490, 3505, 5000));

        // Green candle -> should NOT start LONG momentum for a Top Loser
        strategy.onCandle(c(sym, t0.plusSeconds(300), 3505, 3530, 3500, 3525, 6000));
        LowestVolumeReversalStrategy.SymbolState st = strategy.getState(sym);
        assertNull(st.pendingDirection);
        assertEquals(0, st.consecutiveMomentum);

        // Red candle -> DOES start SHORT momentum for Top Loser
        strategy.onCandle(c(sym, t0.plusSeconds(600), 3525, 3530, 3480, 3485, 7000));
        assertEquals(LowestVolumeReversalStrategy.Direction.SHORT, st.pendingDirection);
        assertEquals(1, st.consecutiveMomentum);
    }

    private Candle c(String sym, Instant ts, double o, double h, double l, double cl, long vol) {
        return new Candle(sym, "5", ts,
            BigDecimal.valueOf(o), BigDecimal.valueOf(h),
            BigDecimal.valueOf(l), BigDecimal.valueOf(cl), vol);
    }
}
