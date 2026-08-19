package com.tradingbot.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Position;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.KillSwitchService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramBotServiceTest {

    private StrategyEngine strategyEngine;
    private OrderManagerService oms;
    private PositionManagerService positionManager;
    private KillSwitchService killSwitch;
    private MarketDataHub marketDataHub;
    private ObjectMapper objectMapper;

    private TelegramBotService botService;

    @BeforeEach
    void setUp() {
        strategyEngine = mock(StrategyEngine.class);
        oms = mock(OrderManagerService.class);
        positionManager = mock(PositionManagerService.class);
        killSwitch = mock(KillSwitchService.class);
        marketDataHub = mock(MarketDataHub.class);
        objectMapper = new ObjectMapper();

        when(strategyEngine.getSignalStream()).thenReturn(Flux.empty());
        when(killSwitch.activateGlobalPanic(anyString())).thenReturn(Mono.empty());
        when(marketDataHub.getActiveBroker()).thenReturn("ZERODHA");
        when(marketDataHub.isFailedOver()).thenReturn(false);
        when(oms.isPaperTrading()).thenReturn(true);
        when(killSwitch.isGlobalPanicActive()).thenReturn(false);

        botService = new TelegramBotService(
            strategyEngine,
            oms,
            positionManager,
            killSwitch,
            marketDataHub,
            "123456:MOCK_TOKEN",
            "999999",
            WebClient.builder(),
            objectMapper
        );
    }

    @Test
    void testHandleCallbackQueryPanic() {
        ObjectNode callback = objectMapper.createObjectNode();
        callback.put("id", "cb_123");
        callback.put("data", "CONFIRM_PANIC");

        botService.handleCallbackQuery(callback);

        verify(killSwitch, times(1)).activateGlobalPanic("Triggered by Telegram Operator");
    }

    @Test
    void testHandleCallbackQueryPauseAndResume() {
        ObjectNode pauseCb = objectMapper.createObjectNode();
        pauseCb.put("id", "cb_pause");
        pauseCb.put("data", "PAUSE_STRAT:VB_01");
        botService.handleCallbackQuery(pauseCb);
        verify(strategyEngine, times(1)).pauseStrategy("VB_01");

        ObjectNode resumeCb = objectMapper.createObjectNode();
        resumeCb.put("id", "cb_resume");
        resumeCb.put("data", "RESUME_STRAT:VB_01");
        botService.handleCallbackQuery(resumeCb);
        verify(strategyEngine, times(1)).resumeStrategy("VB_01");
    }

    @Test
    void testHandlePnlCommand() {
        Position p1 = Position.builder()
            .accountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .netQuantity(10)
            .mtmPnl(new BigDecimal("250.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .build();

        when(positionManager.getOpenIntradayPositions()).thenReturn(List.of(p1));
        when(positionManager.getOpenPositionalPositions()).thenReturn(List.of());

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("text", "/pnl");
        botService.handleMessage(msg);

        verify(positionManager, times(1)).getOpenIntradayPositions();
        verify(positionManager, times(1)).getOpenPositionalPositions();
    }
}
