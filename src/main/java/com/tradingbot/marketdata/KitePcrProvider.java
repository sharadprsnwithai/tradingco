package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches Put-Call Ratio (PCR) from Kite/Zerodha quotes API.
 * Uses the quotes endpoint which includes open interest (OI) for derivatives.
 *
 * PCR = Total Put OI / Total Call OI across ATM ± N strikes for nearest expiry.
 */
@Service
public class KitePcrProvider {

    private static final Logger log = LoggerFactory.getLogger(KitePcrProvider.class);

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final InstrumentMasterService instrumentMaster;

    private volatile String cachedNearestExpiry = null;
    private volatile long lastExpiryCacheTime = 0;

    public KitePcrProvider(
        KiteConfig config,
        KiteAuthenticator authenticator,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper,
        @Autowired(required = false) InstrumentMasterService instrumentMaster
    ) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.instrumentMaster = instrumentMaster;
        this.webClient = webClientBuilder
            .baseUrl(config.getBaseUrl())
            .defaultHeader("X-Kite-Version", "3")
            .build();
    }

    /**
     * Fetches PCR for NIFTY options using Kite quotes API with default ATM ± 4 strike range.
     *
     * @param spotPrice current NIFTY spot price to determine ATM strike
     * @return PCR value (Total Put OI / Total Call OI), or 0.0 if unavailable
     */
    public double fetchPcr(double spotPrice) {
        return fetchPcr(spotPrice, 4);
    }

    /**
     * Fetches PCR for NIFTY options using Kite quotes API for a custom ATM ± N strike range.
     *
     * @param spotPrice   current NIFTY spot price to determine ATM strike
     * @param strikeRange strike offset range around ATM (e.g., 3 for ATM ± 3 strikes)
     * @return PCR value (Total Put OI / Total Call OI), or 0.0 if unavailable
     */
    public double fetchPcr(double spotPrice, int strikeRange) {
        if (!config.isEnabled() || !authenticator.hasValidSession()) {
            log.debug("Kite not enabled or not authenticated, returning default PCR 1.0");
            return 1.0;
        }

        int range = strikeRange > 0 ? strikeRange : 4;

        try {
            return authenticator.getAccessToken()
                .flatMap(token -> {
                    // Get nearest expiry date
                    String nearestExpiry = getNearestExpiry(token);
                    if (nearestExpiry == null) {
                        return Mono.just(1.0);
                    }

                    // Calculate ATM strike (round to nearest 50)
                    int atmStrike = (int) Math.round(spotPrice / 50.0) * 50;

                    if (instrumentMaster != null) {
                        // Resolve the exact option instruments from the instrument master (which already
                        // carries the correct Kite trading symbols). This avoids (a) the ~20MB
                        // /instruments/NFO CSV download and (b) hand-built weekly/monthly expiry symbol
                        // strings that produced 404s and a fallback PCR of 1.0 (AUD-04).
                        List<String> instruments = instrumentMaster
                            .findOptionContracts("NIFTY", nearestExpiry, null, null)
                            .filter(inst -> {
                                if (inst.strike() == null) return false;
                                int strike = (int) Math.round(inst.strike().doubleValue());
                                return Math.abs(strike - atmStrike) <= range * 50;
                            })
                            .map(Instrument::canonicalSymbol)
                            .filter(s -> s != null && (s.endsWith("CE") || s.endsWith("PE")))
                            .collectList()
                            .block();

                        if (instruments == null || instruments.isEmpty()) {
                            log.warn("[PCR] No NIFTY option instruments in master for expiry={}, atm={} — returning PCR 1.0",
                                nearestExpiry, atmStrike);
                            return Mono.just(1.0);
                        }

                        String queryParams = String.join("&", instruments.stream()
                            .map(i -> "i=" + i)
                            .toArray(String[]::new));

                        return webClient.get()
                            .uri("/quote?" + queryParams)
                            .header("Authorization", "token " + config.getApiKey() + ":" + token)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(this::parsePcr)
                            .onErrorResume(ex -> {
                                log.warn("Failed to fetch Kite quotes for PCR: {}", ex.getMessage());
                                return Mono.just(1.0);
                            });
                    }

                    // Fallback (no instrument master): build symbols manually using the expiry date part.
                    List<String> instruments = new ArrayList<>();
                    for (int offset = -range; offset <= range; offset++) {
                        int strike = atmStrike + offset * 50;
                        String datePart = formatExpiryForKite(nearestExpiry);
                        instruments.add("NFO:NIFTY" + datePart + strike + "CE");
                        instruments.add("NFO:NIFTY" + datePart + strike + "PE");
                    }
                    String queryParams = String.join("&", instruments.stream()
                        .map(i -> "i=" + i)
                        .toArray(String[]::new));
                    return webClient.get()
                        .uri("/quote?" + queryParams)
                        .header("Authorization", "token " + config.getApiKey() + ":" + token)
                        .retrieve()
                        .bodyToMono(String.class)
                        .map(this::parsePcr)
                        .onErrorResume(ex -> {
                            log.warn("Failed to fetch Kite quotes for PCR: {}", ex.getMessage());
                            return Mono.just(1.0);
                        });
                })
                .block();
        } catch (Exception e) {
            log.warn("Error fetching PCR from Kite: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * Fetches the last traded price for a single instrument via Kite's /quote/ltp endpoint.
     * Used by strategies to get the option premium at entry time (before the first
     * WebSocket tick arrives for a freshly subscribed contract).
     *
     * @param canonicalSymbol canonical instrument key, e.g. "NFO:NIFTY26AUG24500CE"
     * @return the last traded price, or 0.0 if unavailable
     */
    public double fetchLtp(String canonicalSymbol) {
        if (!config.isEnabled() || !authenticator.hasValidSession()) {
            log.debug("Kite not enabled/authenticated - LTP unavailable for {}", canonicalSymbol);
            return 0.0;
        }
        try {
            Double ltp = authenticator.getAccessToken()
                .flatMap(token -> webClient.get()
                    .uri("/quote/ltp?i=" + canonicalSymbol)
                    .header("Authorization", "token " + config.getApiKey() + ":" + token)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            JsonNode root = objectMapper.readTree(json);
                            return root.path("data").path(canonicalSymbol).path("last_price").asDouble(0.0);
                        } catch (Exception e) {
                            log.warn("Failed to parse LTP response for {}: {}", canonicalSymbol, e.getMessage());
                            return 0.0;
                        }
                    }))
                .block();
            return ltp != null ? ltp : 0.0;
        } catch (Exception e) {
            log.warn("Failed to fetch LTP for {}: {}", canonicalSymbol, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets the nearest expiry date from Kite instruments endpoint (today or future only).
     */
    private String getNearestExpiry(String token) {
        long now = System.currentTimeMillis();
        if (cachedNearestExpiry != null && (now - lastExpiryCacheTime) < 3_600_000) {
            return cachedNearestExpiry;
        }

        if (instrumentMaster != null) {
            try {
                String exp = instrumentMaster.findUpcomingExpiries("NIFTY", "CE", 1).blockFirst();
                if (exp != null && !exp.isBlank()) {
                    this.cachedNearestExpiry = exp;
                    this.lastExpiryCacheTime = now;
                    return exp;
                }
            } catch (Exception ex) {
                log.debug("Failed to query upcoming expiry from InstrumentMasterService: {}", ex.getMessage());
            }
        }

        try {
            String response = webClient.get()
                .uri("/instruments/NFO")
                .header("Authorization", "token " + config.getApiKey() + ":" + token)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            if (response == null || response.isEmpty()) return null;

            // Parse CSV: instrument_token,exchange_token,tradingsymbol,name,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
            String[] lines = response.split("\n");
            String nearestExpiry = null;
            String todayStr = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();

            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split(",");
                if (parts.length < 6) continue;

                String symbol = parts[2].replace("\"", "").trim();
                String expiry = parts[4].replace("\"", "").trim();

                if (symbol.startsWith("NIFTY") && !symbol.contains("NIFTY50")
                    && parts[9].contains("NFO") && parts[10].contains("NFO")) {
                    if (expiry.compareTo(todayStr) >= 0) {
                        if (nearestExpiry == null || expiry.compareTo(nearestExpiry) < 0) {
                            nearestExpiry = expiry;
                        }
                    }
                }
            }

            if (nearestExpiry != null) {
                this.cachedNearestExpiry = nearestExpiry;
                this.lastExpiryCacheTime = now;
            }
            return nearestExpiry;
        } catch (Exception e) {
            log.warn("Failed to fetch Kite instruments: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Formats expiry date from "2026-08-27" to "26AUG" (monthly) or "26820" (weekly) for Kite trading symbol.
     */
    private String formatExpiryForKite(String expiryDate) {
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(expiryDate.trim());
            // Monthly expiry is the last expiry in the calendar month
            boolean isMonthly = date.plusDays(7).getMonthValue() != date.getMonthValue();
            if (isMonthly) {
                return date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMM", java.util.Locale.ENGLISH)).toUpperCase();
            } else {
                int yy = date.getYear() % 100;
                int m = date.getMonthValue();
                String mStr = (m == 10) ? "O" : (m == 11) ? "N" : (m == 12) ? "D" : String.valueOf(m);
                int dd = date.getDayOfMonth();
                return String.format("%02d%s%02d", yy, mStr, dd);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parses Kite quotes response and calculates PCR.
     * Response format: {"data": {"NFO:NIFTY26AUG24500CE": {"oi": 12345, ...}, ...}}
     */
    private double parsePcr(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");

            double totalPutOi = 0;
            double totalCallOi = 0;

            var fields = data.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String instrument = entry.getKey();
                JsonNode quote = entry.getValue();

                double oi = quote.path("oi").asDouble(0);

                if (instrument.endsWith("CE")) {
                    totalCallOi += oi;
                } else if (instrument.endsWith("PE")) {
                    totalPutOi += oi;
                }
            }

            if (totalCallOi == 0) return 1.0;

            double pcr = totalPutOi / totalCallOi;
            log.debug("Kite PCR calculated: Put OI={}, Call OI={}, PCR={:.4f}", totalPutOi, totalCallOi, pcr);
            return pcr;

        } catch (Exception e) {
            log.warn("Failed to parse Kite quotes for PCR: {}", e.getMessage());
            return 1.0;
        }
    }
}
