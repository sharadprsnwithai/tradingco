package com.tradingbot.nse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NSE India API client for fetching Top Gainers and Top Losers stock lists.
 * Manages session cookies required by NSE India's anti-bot protection.
 * Caches the daily gainers/losers response after the 09:26 IST scan.
 */
@Service
public class NseIndiaClient implements GainersLosersSource {

    private static final Logger log = LoggerFactory.getLogger(NseIndiaClient.class);
    private static final String NSE_BASE_URL = "https://www.nseindia.com";
    private static final String GAINERS_API = "/api/live-analysis-variations?index=gainers";
    private static final String LOSERS_API = "/api/live-analysis-variations?index=loosers";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private volatile String sessionCookie;
    private volatile long cookieTimestamp;

    private final Map<String, List<NseGainerLoser>> dailyCache = new ConcurrentHashMap<>();

    /**
     * Constructs the NSE India API client.
     *
     * @param webClientBuilder Spring WebClient builder
     * @param objectMapper     Jackson ObjectMapper for JSON deserialization
     */
    public NseIndiaClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
            .baseUrl(NSE_BASE_URL)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the current Top Gainers list from NSE India.
     * Uses cached data if available for the current day.
     *
     * @return Mono emitting the list of top gainers
     */
    public Mono<List<NseGainerLoser>> fetchGainers() {
        return fetchFromCacheOrApi("gainers", GAINERS_API);
    }

    /**
     * Fetches the current Top Losers list from NSE India.
     * Uses cached data if available for the current day.
     *
     * @return Mono emitting the list of top losers
     */
    public Mono<List<NseGainerLoser>> fetchLosers() {
        return fetchFromCacheOrApi("losers", LOSERS_API);
    }

    /**
     * Clears the daily cache, forcing fresh API calls on next request.
     */
    public void clearCache() {
        dailyCache.clear();
        sessionCookie = null;
        log.info("NSE India client cache cleared");
    }

    private Mono<List<NseGainerLoser>> fetchFromCacheOrApi(String key, String apiPath) {
        List<NseGainerLoser> cached = dailyCache.get(key);
        if (cached != null) {
            return Mono.just(cached);
        }

        return ensureSession()
            .then(fetchApiData(apiPath))
            .doOnNext(data -> dailyCache.put(key, data));
    }

    private Mono<Void> ensureSession() {
        if (sessionCookie != null && (System.currentTimeMillis() - cookieTimestamp) < 3_600_000) {
            return Mono.empty();
        }

        return webClient.get()
            .uri("/")
            .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .exchangeToMono(response -> {
                String cookie = response.cookies().values().stream()
                    .flatMap(List::stream)
                    .map(c -> c.getName() + "=" + c.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");

                if (!cookie.isBlank()) {
                    this.sessionCookie = cookie;
                    this.cookieTimestamp = System.currentTimeMillis();
                    log.info("NSE India session cookie obtained");
                } else {
                    log.warn("No cookies received from NSE India homepage");
                }
                return Mono.just(true);
            })
            .then(Mono.<Void>empty())
            .onErrorResume(e -> {
                log.warn("Failed to establish NSE session: {}", e.getMessage());
                return Mono.empty();
            });
    }

    private Mono<List<NseGainerLoser>> fetchApiData(String apiPath) {
        return webClient.get()
            .uri(apiPath)
            .header(HttpHeaders.COOKIE, sessionCookie != null ? sessionCookie : "")
            .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header(HttpHeaders.REFERER, "https://www.nseindia.com/")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .retrieve()
            .bodyToMono(Map.class)
            .flatMap(response -> {
                try {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawData = (List<Map<String, Object>>) response.get("data");
                    if (rawData == null) {
                        return Mono.just(List.<NseGainerLoser>of());
                    }
                    List<NseGainerLoser> result = rawData.stream()
                        .map(m -> objectMapper.convertValue(m, NseGainerLoser.class))
                        .toList();
                    return Mono.just(result);
                } catch (Exception e) {
                    log.error("Failed to parse NSE API response: {}", e.getMessage());
                    return Mono.just(List.<NseGainerLoser>of());
                }
            })
            .timeout(Duration.ofSeconds(10))
            .onErrorResume(WebClientResponseException.class, e -> {
                log.warn("NSE API {} returned {}: {}", apiPath, e.getStatusCode(), e.getMessage());
                return Mono.just(List.<NseGainerLoser>of());
            })
            .onErrorResume(e -> {
                log.warn("NSE API {} failed: {}", apiPath, e.getMessage());
                return Mono.just(List.<NseGainerLoser>of());
            });
    }
}
