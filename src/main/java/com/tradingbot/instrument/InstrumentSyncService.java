package com.tradingbot.instrument;

import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the daily instrument master dumps from Kite (NSE + NFO) and persists
 * them into the SQLite-backed {@link InstrumentMasterService}. Without this sync,
 * the instrument DB is empty: no WebSocket token resolution, no option strike
 * lookup, no lot sizes, no tick sizes.
 *
 * Kite instruments CSV columns:
 * instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
 */
@Service
public class InstrumentSyncService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentSyncService.class);

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;
    private final InstrumentMasterService instrumentMaster;

    public InstrumentSyncService(KiteConfig config, KiteAuthenticator authenticator,
                                 WebClient.Builder webClientBuilder, InstrumentMasterService instrumentMaster) {
        this.config = config;
        this.authenticator = authenticator;
        this.instrumentMaster = instrumentMaster;
        this.webClient = webClientBuilder
            .baseUrl(config.getBaseUrl())
            .defaultHeader("X-Kite-Version", "3")
            // NFO dump is ~10-20 MB; raise codec limit beyond the 256 KB default
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
    }

    /**
     * Downloads NFO and NSE instrument dumps and persists them.
     *
     * @return total number of instruments persisted (0 if Kite disabled/unauthenticated)
     */
    public Mono<Integer> syncFromKite() {
        if (!config.isEnabled() || !authenticator.hasValidSession()) {
            log.warn("Kite disabled or not authenticated - instrument sync skipped");
            return Mono.just(0);
        }

        return authenticator.getAccessToken()
            .flatMap(token -> Mono.zip(
                fetchCsv(token, "NFO").defaultIfEmpty(""),
                fetchCsv(token, "NSE").defaultIfEmpty("")
            ))
            .flatMap(tuple -> Mono.fromCallable(() -> {
                List<Instrument> all = new ArrayList<>();
                all.addAll(parseCsv(tuple.getT1()));
                all.addAll(parseCsv(tuple.getT2()));
                return all;
            }).subscribeOn(Schedulers.boundedElastic()))
            .flatMap(instruments -> {
                if (instruments.isEmpty()) {
                    log.warn("Instrument sync produced 0 records - check Kite connectivity");
                    return Mono.just(0);
                }
                return instrumentMaster.saveInstruments(instruments).thenReturn(instruments.size());
            })
            .doOnNext(n -> log.info("Kite instrument sync complete: {} instruments persisted", n))
            .doOnError(e -> log.error("Kite instrument sync failed: {}", e.getMessage()));
    }

    private Mono<String> fetchCsv(String token, String exchange) {
        return webClient.get()
            .uri("/instruments/" + exchange)
            .header("Authorization", "token " + config.getApiKey() + ":" + token)
            .retrieve()
            .bodyToMono(String.class)
            .doOnError(e -> log.error("Failed to download {} instruments: {}", exchange, e.getMessage()));
    }

    /**
     * Parses Kite's instruments CSV into Instrument records.
     */
    List<Instrument> parseCsv(String csv) {
        List<Instrument> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;

        String[] lines = csv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] p = line.split(",");
            if (p.length < 12) continue;

            try {
                String tradingSymbol = unquote(p[2]);
                String exchange = unquote(p[11]);
                String instrumentType = unquote(p[9]);
                String expiry = unquote(p[5]);
                String name = unquote(p[3]);
                String kiteToken = unquote(p[0]);
                String lotStr = unquote(p[8]);
                String tickStr = unquote(p[7]);
                String strikeStr = unquote(p[6]);

                BigDecimal strike = null;
                if (strikeStr != null && !strikeStr.isEmpty()) {
                    strike = new BigDecimal(strikeStr);
                }

                out.add(Instrument.builder()
                    .canonicalSymbol(exchange + ":" + tradingSymbol)
                    .kiteToken(kiteToken)
                    .shoonyaToken(null)
                    .exchange(exchange)
                    .tradingSymbol(tradingSymbol)
                    .name(name)
                    .lotSize(lotStr.isEmpty() ? 1 : (int) Double.parseDouble(lotStr))
                    .tickSize(tickStr.isEmpty() ? new BigDecimal("0.05") : new BigDecimal(tickStr))
                    .instrumentType(instrumentType)
                    .strike(strike)
                    .expiry(expiry.isEmpty() ? null : expiry)
                    .build());
            } catch (Exception e) {
                // Skip malformed rows silently - large dumps occasionally have them
                log.trace("Skipping malformed instrument row {}: {}", i, e.getMessage());
            }
        }
        return out;
    }

    /** Strips surrounding double-quotes (Kite CSV quotes string fields) and trims. */
    private static String unquote(String s) {
        if (s == null) return s;
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
