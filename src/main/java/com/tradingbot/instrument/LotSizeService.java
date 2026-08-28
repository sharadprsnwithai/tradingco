package com.tradingbot.instrument;

import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches F&O lot sizes from Kite Connect instruments API.
 * Each stock has a unique lot size for F&O trading.
 */
@Service
public class LotSizeService {

    private static final Logger log = LoggerFactory.getLogger(LotSizeService.class);

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;

    // symbol → lot_size (e.g., "RELIANCE" → 250, "TCS" → 175)
    private final Map<String, Integer> lotSizeCache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public LotSizeService(KiteConfig config, KiteAuthenticator authenticator,
                          WebClient.Builder webClientBuilder) {
        this.config = config;
        this.authenticator = authenticator;
        String baseUrl = config != null && config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.kite.trade";
        this.webClient = webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("X-Kite-Version", "3")
            .build();
    }

    /**
     * Gets the lot size for a given stock symbol.
     * Returns default index lot size or 1 if not found.
     *
     * @param symbol the stock symbol (e.g., "RELIANCE", "TCS", "NIFTY")
     * @return lot size for F&O trading
     */
    public int getLotSize(String symbol) {
        if (!loaded) {
            loadLotSizes();
        }
        if (symbol == null || symbol.isBlank()) return 1;
        // Strip exchange prefix if present (e.g., "NSE:RELIANCE" → "RELIANCE", "NFO:NIFTY_50" → "NIFTY")
        String cleanSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
        cleanSymbol = cleanSymbol.toUpperCase().trim();

        int size = lotSizeCache.getOrDefault(cleanSymbol, 0);
        if (size > 0) return size;

        // Standard index lot size defaults for Indian derivatives
        if (cleanSymbol.startsWith("NIFTY")) return 65;
        if (cleanSymbol.startsWith("BANKNIFTY")) return 15;
        if (cleanSymbol.startsWith("FINNIFTY")) return 25;
        if (cleanSymbol.startsWith("MIDCPNIFTY")) return 50;
        if (cleanSymbol.startsWith("SENSEX")) return 10;
        if (cleanSymbol.startsWith("BANKEX")) return 15;

        return 1;
    }

    /**
     * Gets order quantity for specified number of lots.
     *
     * @param symbol the stock symbol
     * @param lots   number of lots (minimum 1)
     * @return lot_size × lots
     */
    public int getOrderQuantity(String symbol, int lots) {
        return getLotSize(symbol) * Math.max(1, lots);
    }

    /**
     * Gets order quantity for 2 lots (legacy default).
     *
     * @param symbol the stock symbol
     * @return 2 × lot_size
     */
    public int getOrderQuantity(String symbol) {
        return getLotSize(symbol) * 2;
    }

    /**
     * Loads all NFO instrument lot sizes from Kite API.
     * CSV format: instrument_token,exchange_token,tradingsymbol,name,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
     */
    public void loadLotSizes() {
        try {
            String response = webClient.get()
                .uri("/instruments/NFO")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> {
                    if (config.isEnabled() && authenticator.hasValidSession()) {
                        return authenticator.getAccessToken()
                            .flatMap(token -> webClient.get()
                                .uri("/instruments/NFO")
                                .header("Authorization", "token " + config.getApiKey() + ":" + token)
                                .retrieve()
                                .bodyToMono(String.class));
                    }
                    return reactor.core.publisher.Mono.empty();
                })
                .block();

            if (response == null || response.isEmpty()) {
                log.debug("Empty or unavailable response from Kite instruments API, using default lot sizes");
                loaded = true;
                return;
            }

            String[] lines = response.split("\n");
            int count = 0;

            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split(",");
                if (parts.length < 8) continue;

                String symbol = parts[2];    // tradingsymbol
                int lotSize = Integer.parseInt(parts[7].trim()); // lot_size
                String segment = parts.length > 9 ? parts[9] : "";

                // Only store NFO segment instruments (F&O)
                if (segment.contains("NFO") && lotSize > 0) {
                    // Store with base symbol (e.g., "RELIANCE26AUG1200CE" → extract "RELIANCE")
                    String baseSymbol = extractBaseSymbol(symbol);
                    if (baseSymbol != null) {
                        lotSizeCache.put(baseSymbol, lotSize);
                        count++;
                    }
                }
            }

            loaded = true;
            log.info("Loaded {} F&O lot sizes from Kite instruments", lotSizeCache.size());

        } catch (Exception e) {
            log.warn("Failed to load lot sizes from Kite: {}", e.getMessage());
        }
    }

    /**
     * Extracts base symbol from F&O trading symbol.
     * Examples: "RELIANCE26AUG1200CE" → "RELIANCE"
     *           "TCS26AUG2100PE" → "TCS"
     *           "NIFTY26AUG24500CE" → "NIFTY"
     */
    private String extractBaseSymbol(String tradingSymbol) {
        // Remove common suffixes: dates (26AUG), strikes (24500), option type (CE/PE)
        // Pattern: BASE + DATE + STRIKE + CE/PE
        // Date format: DDMMM (e.g., 26AUG)
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^([A-Z]+)\\d{2}[A-Z]{3}\\d+[CP]E?$")
            .matcher(tradingSymbol);

        if (m.matches()) {
            return m.group(1);
        }

        // Fallback: if no match, try stripping last 10 chars (approximate)
        if (tradingSymbol.length() > 10) {
            return tradingSymbol.substring(0, tradingSymbol.length() - 10);
        }

        return tradingSymbol;
    }

    /**
     * Forces a reload of lot sizes (e.g., at start of new trading day).
     */
    public void reload() {
        loaded = false;
        lotSizeCache.clear();
        loadLotSizes();
    }
}
