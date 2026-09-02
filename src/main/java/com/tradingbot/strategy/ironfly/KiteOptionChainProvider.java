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
        return fetchChainFromBroker(underlying, expiry)
            .switchIfEmpty(Mono.just(OptionChain.empty(normalizeUnderlying(underlying), expiry != null ? expiry : "")))
            .doOnError(e -> log.warn("Failed to fetch option chain from Kite for {}: {}", underlying, e.getMessage()));
    }

    private Mono<OptionChain> fetchChainFromBroker(String underlying, String expiry) {
        String normUnderlying = normalizeUnderlying(underlying);
        Mono<String> expiryMono = (expiry != null && !expiry.isBlank())
            ? Mono.just(expiry)
            : instrumentMaster.findUpcomingExpiries(normUnderlying, "CE", 1).next();

        return expiryMono.flatMap(targetExpiry ->
            instrumentMaster.findOptionContracts(normUnderlying, targetExpiry, null, "CE")
                .concatWith(instrumentMaster.findOptionContracts(normUnderlying, targetExpiry, null, "PE"))
                .collectList()
                .flatMap(instruments -> {
                    if (instruments.isEmpty()) return Mono.just(OptionChain.empty(normUnderlying, targetExpiry));

                    List<String> canonicalSymbols = instruments.stream()
                        .map(Instrument::canonicalSymbol)
                        .filter(s -> s != null && !s.isBlank())
                        .toList();

                    if (canonicalSymbols.isEmpty()) return Mono.just(OptionChain.empty(normUnderlying, targetExpiry));

                    return fetchBatchLtp(canonicalSymbols)
                        .zipWith(getSpotPrice(normUnderlying))
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
                            return new OptionChain(normUnderlying, targetExpiry, calls, puts);
                        });
                })
        ).defaultIfEmpty(OptionChain.empty(normUnderlying, expiry != null ? expiry : ""));
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
                .flatMap(batch -> webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/quote/ltp")
                        .queryParam("i", (Object[]) batch.toArray(String[]::new))
                        .build())
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
                    })
                )
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
                .uri(uriBuilder -> uriBuilder.path("/quote/ltp").queryParam("i", sym).build())
                .header("Authorization", "token " + kiteConfig.getApiKey() + ":" + token)
                .header("X-Kite-Version", "3")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> root.path("data").path(sym).path("last_price").asDouble(0.0))
                .onErrorResume(e -> Mono.just(0.0))
            )
            .onErrorResume(e -> Mono.just(0.0));
    }

    private String normalizeUnderlying(String underlying) {
        if (underlying == null) return "NIFTY";
        String s = underlying.toUpperCase().trim();
        if (s.startsWith("NSE:") || s.startsWith("NFO:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        }
        return switch (s) {
            case "NIFTY 50", "NIFTY50", "NIFTY_50" -> "NIFTY";
            case "NIFTY BANK", "BANKNIFTY_50", "BANK NIFTY" -> "BANKNIFTY";
            case "NIFTY FIN SERVICE", "FINNIFTY_50", "FIN NIFTY" -> "FINNIFTY";
            case "NIFTY MID SELECT", "MIDCPNIFTY_50" -> "MIDCPNIFTY";
            case "NIFTY NEXT 50", "NIFTYNXT50" -> "NIFTYNXT50";
            default -> s;
        };
    }

    private String mapToKiteCanonicalIndex(String underlying) {
        if (underlying == null) return "NSE:NIFTY 50";
        String s = underlying.toUpperCase().trim();
        if (s.startsWith("NSE:") || s.startsWith("NFO:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        }
        return switch (s) {
            case "NIFTY", "NIFTY 50", "NIFTY50", "NIFTY_50" -> "NSE:NIFTY 50";
            case "BANKNIFTY", "NIFTY BANK", "BANKNIFTY_50", "BANK NIFTY" -> "NSE:NIFTY BANK";
            case "FINNIFTY", "NIFTY FIN SERVICE", "FINNIFTY_50", "FIN NIFTY" -> "NSE:NIFTY FIN SERVICE";
            case "MIDCPNIFTY", "NIFTY MID SELECT", "MIDCPNIFTY_50" -> "NSE:NIFTY MID SELECT";
            case "NIFTYNXT50", "NIFTY NEXT 50" -> "NSE:NIFTY NEXT 50";
            default -> "NSE:" + s;
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
