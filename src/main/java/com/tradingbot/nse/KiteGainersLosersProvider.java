package com.tradingbot.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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

    public KiteGainersLosersProvider(KiteConfig config,
                                     KiteAuthenticator authenticator,
                                     WebClient.Builder webClientBuilder,
                                     ObjectMapper objectMapper,
                                     NseIndiaClient fallback,
                                     ShoonyaGainersLosersProvider shoonyaBackup) {
        this.config = config;
        this.authenticator = authenticator;
        this.webClient = webClientBuilder.baseUrl(KITE_REST)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.shoonyaBackup = shoonyaBackup;
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
                sorted.sort(Comparator.comparingDouble(NseGainerLoser::pChange));
                int from = gainers ? Math.max(0, sorted.size() - TOP_N) : 0;
                int to = gainers ? sorted.size() : Math.min(TOP_N, sorted.size());
                return sorted.subList(from, to);
            })
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] {} derive failed: {}", gainers ? "gainers" : "losers", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private Mono<List<String>> getUniverse() {
        if (foUniverse != null) return Mono.just(foUniverse);
        return authenticator.getAccessToken()
            .flatMap(token -> webClient.get()
                .uri("/instruments")
                .header("Authorization", "token " + config.getApiKey() + ":" + token)
                .header("X-Kite-Version", "3")
                .retrieve()
                .bodyToMono(String.class))
            .map(this::parseFoUniverse)
            .doOnNext(u -> { foUniverse = u; log.info("[KITE-SEL] Resolved F&O equity universe: {} symbols", u.size()); })
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] instruments download failed: {}", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private List<String> parseFoUniverse(String csv) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String[] lines = csv.split("\n", -1);
        if (lines.length < 2) return List.of();
        String[] header = lines[0].split(",", -1);
        int idxSegment = indexOf(header, "segment");
        int idxType = indexOf(header, "instrument_type");
        int idxName = indexOf(header, "name");
        if (idxSegment < 0 || idxType < 0 || idxName < 0) return List.of();
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split(",", -1);
            if (c.length <= idxName) continue;
            String segment = c[idxSegment].trim();
            String type = c[idxType].trim();
            String name = c[idxName].trim();
            if (!"NFO".equals(segment)) continue;
            if (!Set.of("FUT", "CE", "PE").contains(type)) continue;
            if (name.isEmpty() || INDEX_NAMES.contains(name)) continue;
            names.add("NSE:" + name);
        }
        return new ArrayList<>(names);
    }

    private Mono<List<NseGainerLoser>> quote(List<String> symbols) {
        if (symbols.isEmpty()) return Mono.just(List.of());
        String query = symbols.stream()
            .map(s -> "i=" + URLEncoder.encode(s, StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        return authenticator.getAccessToken()
            .flatMap(token -> webClient.get()
                .uri("/quote?" + query)
                .header("Authorization", "token " + config.getApiKey() + ":" + token)
                .header("X-Kite-Version", "3")
                .retrieve()
                .bodyToMono(String.class))
            .map(this::parseQuotes)
            .onErrorResume(e -> {
                log.warn("[KITE-SEL] quote failed: {}", e.getMessage());
                return Mono.just(List.of());
            });
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
