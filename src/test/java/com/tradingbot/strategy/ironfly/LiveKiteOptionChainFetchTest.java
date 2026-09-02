package com.tradingbot.strategy.ironfly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LiveKiteOptionChainFetchTest {

    private void loadDotEnv() {
        File envFile = new File(".env");
        if (!envFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    System.setProperty(key, value);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not load .env: " + e.getMessage());
        }
    }

    @Test
    void testLiveNiftyOptionChainFetch() {
        loadDotEnv();

        KiteConfig config = new KiteConfig();
        config.setEnabled(Boolean.parseBoolean(System.getProperty("KITE_ENABLED", "true")));
        config.setUserId(System.getProperty("KITE_USER_ID", "TC4648"));
        config.setPassword(System.getProperty("KITE_PASSWORD", ""));
        config.setApiKey(System.getProperty("KITE_API_KEY", ""));
        config.setApiSecret(System.getProperty("KITE_API_SECRET", ""));
        config.setTotpSecret(System.getProperty("KITE_TOTP_SECRET", ""));
        config.setBaseUrl(System.getProperty("KITE_BASE_URL", "https://api.kite.trade"));
        config.setAccessToken(System.getProperty("KITE_ACCESS_TOKEN", ""));

        ObjectMapper objectMapper = new ObjectMapper();
        KiteAuthenticator authenticator = new KiteAuthenticator(config, objectMapper);
        InstrumentMasterService instrumentMaster = new InstrumentMasterService("data/instruments.db");
        instrumentMaster.initSchema();

        BrokerAdapterRegistry brokerRegistry = new BrokerAdapterRegistry(List.of());
        KiteOptionChainProvider provider = new KiteOptionChainProvider(
            brokerRegistry,
            instrumentMaster,
            config,
            authenticator,
            WebClient.builder(),
            objectMapper
        );

        System.out.println("=== 1. FETCHING LIVE NIFTY SPOT PRICE ===");
        Double spot = provider.getSpotPrice("NIFTY").block();
        System.out.printf("Live NIFTY Spot Price: ₹%.2f%n%n", spot != null ? spot : 0.0);

        System.out.println("=== 2. FETCHING UPCOMING NIFTY EXPIRIES ===");
        List<String> expiries = instrumentMaster.findUpcomingExpiries("NIFTY", "CE", 5).collectList().block();
        System.out.println("Available Expiries in DB: " + expiries);

        String targetExpiry = (expiries != null && !expiries.isEmpty()) ? expiries.get(0) : null;
        System.out.println("Target Expiry: " + targetExpiry);

        System.out.println("\n=== 3. FETCHING LIVE NIFTY OPTION CHAIN ===");
        OptionChain chain = provider.getOptionChain("NIFTY", targetExpiry).block();

        assertNotNull(chain, "OptionChain must not be null");
        System.out.println("OptionChain Underlying: " + chain.underlying());
        System.out.println("OptionChain Expiry: " + chain.expiry());
        System.out.println("Total Calls: " + chain.calls().size());
        System.out.println("Total Puts: " + chain.puts().size());

        if (spot != null && spot > 0 && !chain.calls().isEmpty()) {
            int atmStrike = (int) Math.round(spot / 50.0) * 50;
            System.out.printf("%n=== ATM (Strike %d) & SURROUNDING STRIKES ===%n", atmStrike);
            System.out.printf("%-10s | %-12s | %-8s || %-8s | %-12s%n",
                "CE Delta", "CE LTP (₹)", "Strike", "PE LTP (₹)", "PE Delta");
            System.out.println("------------------------------------------------------------------");

            List<Integer> strikes = chain.calls().keySet().stream().sorted().collect(Collectors.toList());
            for (int strike : strikes) {
                if (Math.abs(strike - atmStrike) <= 250) { // ATM +- 5 strikes
                    StrikeQuote ce = chain.getCall(strike);
                    StrikeQuote pe = chain.getPut(strike);
                    double ceLtp = ce != null && ce.ltp() != null ? ce.ltp().doubleValue() : 0.0;
                    double peLtp = pe != null && pe.ltp() != null ? pe.ltp().doubleValue() : 0.0;
                    double ceDelta = ce != null ? ce.delta() : 0.0;
                    double peDelta = pe != null ? pe.delta() : 0.0;

                    String marker = (strike == atmStrike) ? " <-- ATM" : "";
                    System.out.printf("%-10.2f | %-12.2f | %-8d || %-8.2f | %-12.2f%s%n",
                        ceDelta, ceLtp, strike, peLtp, peDelta, marker);
                }
            }
        }
    }
}
