package com.tradingbot.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives the Top Gainers / Top Losers equity lists from Kite instead of scraping
 * the flaky NSE India website.
 *
 * Approach:
 *   1. Download Kite's instruments master (once, cached) and extract the unique
 *      equity underlyings that are part of the F&O segment (exclude indices).
 *   2. Batch-quote every underlying via Kite's /quote REST endpoint.
 *   3. Compute % change = (last_price - previous_close) / previous_close.
 *   4. Rank and return the top-N gainers / losers.
 *
 * On any Kite failure it transparently falls back to {@link NseIndiaClient} so the
 * LVR strategy selection never ends up empty due to a transient broker/network issue.
 */
@Service
@Primary
public class KiteGainersLosersProvider implements GainersLosersSource {

    private static final Logger log = LoggerFactory.getLogger(KiteGainersLosersProvider.class);

    private static final String KITE_REST = "https://api.kite.trade";
    private static final int TOP_N = 15;

    /** Index underlyings that appear in the NFO segment but are NOT equity stocks. */
    private static final Set<String> INDEX_NAMES = Set.of(
        "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50",
        "SENSEX", "BANKEX", "INDIAVIX"
    );

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final NseIndiaClient fallback;
    private final GainersLosersSource shoonyaBackup;

    private volatile List<String> foUniverse; // resolved "NSE:<symbol>" tokens

    private final InstrumentMasterService instrumentMaster;

    public KiteGainersLosersProvider(KiteConfig config,
                                     KiteAuthenticator authenticator,
                                     WebClient.Builder webClientBuilder,
                                     ObjectMapper objectMapper,
                                     NseIndiaClient fallback,
                                     ShoonyaGainersLosersProvider shoonyaBackup,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) InstrumentMasterService instrumentMaster) {
        this.config = config;
        this.authenticator = authenticator;
        this.webClient = webClientBuilder.baseUrl(KITE_REST)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.shoonyaBackup = shoonyaBackup;
        this.instrumentMaster = instrumentMaster;
    }

    @Override
    public Mono<List<NseGainerLoser>> fetchGainers() {
        return ranked(true)
            .flatMap(list -> !list.isEmpty() ? Mono.just(list) : shoonyaBackup.fetchGainers())
            .flatMap(list -> !list.isEmpty() ? Mono.just(list) : fallback.fetchGainers());
    }

    @Override
    public Mono<List<NseGainerLoser>> fetchLosers() {
        return ranked(false)
            .flatMap(list -> !list.isEmpty() ? Mono.just(list) : shoonyaBackup.fetchLosers())
            .flatMap(list -> !list.isEmpty() ? Mono.just(list) : fallback.fetchLosers());
    }

    private Mono<List<NseGainerLoser>> ranked(boolean gainers) {
        return getUniverse()
            .flatMap(this::quote)
            .map(list -> {
                if (list.isEmpty()) return list;
                List<NseGainerLoser> sorted = new ArrayList<>(list);
                if (gainers) {
                    sorted.sort(Comparator.comparingDouble(NseGainerLoser::pChange).reversed());
                } else {
                    sorted.sort(Comparator.comparingDouble(NseGainerLoser::pChange));
                }
                return sorted.subList(0, Math.min(TOP_N, sorted.size()));
            })
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] {} derive failed: {}", gainers ? "gainers" : "losers", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private Mono<List<String>> getUniverse() {
        if (foUniverse != null && !foUniverse.isEmpty()) return Mono.just(foUniverse);
        return webClient.get()
            .uri("/instruments")
            .header("X-Kite-Version", "3")
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseFoUniverse)
            .flatMap(u -> {
                if (!u.isEmpty()) return Mono.just(u);
                if (instrumentMaster != null) {
                    return instrumentMaster.getDistinctFoUnderlyingNames()
                        .map(names -> names.stream().map(n -> "NSE:" + n).toList());
                }
                return Mono.just(List.<String>of());
            })
            .doOnNext(u -> {
                if (!u.isEmpty()) {
                    foUniverse = u;
                    log.info("[KITE-SEL] Resolved F&O equity universe: {} symbols", u.size());
                }
            })
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] instruments download failed: {}", e.getMessage());
                if (instrumentMaster != null) {
                    return instrumentMaster.getDistinctFoUnderlyingNames()
                        .map(names -> names.stream().map(n -> "NSE:" + n).toList())
                        .doOnNext(u -> {
                            if (!u.isEmpty()) {
                                foUniverse = u;
                                log.info("[KITE-SEL] Fallback F&O equity universe from DB: {} symbols", u.size());
                            }
                        });
                }
                return Mono.just(List.of());
            });
    }

    private List<String> parseFoUniverse(String csv) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String[] lines = csv.split("\n", -1);
        if (lines.length < 2) return List.of();
        String[] header = lines[0].split(",", -1);
        int idxSegment = indexOf(header, "segment");
        int idxExchange = indexOf(header, "exchange");
        int idxType = indexOf(header, "instrument_type");
        int idxName = indexOf(header, "name");
        if (idxName < 0) return List.of();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",", -1);
            if (c.length <= idxName) continue;
            String segment = idxSegment >= 0 && c.length > idxSegment ? c[idxSegment].trim() : "";
            String exchange = idxExchange >= 0 && c.length > idxExchange ? c[idxExchange].trim() : "";
            String type = idxType >= 0 && c.length > idxType ? c[idxType].trim() : "";
            String name = c[idxName].trim().replace("\"", "");

            boolean isNfo = "NFO".equalsIgnoreCase(exchange) || segment.startsWith("NFO");
            if (!isNfo) continue;
            if (!type.isEmpty() && !Set.of("FUT", "CE", "PE").contains(type)) continue;
            if (name.isEmpty() || INDEX_NAMES.contains(name)) continue;
            names.add("NSE:" + name);
        }
        return new ArrayList<>(names);
    }

    private Mono<List<NseGainerLoser>> quote(List<String> symbols) {
        if (symbols.isEmpty()) return Mono.just(List.of());
        return authenticator.getAccessToken()
            .flatMap(token -> {
                List<List<String>> partitions = partition(symbols, 100);
                return Flux.fromIterable(partitions)
                    .flatMap(batch -> {
                        String query = batch.stream()
                            .map(s -> "i=" + URLEncoder.encode(s, StandardCharsets.UTF_8))
                            .collect(Collectors.joining("&"));
                        return webClient.get()
                            .uri("/quote?" + query)
                            .header("Authorization", "token " + config.getApiKey() + ":" + token)
                            .header("X-Kite-Version", "3")
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(this::parseQuotes)
                            .onErrorResume(e -> {
                                log.warn("[KITE-SEL] quote batch failed: {}", e.getMessage());
                                return Mono.just(List.of());
                            });
                    })
                    .flatMapIterable(list -> list)
                    .collectList();
            })
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] quote failed: {}", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private List<NseGainerLoser> parseQuotes(String body) {
        List<NseGainerLoser> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isObject()) return out;
            data.fields().forEachRemaining(e -> {
                JsonNode q = e.getValue();
                double last = q.path("last_price").asDouble();
                double prevClose = q.path("ohlc").path("close").asDouble();
                if (prevClose <= 0) return;
                double pct = (last - prevClose) / prevClose * 100.0;
                String key = e.getKey();
                String sym = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                out.add(new NseGainerLoser(sym, "EQ", last, last - prevClose, pct, 0, 0, 0, prevClose, 0L));
            });
        } catch (Exception ex) {
            log.warn("[KITE-SEL] quote parse error: {}", ex.getMessage());
        }
        return out;
    }

    private static int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].trim().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }
}
