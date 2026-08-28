package com.tradingbot.strategy.ironfly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kite (Zerodha) implementation of OptionChainProvider.
 * Uses the instrument master to find option contracts and non-blocking batch REST quotes for pricing.
 */
@Primary
@Component
public class KiteOptionChainProvider implements OptionChainProvider {

    private static final Logger log = LoggerFactory.getLogger(KiteOptionChainProvider.class);
    private static final String KITE_BASE_URL = "https://api.kite.trade";

    private final BrokerAdapterRegistry brokerRegistry;
    private final InstrumentMasterService instrumentMaster;
    private final KiteConfig kiteConfig;
    private final KiteAuthenticator kiteAuthenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public KiteOptionChainProvider(
        BrokerAdapterRegistry brokerRegistry,
        InstrumentMasterService instrumentMaster,
        @Autowired(required = false) KiteConfig kiteConfig,
        @Autowired(required = false) KiteAuthenticator kiteAuthenticator,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper
    ) {
        this.brokerRegistry = brokerRegistry;
        this.instrumentMaster = instrumentMaster;
        this.kiteConfig = kiteConfig;
        this.kiteAuthenticator = kiteAuthenticator;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl(KITE_BASE_URL)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
    }

    @Override
    public Mono<OptionChain> getOptionChain(String underlying, String expiry) {
        return brokerRegistry.getAll()
            .filter(BrokerAdapter::isEnabled)
            .take(1)
            .singleOrEmpty()
            .flatMap(adapter -> fetchChainFromBroker(underlying, expiry))
            .switchIfEmpty(Mono.just(OptionChain.empty(underlying, expiry)))
            .doOnError(e -> log.warn("Failed to fetch option chain from Kite for {}: {}", underlying, e.getMessage()));
    }

    private Mono<OptionChain> fetchChainFromBroker(String underlying, String expiry) {
        return instrumentMaster.findOptionContracts(underlying, expiry, null, "CE")
            .concatWith(instrumentMaster.findOptionContracts(underlying, expiry, null, "PE"))
            .collectList()
            .flatMap(instruments -> {
                if (instruments.isEmpty()) return Mono.just(OptionChain.empty(underlying, expiry));

                List<String> canonicalSymbols = instruments.stream()
                    .map(Instrument::canonicalSymbol)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

                if (canonicalSymbols.isEmpty()) return Mono.just(OptionChain.empty(underlying, expiry));

                return fetchBatchLtp(canonicalSymbols)
                    .zipWith(getSpotPrice(underlying))
                    .map(tuple -> {
                        Map<String, Double> prices = tuple.getT1();
                        double spot = tuple.getT2();
                        Map<Integer, StrikeQuote> calls = new HashMap<>();
                        Map<Integer, StrikeQuote> puts = new HashMap<>();
                        for (Instrument inst : instruments) {
                            if (inst.strike() == null) continue;
                            OptionType type = "CE".equalsIgnoreCase(inst.instrumentType()) ? OptionType.CE : OptionType.PE;
                            int strike = inst.strike().intValue();

                            double ltp = prices.getOrDefault(inst.canonicalSymbol(), 0.0);
                            double delta = approximateDelta(strike, spot, type);
                            StrikeQuote quote = new StrikeQuote(
                                strike, type,
                                BigDecimal.valueOf(ltp),
                                BigDecimal.valueOf(ltp),
                                BigDecimal.valueOf(ltp),
                                10000, 500,
                                delta, 0.0, 0.0, 0.0
                            );

                            if (type == OptionType.CE) {
                                calls.put(strike, quote);
                            } else {
                                puts.put(strike, quote);
                            }
                        }
                        return new OptionChain(underlying, expiry, calls, puts);
                    });
            });
    }

    private double approximateDelta(int strike, double spot, OptionType type) {
        if (spot <= 0) return type == OptionType.CE ? 0.5 : -0.5;
        double moneyness = (strike - spot) / spot;
        if (type == OptionType.CE) {
            return Math.max(0.05, Math.min(0.95, 0.5 - moneyness * 8.0));
        } else {
            return -Math.max(0.05, Math.min(0.95, 0.5 + moneyness * 8.0));
        }
    }

    public Mono<Map<String, Double>> fetchBatchLtp(List<String> canonicalSymbols) {
        if (canonicalSymbols.isEmpty() || kiteAuthenticator == null || kiteConfig == null || !kiteConfig.isEnabled()) {
            return Mono.just(Collections.emptyMap());
        }

        List<List<String>> partitions = partition(canonicalSymbols, 100);
        return kiteAuthenticator.getAccessToken()
            .flatMap(token -> Flux.fromIterable(partitions)
                .flatMap(batch -> {
                    String query = batch.stream()
                        .map(s -> "i=" + URLEncoder.encode(s, StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
                    return webClient.get()
                        .uri("/quote/ltp?" + query)
                        .header("Authorization", "token " + kiteConfig.getApiKey() + ":" + token)
                        .header("X-Kite-Version", "3")
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(root -> {
                            Map<String, Double> prices = new HashMap<>();
                            JsonNode data = root.path("data");
                            if (data.isObject()) {
                                data.fields().forEachRemaining(e -> {
                                    prices.put(e.getKey(), e.getValue().path("last_price").asDouble(0.0));
                                });
                            }
                            return prices;
                        })
                        .onErrorResume(e -> {
                            log.warn("[KiteOptionChain] Batch quote error: {}", e.getMessage());
                            return Mono.just(Collections.emptyMap());
                        });
                })
                .reduce((Map<String, Double>) new HashMap<String, Double>(), (acc, map) -> {
                    acc.putAll(map);
                    return acc;
                })
            )
            .onErrorResume(e -> Mono.just(Map.of()));
    }

    @Override
    public Mono<Double> getSpotPrice(String underlying) {
        String sym = mapToKiteCanonicalIndex(underlying);
        if (kiteAuthenticator == null || kiteConfig == null || !kiteConfig.isEnabled()) {
            return Mono.just(0.0);
        }
        return kiteAuthenticator.getAccessToken()
            .flatMap(token -> webClient.get()
                .uri("/quote/ltp?i=" + URLEncoder.encode(sym, StandardCharsets.UTF_8))
                .header("Authorization", "token " + kiteConfig.getApiKey() + ":" + token)
                .header("X-Kite-Version", "3")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> root.path("data").path(sym).path("last_price").asDouble(0.0))
                .onErrorResume(e -> Mono.just(0.0))
            )
            .onErrorResume(e -> Mono.just(0.0));
    }

    private String mapToKiteCanonicalIndex(String underlying) {
        if (underlying == null) return "NSE:NIFTY 50";
        return switch (underlying.toUpperCase().trim()) {
            case "NIFTY", "NIFTY 50", "NIFTY50" -> "NSE:NIFTY 50";
            case "BANKNIFTY", "NIFTY BANK" -> "NSE:NIFTY BANK";
            case "FINNIFTY", "NIFTY FIN SERVICE" -> "NSE:NIFTY FIN SERVICE";
            default -> underlying.startsWith("NSE:") ? underlying : "NSE:" + underlying;
        };
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
