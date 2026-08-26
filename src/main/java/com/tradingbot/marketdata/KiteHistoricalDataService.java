package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-performance, reactive Historical Candle Data Service for Zerodha Kite Connect.
 * Fetches authentic multi-timeframe historical candles (5m, 15m, 60m) to warm up
 * indicators (SuperTrend, RSI, VWAP) before market open.
 */
@Service
public class KiteHistoricalDataService {

    private static final Logger log = LoggerFactory.getLogger(KiteHistoricalDataService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final InstrumentMasterService instrumentMaster;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public record KiteWarmupRequest(
        String symbol,
        String timeframe,
        int numCandles
    ) {}

    public record KiteWarmupResult(
        String symbol,
        String timeframe,
        List<Candle> candles,
        boolean success
    ) {}

    public KiteHistoricalDataService(
        KiteConfig config,
        KiteAuthenticator authenticator,
        InstrumentMasterService instrumentMaster,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper
    ) {
        this.config = config;
        this.authenticator = authenticator;
        this.instrumentMaster = instrumentMaster;
        this.objectMapper = objectMapper;
        String baseUrl = config != null && config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.kite.trade";
        this.webClient = webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("X-Kite-Version", "3")
            .build();
    }

    /**
     * Fetches historical candles from Kite for the given symbol and timeframe.
     */
    public Mono<List<Candle>> fetchHistoricalCandles(String symbol, String timeframe, int numCandles) {
        if (!config.isEnabled() || !authenticator.hasValidSession()) {
            log.debug("Kite disabled or not authenticated — skipping historical fetch for {}", symbol);
            return Mono.just(Collections.emptyList());
        }

        return instrumentMaster.resolveForMarketData(symbol)
            .flatMap(instrument -> {
                long token = 0;
                try {
                    token = Long.parseLong(instrument.kiteToken());
                } catch (Exception ignored) {}
                if (token <= 0) {
                    return Mono.just(Collections.<Candle>emptyList());
                }
                return fetchCandlesForToken(symbol, token, timeframe, numCandles);
            })
            .defaultIfEmpty(Collections.<Candle>emptyList());
    }

    /**
     * Fetches candles directly using an instrument token.
     */
    public Mono<List<Candle>> fetchCandlesForToken(String symbol, long token, String timeframe, int numCandles) {
        String kiteInterval = toKiteInterval(timeframe);
        int daysBack = calculateDaysBack(timeframe, numCandles);

        LocalDate toDate = LocalDate.now(IST_ZONE);
        LocalDate fromDate = toDate.minusDays(daysBack);

        String fromStr = fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String toStr = toDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        String uri = String.format("/instruments/historical/%d/%s?from=%s+09:15:00&to=%s+15:30:00",
            token, kiteInterval, fromStr, toStr);

        return authenticator.getAccessToken()
            .flatMap(accessToken -> webClient.get()
                .uri(uri)
                .header("Authorization", "token " + config.getApiKey() + ":" + accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> parseKiteCandles(body, symbol, timeframe))
                .onErrorResume(e -> {
                    log.warn("Failed to fetch Kite historical candles for {} (token={}): {}", symbol, token, e.getMessage());
                    return Mono.just(Collections.emptyList());
                }))
            .defaultIfEmpty(Collections.emptyList());
    }

    /**
     * Executes sequential warmup for a list of requests with rate-limiting throttle (350ms).
     */
    public Flux<KiteWarmupResult> warmupSequentially(List<KiteWarmupRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(requests)
            .concatMap(req -> fetchHistoricalCandles(req.symbol(), req.timeframe(), req.numCandles())
                .map(candles -> new KiteWarmupResult(req.symbol(), req.timeframe(), candles, !candles.isEmpty()))
                .delayElement(Duration.ofMillis(350)))
            .doOnNext(res -> {
                if (res.success()) {
                    log.info("Kite Warmup SUCCESS: {} [{}m] -> {} candles", res.symbol(), res.timeframe(), res.candles().size());
                } else {
                    log.warn("Kite Warmup FAILED: {} [{}m] -> 0 candles", res.symbol(), res.timeframe());
                }
            });
    }

    private String toKiteInterval(String timeframe) {
        return switch (timeframe) {
            case "1" -> "minute";
            case "3" -> "3minute";
            case "5" -> "5minute";
            case "15" -> "15minute";
            case "30" -> "30minute";
            case "60" -> "60minute";
            case "D", "day" -> "day";
            default -> timeframe.contains("minute") ? timeframe : timeframe + "minute";
        };
    }

    private int calculateDaysBack(String timeframe, int numCandles) {
        int minutesPerCandle = switch (timeframe) {
            case "1" -> 1;
            case "3" -> 3;
            case "5" -> 5;
            case "15" -> 15;
            case "30" -> 30;
            case "60" -> 60;
            default -> 15;
        };
        // 375 minutes per trading day. Account for weekends and market holidays (~2.5x multiplier)
        int totalMinutes = numCandles * minutesPerCandle;
        int tradingDays = (int) Math.ceil((double) totalMinutes / 375.0);
        return Math.max(3, (int) Math.ceil(tradingDays * 2.5) + 2);
    }

    private List<Candle> parseKiteCandles(String json, String symbol, String timeframe) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode candlesArr = root.path("data").path("candles");
            if (candlesArr.isArray()) {
                for (JsonNode c : candlesArr) {
                    if (c.isArray() && c.size() >= 6) {
                        String tsStr = c.get(0).asText();
                        if (tsStr.endsWith("+0530")) {
                            tsStr = tsStr.substring(0, tsStr.length() - 5) + "+05:30";
                        }
                        Instant ts = ZonedDateTime.parse(tsStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                        BigDecimal open = BigDecimal.valueOf(c.get(1).asDouble());
                        BigDecimal high = BigDecimal.valueOf(c.get(2).asDouble());
                        BigDecimal low = BigDecimal.valueOf(c.get(3).asDouble());
                        BigDecimal close = BigDecimal.valueOf(c.get(4).asDouble());
                        long volume = c.get(5).asLong();

                        candles.add(new Candle(symbol, timeframe, ts, open, high, low, close, volume));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing Kite candle response for {}: {}", symbol, e.getMessage());
        }
        return candles;
    }
}
