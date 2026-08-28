package com.tradingbot.strategy.ironfly;

import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.telegram.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IronFlyDecisionDataTest {

    private OptionChainProvider optionChainProvider;
    private TelegramBotService telegramBot;
    private IronFlyDbService dbService;
    private IronFlyService ironFlyService;

    @BeforeEach
    void setUp() {
        optionChainProvider = mock(OptionChainProvider.class);
        telegramBot = mock(TelegramBotService.class);
        dbService = mock(IronFlyDbService.class);

        when(telegramBot.sendAlert(anyString())).thenReturn(Mono.empty());
        when(dbService.findActivePositions()).thenReturn(reactor.core.publisher.Flux.empty());

        DailyAnalyzer dailyAnalyzer = new DailyAnalyzer(4.0, 8.0, 4, 70.0, 70.0, 50.0);
        AdjustmentHandler adjustmentHandler = new AdjustmentHandler(0.25, 0.5);

        ironFlyService = new IronFlyService(
            optionChainProvider,
            dailyAnalyzer,
            adjustmentHandler,
            mock(InstrumentMasterService.class),
            mock(com.tradingbot.adapter.BrokerAdapterRegistry.class),
            mock(com.tradingbot.position.PositionManagerService.class),
            telegramBot,
            dbService,
            "NIFTY,RELIANCE",
            3, 30, 2.0, 100, 1.5
        );
    }

    @Test
    void testDecidesCorrectMonthlySellAndBuyStrikes() {
        // NIFTY Spot = 24530.0
        when(optionChainProvider.getSpotPrice("NIFTY")).thenReturn(Mono.just(24530.0));

        // Mock Option Chain with ATM (24550), Call Hedge (25150), Put Hedge (23950)
        Map<Integer, StrikeQuote> calls = new HashMap<>();
        Map<Integer, StrikeQuote> puts = new HashMap<>();

        // ATM 24550: CE @ 310.0, PE @ 290.0 -> Straddle = 600.0 (offset = 600)
        calls.put(24550, new StrikeQuote(24550, OptionType.CE, BigDecimal.valueOf(310.0), BigDecimal.valueOf(310.0), BigDecimal.valueOf(311.0), 50000, 5000, 0.52, 0.001, -15.0, 12.0));
        puts.put(24550, new StrikeQuote(24550, OptionType.PE, BigDecimal.valueOf(290.0), BigDecimal.valueOf(290.0), BigDecimal.valueOf(291.0), 60000, 6000, -0.48, 0.001, -14.0, 11.5));

        // Long Call Hedge 25150 (ATM + 600) @ 55.0
        calls.put(25150, new StrikeQuote(25150, OptionType.CE, BigDecimal.valueOf(55.0), BigDecimal.valueOf(54.0), BigDecimal.valueOf(56.0), 30000, 3000, 0.15, 0.001, -7.0, 6.0));

        // Long Put Hedge 23950 (ATM - 600) @ 45.0
        puts.put(23950, new StrikeQuote(23950, OptionType.PE, BigDecimal.valueOf(45.0), BigDecimal.valueOf(44.0), BigDecimal.valueOf(46.0), 35000, 3500, -0.14, 0.001, -6.5, 5.5));

        OptionChain chain = new OptionChain("NIFTY", "2026-09-24", calls, puts);
        when(optionChainProvider.getOptionChain(eq("NIFTY"), anyString())).thenReturn(Mono.just(chain));

        // RELIANCE empty to isolate NIFTY
        when(optionChainProvider.getSpotPrice("RELIANCE")).thenReturn(Mono.just(0.0));
        when(optionChainProvider.getOptionChain(eq("RELIANCE"), anyString())).thenReturn(Mono.just(OptionChain.empty("RELIANCE", "2026-09-24")));

        ironFlyService.sendRecommendations().block();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramBot, times(1)).sendAlert(captor.capture());

        String message = captor.getValue();
        System.out.println("=== GENERATED RECOMMENDATION ALERT ===");
        System.out.println(message);

        // Verification of Sell and Buy strike decisions:
        assertTrue(message.contains("ATM Strike:* 24550"), "Should select ATM 24550");
        assertTrue(message.contains("Sell 24550 CE"), "Should sell ATM 24550 CE");
        assertTrue(message.contains("Sell 24550 PE"), "Should sell ATM 24550 PE");
        assertTrue(message.contains("Buy 25150 CE"), "Should buy Long Call Hedge at 25150");
        assertTrue(message.contains("Buy 23950 PE"), "Should buy Long Put Hedge at 23950");
        assertTrue(message.contains("Net Credit:* ₹500.00"), "Net credit should be 600 - 55 - 45 = 500");
        assertTrue(message.contains("BE:* ₹25050.00 / ₹24050.00"), "Breakevens should be 24550 ± 500");
    }
}
