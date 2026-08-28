package com.tradingbot.nse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveGainersLosersIntegrationTest {

    @Test
    void testNseIndiaClientLiveFetch() {
        NseIndiaClient client = new NseIndiaClient(WebClient.builder(), new ObjectMapper());
        
        List<NseGainerLoser> gainers = client.fetchGainers().block();
        List<NseGainerLoser> losers = client.fetchLosers().block();

        assertNotNull(gainers, "Gainers list should not be null");
        assertNotNull(losers, "Losers list should not be null");

        System.out.println("=== JAVA CLIENT LIVE GAINERS (" + gainers.size() + ") ===");
        int i = 1;
        for (NseGainerLoser g : gainers.stream().limit(10).toList()) {
            System.out.printf("%d. %s: LTP ₹%.2f (%+.2f%%)%n", i++, g.symbol(), g.ltp(), g.pChange());
        }

        System.out.println("\n=== JAVA CLIENT LIVE LOSERS (" + losers.size() + ") ===");
        i = 1;
        for (NseGainerLoser l : losers.stream().limit(10).toList()) {
            System.out.printf("%d. %s: LTP ₹%.2f (%+.2f%%)%n", i++, l.symbol(), l.ltp(), l.pChange());
        }

        assertFalse(gainers.isEmpty(), "Live gainers should not be empty");
        assertFalse(losers.isEmpty(), "Live losers should not be empty");
        assertTrue(gainers.size() >= 10, "Should have at least 10 gainers");
        assertTrue(losers.size() >= 10, "Should have at least 10 losers");
    }
}
