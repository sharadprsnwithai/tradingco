package com.tradingbot.scheduler;

import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.RiskManager;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.telegram.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketClockSchedulerTest {

    private StrategyEngine strategyEngine;
    private PositionManagerService positionManager;
    private RiskManager riskManager;
    private BrokerAdapterRegistry brokerRegistry;
    private MarketDataHub marketDataHub;
    private ShoonyaHistoricalDataService historicalDataService;
    private TelegramBotService telegramBot;

    private MarketClockScheduler scheduler;

    @BeforeEach
    void setUp() {
        strategyEngine = mock(StrategyEngine.class);
        positionManager = mock(PositionManagerService.class);
        riskManager = mock(RiskManager.class);
        brokerRegistry = mock(BrokerAdapterRegistry.class);
        marketDataHub = mock(MarketDataHub.class);
        historicalDataService = mock(ShoonyaHistoricalDataService.class);
        telegramBot = mock(TelegramBotService.class);

        when(telegramBot.sendAlert(anyString())).thenReturn(Mono.empty());
        when(brokerRegistry.getAll()).thenReturn(Flux.empty());
        when(strategyEngine.getRegisteredStrategies()).thenReturn(List.of());
        when(historicalDataService.warmupSequentially(anyList())).thenReturn(Flux.empty());
        when(marketDataHub.getCandleAggregator()).thenReturn(new CandleAggregator());
        when(positionManager.executeEodIntradaySquareOff()).thenReturn(Mono.empty());

        scheduler = new MarketClockScheduler(
            strategyEngine,
            positionManager,
            riskManager,
            brokerRegistry,
            marketDataHub,
            historicalDataService,
            telegramBot
        );
    }

    @Test
    void testTradingDayAndHolidayFilter() {
        // Saturday / Sunday check
        LocalDate saturday = LocalDate.of(2025, 2, 22);
        LocalDate sunday = LocalDate.of(2025, 2, 23);
        LocalDate weekday = LocalDate.of(2025, 2, 24); // Monday

        assertFalse(scheduler.isTradingDay(saturday), "Saturday must not be a trading day");
        assertFalse(scheduler.isTradingDay(sunday), "Sunday must not be a trading day");
        assertTrue(scheduler.isTradingDay(weekday), "Regular weekday must be a trading day");

        // NSE 2025 Holiday Check: Mahashivratri (26 Feb 2025)
        LocalDate mahashivratri = LocalDate.of(2025, 2, 26);
        assertFalse(scheduler.isTradingDay(mahashivratri), "Mahashivratri must be recognized as exchange holiday");

        // Good Friday (18 April 2025)
        LocalDate goodFriday = LocalDate.of(2025, 4, 18);
        assertFalse(scheduler.isTradingDay(goodFriday), "Good Friday must be recognized as exchange holiday");
    }

    @Test
    void test1514AutomatedIntradaySquareOff() {
        scheduler.onIntradaySquareOff();

        verify(positionManager, times(1)).executeEodIntradaySquareOff();
        verify(telegramBot, times(1)).sendAlert(argThat(msg -> msg.contains("15:14 IST") && msg.contains("Square-Off")));
    }

    @Test
    void test1510IntradayEntryCutoff() {
        scheduler.onIntradayEntryCutoff();

        verify(strategyEngine, times(1)).dispatchSchedule(argThat(e -> ScheduledEvent.INTRADAY_ENTRY_CUTOFF.equals(e.eventType())));
        verify(telegramBot, times(1)).sendAlert(argThat(msg -> msg.contains("15:10 IST")));
    }

    @Test
    void test0915MarketOpen() {
        scheduler.onMarketOpen();

        verify(strategyEngine, times(1)).dispatchSchedule(argThat(e -> ScheduledEvent.MARKET_OPEN.equals(e.eventType())));
        verify(telegramBot, times(1)).sendAlert(argThat(msg -> msg.contains("09:15 IST") && msg.contains("Market Open")));
    }

    @Test
    void test0925PreMarketOiScan() {
        scheduler.onPreMarketOiScan();

        verify(strategyEngine, times(1)).dispatchSchedule(argThat(e -> ScheduledEvent.OI_SCAN.equals(e.eventType())));
        verify(strategyEngine, times(1)).syncSubscriptions();
        verify(telegramBot, times(1)).sendAlert(argThat(msg -> msg.contains("09:25 IST") && msg.contains("Top 5 OI Breakout")));
    }

    @Test
    void test1530MarketClose() {
        scheduler.onMarketClose();

        verify(strategyEngine, times(1)).dispatchSchedule(argThat(e -> ScheduledEvent.MARKET_CLOSE.equals(e.eventType())));
        verify(riskManager, times(1)).resetDailyStats();
        verify(telegramBot, times(1)).sendAlert(argThat(msg -> msg.contains("15:30 IST") && msg.contains("Market Closed")));
    }
}
