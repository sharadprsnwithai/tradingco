package com.tradingbot.instrument;

import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Downloads Shoonya's bulk symbol masters (zipped text files) and back-fills the
 * {@code shoonya_token} column on the instrument rows created from the Kite master.
 *
 * Without this, every Shoonya lookup fails because {@code shoonya_token} is NULL,
 * so the Shoonya feed (the configured failover broker) can never start.
 *
 * Shoonya publishes masters as zipped text files:
 *   https://api.shoonya.com/NSE_symbols.txt.zip
 *   https://api.shoonya.com/NFO_symbols.txt.zip
 * Format (comma-separated, no auth required): Token,Symbol,Series,Exchange,Segment,
 * InstrumentType,Expiry,OptionType,StrikePrice,LotSize,TickSize,...
 * Equity symbols carry an "-EQ" suffix (RELIANCE-EQ); F&O symbols match Kite's
 * trading symbols exactly (NIFTY26AUGFUT). Both the suffixed and bare forms are
 * mapped so the app's canonical symbols (NSE:RELIANCE) resolve correctly.
 */
@Service
public class ShoonyaInstrumentSyncService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaInstrumentSyncService.class);

    private static final String MASTER_BASE = "https://api.shoonya.com/";
    private static final List<String> EXCHANGES = List.of("NSE", "NFO");

    private final ShoonyaConfig config;
    private final WebClient webClient;
    private final InstrumentMasterService instrumentMaster;

    public ShoonyaInstrumentSyncService(ShoonyaConfig config,
                                        WebClient.Builder webClientBuilder,
                                        InstrumentMasterService instrumentMaster) {
        this.config = config;
        this.webClient = webClientBuilder
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
        this.instrumentMaster = instrumentMaster;
    }

    /**
     * Downloads Shoonya masters for the configured exchanges and back-fills tokens.
     *
     * @return Mono emitting the total number of instruments whose Shoonya token was set
     */
    public Mono<Integer> syncFromShoonya() {
        if (!config.isEnabled()) {
            log.warn("[SHOONYA-SYNC] Shoonya disabled - master token sync skipped");
            return Mono.just(0);
        }
        return Flux.fromIterable(EXCHANGES)
            .flatMap(exch -> fetchMaster(exch)
                .flatMap(map -> instrumentMaster.updateShoonyaTokens(map)
                    .doOnNext(c -> log.info("[SHOONYA-SYNC] {} master: {} tokens mapped", exch, c))
                    .onErrorResume(e -> {
                        log.warn("[SHOONYA-SYNC] token update failed for {}: {}", exch, e.getMessage());
                        return Mono.just(0);
                    })), 2)
            .reduce(0, Integer::sum)
            .doOnNext(total -> log.info("[SHOONYA-SYNC] Total Shoonya tokens mapped: {}", total));
    }

    private Mono<Map<String, String>> fetchMaster(String exch) {
        String url = MASTER_BASE + exch + "_symbols.txt.zip";
        return webClient.get().uri(url).retrieve().bodyToMono(byte[].class)
            .map(bytes -> parseZip(exch, bytes))
            .onErrorResume(e -> {
                log.warn("[SHOONYA-SYNC] download failed for {}: {}", exch, e.getMessage());
                return Mono.just(Map.of());
            });
    }

    private Map<String, String> parseZip(String exch, byte[] zipBytes) {
        Map<String, String> map = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            var entry = zis.getNextEntry();
            if (entry == null) return map;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] f = line.split(",", -1);
                    if (f.length < 2) continue;
                    String token = f[0].trim();
                    String tsym = f[1].trim();
                    if (token.isEmpty() || tsym.isEmpty()) continue;
                    if (!token.chars().allMatch(Character::isDigit)) continue; // skip header / bad rows
                    map.put(exch + ":" + tsym, token);
                    if (tsym.endsWith("-EQ")) {
                        String bare = tsym.substring(0, tsym.length() - 3);
                        map.put(exch + ":" + bare, token);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SHOONYA-SYNC] parse error for {}: {}", exch, e.getMessage());
        }
        return map;
    }
}
