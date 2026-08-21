package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.shoonya.ShoonyaAuthenticator;
import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import com.tradingbot.model.Candle;
import com.tradingbot.resilience.BrokerBulkheadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for fetching historical candle data from Shoonya (Finvasia NorenAPI) TPSeries.
 * Enforces rate limiting (1 req/sec) and sequential backfill with 350ms throttle via concatMap.
 */
@Service
public class ShoonyaHistoricalDataService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaHistoricalDataService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SHOONYA_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final BrokerBulkheadManager bulkheadManager;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the historical data service with Shoonya API dependencies.
     *
     * @param config Shoonya API configuration (user ID, enabled flag, etc.)
     * @param authenticator handles access token and session management
     * @param bulkheadManager provides bulkhead isolation for API calls
     * @param webClientBuilder Spring WebClient builder pre-configured with base URL
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     */
    public ShoonyaHistoricalDataService(
        ShoonyaConfig config,
        ShoonyaAuthenticator authenticator,
        BrokerBulkheadManager bulkheadManager,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper
    ) {
        this.config = config;
        this.authenticator = authenticator;
        this.bulkheadManager = bulkheadManager;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl("https://api.shoonya.com").build();
    }

    /**
     * Fetch historical candles for a single instrument with rate-limiting and bulkhead protection.
     *
     * @param symbol Canonical symbol (e.g. "NSE:RELIANCE")
     * @param exchange Exchange (e.g. "NSE", "NFO")
     * @param token Shoonya token (e.g. "2885")
     * @param timeframe Timeframe in minutes (e.g. "1", "3", "5", "15")
     * @param numCandles Number of recent candles to fetch (e.g. 200)
     * @return Mono of chronological List of Candle
     */
    public Mono<List<Candle>> fetchHistoricalCandles(String symbol, String exchange, String token, String timeframe, int numCandles) {
        if (!config.isEnabled()) {
            log.info("Shoonya is disabled. Generating mock historical candles for {}", symbol);
            return Mono.just(generateMockCandles(symbol, timeframe, numCandles));
        }

        return authenticator.getAccessToken()
            .flatMap(accessToken -> {
                long nowEpoch = Instant.now().getEpochSecond();
                int minutesPerCandle = parseTimeframeMinutes(timeframe);
                // Estimate start time window (with safety multiplier for market hours / weekends)
                long startEpoch = nowEpoch - ((long) numCandles * minutesPerCandle * 60L * 3L);

                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("exch", exchange != null ? exchange : "NSE");
                payload.put("token", token);
                payload.put("st", String.valueOf(startEpoch));
                payload.put("et", String.valueOf(nowEpoch));
                payload.put("intrv", timeframe);

                String jDataStr;
                try {
                    jDataStr = objectMapper.writeValueAsString(payload);
                } catch (Exception e) {
                    return Mono.error(new RuntimeException("Failed to serialize Shoonya TPSeries request", e));
                }

                Mono<List<Candle>> rawRequest = webClient.post()
                    .uri("/NorenWClientAPI/TPSeries")
                    .header("Authorization", accessToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("jData", jDataStr)
                        .with("jKey", authenticator.getSUserToken() != null ? authenticator.getSUserToken() : ""))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(responseBody -> parseCandleResponse(responseBody, symbol, timeframe, numCandles))
                    .onErrorResume(ex -> {
                        log.warn("Shoonya TPSeries fetch failed for {} ({}|{}): {}. Falling back to empty.", symbol, exchange, token, ex.getMessage());
                        return Mono.just(Collections.emptyList());
                    });

                return bulkheadManager.executeShoonya(rawRequest);
            });
    }

    /**
     * Batch warm-up historical candles sequentially with 350ms delay between requests.
     *
     * @param requests list of warmup requests specifying symbol, exchange, token, timeframe, and candle count
     * @return Flux emitting HistoricalWarmupResult for each completed request
     */
    public Flux<HistoricalWarmupResult> warmupSequentially(List<HistoricalWarmupRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(requests)
            .concatMap(req -> fetchHistoricalCandles(req.symbol(), req.exchange(), req.token(), req.timeframe(), req.numCandles())
                .map(candles -> new HistoricalWarmupResult(req.symbol(), req.timeframe(), candles, true, null))
                .onErrorResume(e -> Mono.just(new HistoricalWarmupResult(req.symbol(), req.timeframe(), Collections.emptyList(), false, e.getMessage())))
                .delayElement(Duration.ofMillis(350))
            );
    }

    /**
     * Parses the Shoonya TPSeries JSON response into a sorted, bounded list of Candle objects.
     *
     * @param responseBody raw JSON response string from Shoonya API
     * @param symbol canonical symbol to assign to each candle
     * @param timeframe timeframe string to assign to each candle
     * @param maxCandles maximum number of candles to return
     * @return list of Candle objects sorted chronologically ascending
     */
    private List<Candle> parseCandleResponse(String responseBody, String symbol, String timeframe, int maxCandles) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if ("Ok".equalsIgnoreCase(node.path("stat").asText("Ok")) || node.has("into")) {
                        BigDecimal open = new BigDecimal(node.path("into").asText("0"));
                        BigDecimal high = new BigDecimal(node.path("inth").asText("0"));
                        BigDecimal low = new BigDecimal(node.path("intl").asText("0"));
                        BigDecimal close = new BigDecimal(node.path("intc").asText("0"));
                        long volume = node.path("v").asLong(0);

                        Instant timestamp = parseTimestamp(node);
                        candles.add(new Candle(symbol, timeframe, timestamp, open, high, low, close, volume));
                    }
                }
            } else if (root.isObject() && "Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
                log.warn("Shoonya TPSeries returned error for {}: {}", symbol, root.path("emsg").asText("Unknown"));
            }
        } catch (Exception e) {
            log.error("Failed to parse Shoonya TPSeries response: {}", responseBody, e);
        }

        // Sort chronologically ascending
        candles.sort(Comparator.comparing(Candle::timestamp));

        // Limit to max requested candles
        if (candles.size() > maxCandles) {
            return candles.subList(candles.size() - maxCandles, candles.size());
        }
        return candles;
    }

    /**
     * Extracts an Instant timestamp from a Shoonya candle JSON node.
     * Tries epoch seconds ("ssboe") first, then falls back to formatted time string.
     *
     * @param node JSON node representing a single candle from Shoonya API
     * @return parsed Instant, or current time if parsing fails
     */
    private Instant parseTimestamp(JsonNode node) {
        if (node.has("ssboe")) {
            long epochSeconds = node.path("ssboe").asLong();
            if (epochSeconds > 0) {
                return Instant.ofEpochSecond(epochSeconds);
            }
        }
        String timeStr = node.path("time").asText("");
        if (!timeStr.isEmpty()) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(timeStr, SHOONYA_TIME_FORMATTER);
                return ldt.atZone(IST_ZONE).toInstant();
            } catch (Exception e) {
                log.debug("Could not parse Shoonya timestamp '{}': {}", timeStr, e.getMessage());
            }
        }
        return Instant.now();
    }

    /**
     * Parses a timeframe string into minutes, defaulting to 1 on parse failure.
     *
     * @param tf timeframe string (e.g. "1", "5", "15")
     * @return number of minutes, or 1 if unparseable
     */
    private int parseTimeframeMinutes(String tf) {
        try {
            return Integer.parseInt(tf);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Generates mock candle data for testing or when Shoonya is disabled.
     * Creates synthetic OHLCV candles with a simple price walk.
     *
     * @param symbol canonical symbol for the generated candles
     * @param timeframe timeframe string for the generated candles
     * @param count number of candles to generate
     * @return list of synthetic Candle objects
     */
    private List<Candle> generateMockCandles(String symbol, String timeframe, int count) {
        List<Candle> list = new ArrayList<>();
        Instant start = Instant.now().minus(count * 60L, ChronoUnit.SECONDS);
        BigDecimal price = new BigDecimal("2500.00");
        for (int i = 0; i < count; i++) {
            Instant t = start.plus(i * 60L, ChronoUnit.SECONDS);
            BigDecimal open = price;
            BigDecimal high = price.add(new BigDecimal("2.50"));
            BigDecimal low = price.subtract(new BigDecimal("1.50"));
            BigDecimal close = price.add(new BigDecimal("1.00"));
            list.add(new Candle(symbol, timeframe, t, open, high, low, close, 1000 + i * 10L));
            price = close;
        }
        return list;
    }

    /**
     * Request parameters for a sequential historical candle warmup fetch.
     *
     * @param symbol canonical symbol (e.g. "NSE:RELIANCE")
     * @param exchange exchange code (e.g. "NSE", "NFO")
     * @param token Shoonya instrument token
     * @param timeframe candle interval in minutes (e.g. "1", "5")
     * @param numCandles number of historical candles to fetch
     */
    public record HistoricalWarmupRequest(String symbol, String exchange, String token, String timeframe, int numCandles) {}

    /**
     * Result of a single historical candle warmup fetch.
     *
     * @param symbol canonical symbol that was fetched
     * @param timeframe timeframe of the fetched candles
     * @param candles list of fetched Candle objects (empty on failure)
     * @param success whether the fetch completed successfully
     * @param errorMessage error details if the fetch failed, or null on success
     */
    public record HistoricalWarmupResult(String symbol, String timeframe, List<Candle> candles, boolean success, String errorMessage) {}
}
