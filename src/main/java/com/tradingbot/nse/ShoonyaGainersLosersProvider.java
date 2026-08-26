package com.tradingbot.nse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.shoonya.ShoonyaAuthenticator;
import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Derives Top Gainers / Top Losers for LVR from Shoonya as a backup to Kite.
 *
 * Approach (mirrors {@link KiteGainersLosersProvider}):
 *   1. Resolve the F&O equity universe from the instrument master.
 *   2. For each underlying's nearest-expiry FUT contract (which now carries a
 *      populated {@code shoonya_token} thanks to the Shoonya master sync), fetch
 *      a quote via Shoonya's GetQuotes REST API.
 *   3. Shoonya's quote directly returns {@code pc} (percentage change vs previous
 *      close); rank and return the top-N gainers / losers.
 *
 * Skips entirely when Shoonya is disabled so the selection chain falls through
 * to the next backup (NSE scraper) without noisy auth failures.
 */
@Service
public class ShoonyaGainersLosersProvider implements GainersLosersSource {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaGainersLosersProvider.class);

    private static final int TOP_N = 15;
    private static final Set<String> INDEX_NAMES = Set.of(
        "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50",
        "SENSEX", "BANKEX", "INDIAVIX"
    );

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final InstrumentMasterService instrumentMaster;

    public ShoonyaGainersLosersProvider(ShoonyaConfig config,
                                        ShoonyaAuthenticator authenticator,
                                        WebClient.Builder webClientBuilder,
                                        ObjectMapper objectMapper,
                                        InstrumentMasterService instrumentMaster) {
        this.config = config;
        this.authenticator = authenticator;
        this.webClient = webClientBuilder.baseUrl(config.getBaseUrl()).build();
        this.objectMapper = objectMapper;
        this.instrumentMaster = instrumentMaster;
    }

    @Override
    public Mono<List<NseGainerLoser>> fetchGainers() {
        return ranked(true);
    }

    @Override
    public Mono<List<NseGainerLoser>> fetchLosers() {
        return ranked(false);
    }

    private Mono<List<NseGainerLoser>> ranked(boolean gainers) {
        if (!config.isEnabled()) return Mono.just(List.of());
        return getUniverse()
            .flatMapMany(Flux::fromIterable)
            .flatMap(this::quote, 12)
            .collectList()
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
                log.warn("[SHOONYA-SEL] {} derive failed: {}", gainers ? "gainers" : "losers", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private Mono<List<String>> getUniverse() {
        return instrumentMaster.getDistinctFoUnderlyingNames()
            .flatMapMany(Flux::fromIterable)
            .filter(name -> !INDEX_NAMES.contains(name))
            .flatMap(name -> instrumentMaster.findNearestExpiring(name, "FUT"))
            .filter(inst -> inst.shoonyaToken() != null && !inst.shoonyaToken().isBlank())
            .map(inst -> inst.canonicalSymbol())
            .collectList();
    }

    private Mono<NseGainerLoser> quote(String canonical) {
        return instrumentMaster.findByCanonicalSymbol(canonical)
            .flatMap(inst -> {
                if (inst.shoonyaToken() == null || inst.shoonyaToken().isBlank()) return Mono.empty();
                String exch = canonical.contains(":") ? canonical.substring(0, canonical.indexOf(':')) : "NFO";
                return authenticator.getAccessToken()
                    .flatMap(token -> webClient.post()
                        .uri("/GetQuotes")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(buildForm(token, exch, inst.shoonyaToken()))
                        .retrieve()
                        .bodyToMono(String.class))
                    .map(body -> parseQuote(canonical, body))
                    .onErrorResume(e -> Mono.empty());
            });
    }

    private String buildForm(String token, String exch, String shoonyaToken) {
        try {
            String jData = objectMapper.writeValueAsString(Map.of(
                "uid", config.getUserId(),
                "exch", exch,
                "token", shoonyaToken
            ));
            return "jData=" + jData + "&jKey=" + token;
        } catch (Exception e) {
            return "jData={}&jKey=" + token;
        }
    }

    private NseGainerLoser parseQuote(String canonical, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"Ok".equalsIgnoreCase(root.path("stat").asText(""))) {
                throw new IllegalStateException("stat=" + root.path("stat").asText());
            }
            JsonNode d = root.path("data");
            if (d.isArray() && d.size() > 0) d = d.get(0);
            double pc = d.path("pc").asDouble();
            double lp = d.path("lp").asDouble();
            double close = d.path("c").asDouble();
            String sym = canonical.contains(":") ? canonical.substring(canonical.indexOf(':') + 1) : canonical;
            return new NseGainerLoser(sym, "EQ", lp, lp - close, pc, 0, 0, 0, close, 0L);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse Shoonya quote: " + e.getMessage(), e);
        }
    }
}
