package com.tradingbot.adapter.shoonya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.MarginInfo;
import com.tradingbot.model.Order;
import com.tradingbot.model.OrderModifyRequest;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Position;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import com.tradingbot.resilience.BrokerBulkheadManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ShoonyaBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaBrokerAdapter.class);
    private static final String BROKER_ID = "SHOONYA";

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final InstrumentMasterService instrumentMaster;
    private final BrokerBulkheadManager bulkheadManager;

    // --- Live WebSocket (NorenWS) state ---
    private final Sinks.Many<Tick> tickSink = Sinks.many().multicast().onBackpressureBuffer();
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final Map<String, String> shoonyaKeyToSymbol = new ConcurrentHashMap<>(); // "NSE|22" -> "NSE:RELIANCE"
    private final Set<String> subscribedKeys = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeatExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shoonya-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
    private volatile boolean heartbeatStarted = false;

    /**
     * Constructs a ShoonyaBrokerAdapter with the specified dependencies.
     *
     * @param config           the Shoonya configuration containing credentials and settings
     * @param authenticator    the authenticator for obtaining access tokens
     * @param webClientBuilder the Spring WebClient builder for HTTP communication
     * @param objectMapper     the Jackson ObjectMapper for JSON serialization/deserialization
     * @param instrumentMaster the instrument master for symbol ↔ token resolution
     */
    @Autowired
    public ShoonyaBrokerAdapter(
        ShoonyaConfig config,
        ShoonyaAuthenticator authenticator,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper,
        InstrumentMasterService instrumentMaster,
        @Autowired(required = false) BrokerBulkheadManager bulkheadManager
    ) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.instrumentMaster = instrumentMaster;
        this.bulkheadManager = bulkheadManager;

        // Backoff retry for transient upstream outages (Shoonya 502/503/504 + timeouts).
        // This is the fix for the "502/504 storm": instead of hammering Shoonya on every
        // poll, failed idempotent reads back off exponentially (max 3, 0.5s→8s, jittered).
        // Order-mutation endpoints (Place/Modify/Cancel) are deliberately excluded so we
        // never auto-retry a write and risk a duplicate fill.
        var transientRetry = Retry.backoff(3, Duration.ofMillis(500))
            .maxBackoff(Duration.ofSeconds(8))
            .jitter(0.5)
            .filter(ShoonyaBrokerAdapter::isTransientUpstreamError)
            .doBeforeRetry(sig -> log.warn("Shoonya upstream transient error ({}), backing off before retry {}: {}",
                sig.failure() != null ? sig.failure().getClass().getSimpleName() : "?",
                sig.totalRetries() + 1,
                sig.failure() != null ? sig.failure().getMessage() : ""));

        org.springframework.web.reactive.function.client.ExchangeFilterFunction backoffRetryFilter =
            (request, next) -> {
                String path = request.url().getPath();
                if (path.contains("/PlaceOrder") || path.contains("/ModifyOrder") || path.contains("/CancelOrder")) {
                    return next.exchange(request);
                }
                return next.exchange(request).retryWhen(transientRetry);
            };

        this.webClient = webClientBuilder
            .baseUrl("https://api.shoonya.com")
            .filter(backoffRetryFilter)
            .build();
    }

    public ShoonyaBrokerAdapter(
        ShoonyaConfig config,
        ShoonyaAuthenticator authenticator,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper,
        InstrumentMasterService instrumentMaster
    ) {
        this(config, authenticator, webClientBuilder, objectMapper, instrumentMaster, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getBrokerId() {
        return BROKER_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAccountId() {
        return config.getAccountId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<Void> authenticate() {
        return authenticator.authenticate().then();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<Boolean> isSessionValid() {
        return Mono.just(authenticator.hasValidSession());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderResult> placeOrder(OrderRequest request) {
        return withTokenRetry(token -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("actid", config.getAccountId());
                payload.put("exch", request.exchange() != null ? request.exchange() : "NFO");
                payload.put("tsym", request.symbol());
                payload.put("qty", String.valueOf(request.quantity()));
                payload.put("prd", mapProductTypeToShoonya(request.productType()));
                payload.put("trantype", request.transactionType() == TransactionType.BUY ? "B" : "S");
                payload.put("prctyp", mapOrderTypeToShoonya(request.orderType()));
                payload.put("ret", "DAY");

                if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("price", request.price().toPlainString());
                }
                if (request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("trgprc", request.triggerPrice().toPlainString());
                }
                if (request.tag() != null) {
                    payload.put("remarks", request.tag());
                }

                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/PlaceOrder")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            JsonNode root = objectMapper.readTree(json);
                            String stat = root.path("stat").asText();
                            if ("Ok".equalsIgnoreCase(stat)) {
                                String norenordno = root.path("norenordno").asText();
                                return OrderResult.success(norenordno, request.tag(), OrderStatus.OPEN);
                            } else {
                                String emsg = root.path("emsg").asText("Shoonya order placement failed");
                                if (isSessionError(emsg)) throw new ShoonyaSessionException(emsg);
                                return OrderResult.failure(request.tag(), emsg);
                            }
                        } catch (Exception e) {
                            return OrderResult.failure(request.tag(), "Parse error: " + e.getMessage());
                        }
                    });
            })
            .onErrorResume(ex -> {
                log.error("Shoonya placeOrder failed: {}", ex.getMessage());
                return Mono.just(OrderResult.failure(request.tag(), ex.getMessage()));
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderResult> modifyOrder(String orderId, OrderModifyRequest request) {
        return withTokenRetry(token -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("actid", config.getAccountId());
                payload.put("norenordno", request.brokerOrderId() != null ? request.brokerOrderId() : orderId);
                if (request.symbol() != null && !request.symbol().isBlank()) {
                    payload.put("tsym", request.symbol());
                }
                payload.put("exch", request.exchange() != null ? request.exchange() : "NFO");
                payload.put("qty", String.valueOf(request.quantity()));
                payload.put("prctyp", mapOrderTypeToShoonya(request.orderType()));
                payload.put("ret", "DAY");

                if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("prc", request.price().toPlainString());
                }
                if (request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("trgprc", request.triggerPrice().toPlainString());
                }

                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/ModifyOrder")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            JsonNode root = objectMapper.readTree(json);
                            if ("Ok".equalsIgnoreCase(root.path("stat").asText())) {
                                String norenordno = root.path("result").asText(orderId);
                                return OrderResult.success(norenordno, request.orderId(), OrderStatus.OPEN);
                            } else {
                                String emsg = root.path("emsg").asText("Shoonya order modification failed");
                                if (isSessionError(emsg)) throw new ShoonyaSessionException(emsg);
                                return OrderResult.failure(request.orderId(), emsg);
                            }
                        } catch (Exception e) {
                            return OrderResult.failure(request.orderId(), "Parse error: " + e.getMessage());
                        }
                    });
            })
            .onErrorResume(ex -> Mono.just(OrderResult.failure(request.orderId(), ex.getMessage())));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<Void> cancelOrder(String orderId) {
        return withTokenRetry(token -> {
            Map<String, Object> payload = Map.of("uid", config.getUserId(), "norenordno", orderId);
            String formBody = buildFormBody(payload, token);

            return webClient.post()
                .uri("/NorenWClientAPI/CancelOrder")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromValue(formBody))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        String stat = root.path("stat").asText();
                        if ("Ok".equalsIgnoreCase(stat)) {
                            return Mono.empty();
                        } else {
                            String emsg = root.path("emsg").asText("Shoonya order cancellation failed");
                            if (isSessionError(emsg)) throw new ShoonyaSessionException(emsg);
                            return Mono.error(new RuntimeException("Shoonya cancelOrder failed: " + emsg));
                        }
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("Failed to parse Shoonya cancel response: " + e.getMessage(), e));
                    }
                });
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<List<Order>> getOrderBook() {
        return withTokenRetry(token -> {
            Map<String, Object> payload = Map.of("uid", config.getUserId());
            String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/OrderBook")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            checkSession(objectMapper.readTree(json));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException ignore) {
                            // parseOrderBook handles malformed JSON below
                        }
                        return parseOrderBook(json);
                    });
        }).onErrorResume(ex -> {
            String detail = ex.getMessage();
            if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                detail = wcre.getStatusCode() + " - " + wcre.getResponseBodyAsString();
            }
            log.error("Failed to fetch Shoonya order book: {}", detail);
            return Mono.just(List.of());
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<List<Position>> getPositions() {
        return withTokenRetry(token -> {
            Map<String, Object> payload = Map.of("uid", config.getUserId(), "actid", config.getAccountId());
            String formBody = buildFormBody(payload, token);

            log.info("Fetching Shoonya positions for user: {}", config.getUserId());
                return webClient.post()
                    .uri("/NorenWClientAPI/PositionBook")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            checkSession(objectMapper.readTree(json));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException ignore) {
                            // parsePositions handles malformed JSON below
                        }
                        return parsePositions(json);
                    });
        }).onErrorResume(ex -> {
            log.error("Failed to fetch Shoonya positions: {}", ex.getMessage());
            return Mono.just(List.of());
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<MarginInfo> getMargins() {
        return withTokenRetry(token -> {
            Map<String, Object> payload = Map.of("uid", config.getUserId(), "actid", config.getAccountId());
            String formBody = buildFormBody(payload, token);

            return webClient.post()
                .uri("/NorenWClientAPI/Limits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromValue(formBody))
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        checkSession(root);
                        BigDecimal cash = BigDecimal.valueOf(root.path("cash").asDouble(0.0));
                        BigDecimal marginUsed = BigDecimal.valueOf(root.path("marginused").asDouble(0.0));
                        BigDecimal payin = BigDecimal.valueOf(root.path("payin").asDouble(0.0));
                        BigDecimal available = cash.add(payin).subtract(marginUsed);
                        BigDecimal total = cash.add(payin);
                        return MarginInfo.of(config.getAccountId(), BROKER_ID, available, marginUsed, total, cash);
                    } catch (Exception e) {
                        return MarginInfo.of(config.getAccountId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                    }
                });
        }).onErrorResume(ex -> Mono.just(MarginInfo.of(config.getAccountId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    /**
     * Subscribes to real market data via Shoonya's NorenWS JSON WebSocket feed.
     * Canonical symbols ("NSE:RELIANCE") are resolved to Shoonya exchange|token keys
     * through the instrument master. NOTE: Shoonya tokens require a Shoonya master
     * contract sync (not yet implemented) — until then this resolves nothing and the
     * feed stays off. Failover safety: when the adapter is disabled this returns an
     * empty stream rather than synthetic data.
     *
     * @param symbols the list of canonical trading symbols to subscribe to
     * @return a reactive Flux emitting real exchange Tick objects
     */
    @Override
    public Flux<Tick> subscribeMarketData(List<String> symbols) {
        if (!config.isEnabled()) {
            log.debug("Shoonya adapter disabled - market data feed not started");
            return Flux.empty();
        }
        return authenticator.getAccessToken()
            .flatMapMany(token -> Mono.fromCallable(() -> resolveShoonyaKeys(symbols))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // JDBC lookups must not block reactor threads
                .flatMapMany(newKeys -> {
                    if (newKeys.isEmpty() && subscribedKeys.isEmpty()) {
                        log.warn("No Shoonya tokens resolved for {} symbols - feed NOT started", symbols.size());
                        return Flux.empty();
                    }
                    // WebSocket session handshake requires the susertoken (NOT the REST access_token)
                    ensureWsConnected(authenticator.getSUserToken(), newKeys);
                    return tickSink.asFlux();
                }));
    }

    /**
     * Resolves canonical symbols to Shoonya "EXCHANGE|token" subscription keys.
     */
    private List<String> resolveShoonyaKeys(List<String> symbols) {
        List<String> keys = new ArrayList<>();
        for (String sym : symbols) {
            try {
                // resolveForMarketData maps abstract symbols (NFO:NIFTY_FUT / NFO:NIFTY_50)
                // to the nearest-expiry contract, whose shoonya_token we back-filled via sync.
                var inst = instrumentMaster.resolveForMarketData(sym).blockOptional();
                if (inst.isPresent() && inst.get().shoonyaToken() != null && !inst.get().shoonyaToken().isBlank()) {
                    String exchange = sym.contains(":") ? sym.substring(0, sym.indexOf(':')) : "NSE";
                    String key = exchange + "|" + inst.get().shoonyaToken().trim();
                    registerKeyMapping(key, sym);
                    keys.add(key);
                } else {
                    log.warn("No Shoonya token found for {} - skipping", sym);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve Shoonya token for {}: {}", sym, e.getMessage());
            }
        }
        return keys;
    }

    /**
     * Opens the NorenWS connection on first use; subscribes additional keys on later calls.
     */
    private synchronized void ensureWsConnected(String susertoken, List<String> newKeys) {
        WebSocket existing = wsRef.get();
        if (existing != null && !existing.isOutputClosed()) {
            subscribeKeys(newKeys);
            return;
        }

        log.info("Connecting Shoonya NorenWS feed for {} keys...", newKeys.size());
        subscribedKeys.addAll(newKeys);

        HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(config.getWsUrl()), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    log.info("Shoonya NorenWS socket open - sending connection init");
                    wsRef.set(webSocket);
                    webSocket.request(1);
                    // NorenWS connection handshake
                    sendJson(webSocket, Map.of(
                        "t", "c",
                        "uid", config.getUserId(),
                        "actid", config.getAccountId(),
                        "source", "API",
                        "susertoken", susertoken
                    ));
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    handleWsMessage(webSocket, data.toString());
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    log.warn("Shoonya NorenWS closed: {} {}", statusCode, reason);
                    wsRef.compareAndSet(webSocket, null);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    log.error("Shoonya NorenWS error: {}", error.getMessage());
                    wsRef.compareAndSet(webSocket, null);
                }
            })
            .exceptionally(ex -> {
                log.error("Shoonya NorenWS connect failed: {}", ex.getMessage());
                return null;
            });

        startHeartbeatIfNeeded();
    }

    /**
     * Handles a NorenWS JSON message: connection ack, heartbeat, or touchline tick.
     */
    private void handleWsMessage(WebSocket webSocket, String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String type = node.path("t").asText("");

            switch (type) {
                case "ck" -> {
                    // Connection ack. On success (s=OK) subscribe pending keys; on auth
                    // rejection (s=ERR / "Invalid Session") the cached token is dead - drop
                    // the socket and reconnect with a freshly re-authed token (backed off).
                    String s = node.path("s").asText("");
                    String emsg = node.path("emsg").asText(node.path("msg").asText(""));
                    if (!"OK".equalsIgnoreCase(s)) {
                        log.warn("Shoonya NorenWS connection rejected (s={}, emsg={})", s, emsg);
                        if (isSessionError(emsg) || "ERR".equalsIgnoreCase(s)) {
                            wsRef.compareAndSet(webSocket, null);
                            scheduleWsReconnectWithNewToken();
                        }
                        return;
                    }
                    log.info("Shoonya NorenWS authenticated - subscribing {} keys", subscribedKeys.size());
                    subscribeKeys(new ArrayList<>(subscribedKeys));
                }
                case "tk", "tf" -> {
                    Tick tick = mapShoonyaTick(node);
                    if (tick != null) tickSink.tryEmitNext(tick);
                }
                case "h" -> { /* heartbeat from server - no action */ }
                default -> log.trace("Shoonya NorenWS message type {}: {}", type, message);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Shoonya WS message: {}", e.getMessage());
        }
    }

    /**
     * Subscribes the given keys that are not yet subscribed. NorenWS touchline
     * subscription: {"t":"t","k":"NSE|22#NFO|44671"}
     */
    private void subscribeKeys(List<String> keys) {
        WebSocket ws = wsRef.get();
        if (ws == null || ws.isOutputClosed() || keys.isEmpty()) return;
        List<String> fresh = new ArrayList<>();
        for (String k : keys) {
            if (subscribedKeys.add(k)) fresh.add(k);
        }
        if (fresh.isEmpty()) return;
        sendJson(ws, Map.of("t", "t", "k", String.join("#", fresh)));
        log.info("Shoonya NorenWS: subscribed {} keys", fresh.size());
    }

    private void sendJson(WebSocket ws, Map<String, String> payload) {
        try {
            ws.sendText(objectMapper.writeValueAsString(payload), true);
        } catch (Exception e) {
            log.warn("Failed to send Shoonya WS message: {}", e.getMessage());
        }
    }

    private void startHeartbeatIfNeeded() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            WebSocket ws = wsRef.get();
            if (ws != null && !ws.isOutputClosed()) {
                sendJson(ws, Map.of("t", "h"));
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Recovers the NorenWS feed after a session/auth rejection. Invalidates the dead
     * token and reconnects with a freshly re-authed one. getAccessToken() is
     * single-flight, so concurrent triggers collapse into one re-auth, and the 2s
     * delay provides backoff so a flapping WS does not login in a tight loop.
     */
    private void scheduleWsReconnectWithNewToken() {
        if (!config.isEnabled()) return;
        log.warn("Shoonya NorenWS session invalid - invalidating token and reconnecting with fresh token (backed off)");
        authenticator.invalidateToken();
        authenticator.getAccessToken()
            .delayElement(Duration.ofSeconds(2))
            .doOnNext(tok -> {
                String suToken = authenticator.getSUserToken();
                if (suToken != null) {
                    log.info("Shoonya NorenWS re-authed - reconnecting feed");
                    ensureWsConnected(suToken, new ArrayList<>(subscribedKeys));
                }
            })
            .onErrorResume(ex -> {
                log.error("Shoonya NorenWS re-auth failed: {}", ex.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }

    /**
     * Registers a Shoonya key → canonical symbol mapping for tick routing.
     * Package-private so unit tests can seed mappings without a WebSocket.
     */
    void registerKeyMapping(String key, String canonicalSymbol) {
        shoonyaKeyToSymbol.put(key, canonicalSymbol);
    }

    /**
     * Maps a NorenWS touchline message (tk/tf) to the internal Tick model.
     * Package-private for unit testing.
     */
    Tick mapShoonyaTick(JsonNode node) {
        String exchange = node.path("e").asText("");
        String token = node.path("tk").asText("");
        String canonical = shoonyaKeyToSymbol.get(exchange + "|" + token);
        if (canonical == null) return null;

        return Tick.builder()
            .brokerId(BROKER_ID)
            .symbol(canonical)
            .exchange(exchange)
            .instrumentToken(token)
            .ltp(parseDecimal(node, "lp"))
            .open(parseDecimal(node, "o"))
            .high(parseDecimal(node, "h"))
            .low(parseDecimal(node, "l"))
            .close(parseDecimal(node, "c"))
            .volume((long) node.path("v").asDouble(0))
            .timestamp(Instant.now())
            .build();
    }

    private static BigDecimal parseDecimal(JsonNode node, String field) {
        String v = node.path(field).asText("");
        if (v.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Disconnects the Shoonya WebSocket and stops the heartbeat on shutdown.
     */
    @PreDestroy
    public void shutdownWebSocket() {
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                log.warn("Error closing Shoonya WS: {}", e.getMessage());
            }
        }
        heartbeatExecutor.shutdownNow();
        subscribedKeys.clear();
        shoonyaKeyToSymbol.clear();
    }

    /**
     * Builds a URL-encoded form body from the payload map and token.
     *
     * @param payload the map of parameters to serialize
     * @param token   the authentication token to include
     * @return the encoded form body string
     */
    private String buildFormBody(Map<String, Object> payload, String token) {
        try {
            return "jData=" + objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "jData={}";
        }
    }

    /**
     * Parses the JSON response from the order book API into a list of Order objects.
     *
     * @param json the JSON response string from the Shoonya API
     * @return a list of parsed Order objects
     */
    private List<Order> parseOrderBook(String json) {
        List<Order> orders = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String prd = node.path("prd").asText("I");
                    orders.add(Order.builder()
                        .id(node.path("remarks").asText(node.path("norenordno").asText()))
                        .brokerOrderId(node.path("norenordno").asText())
                        .accountId(config.getAccountId())
                        .brokerId(BROKER_ID)
                        .symbol(node.path("tsym").asText())
                        .exchange(node.path("exch").asText())
                        .instrumentToken(node.path("token").asText())
                        .transactionType("B".equalsIgnoreCase(node.path("trantype").asText()) ? TransactionType.BUY : TransactionType.SELL)
                        .quantity(node.path("qty").asInt())
                        .filledQuantity(node.path("fillshares").asInt(0))
                        .price(BigDecimal.valueOf(node.path("prc").asDouble(0.0)))
                        .triggerPrice(BigDecimal.valueOf(node.path("trgprc").asDouble(0.0)))
                        .averagePrice(BigDecimal.valueOf(node.path("avgprc").asDouble(0.0)))
                        .orderType(mapShoonyaOrderType(node.path("prctyp").asText()))
                        .productType(mapShoonyaProductType(prd))
                        .bookType("I".equalsIgnoreCase(prd) ? BookType.INTRADAY : BookType.POSITIONAL)
                        .status(mapShoonyaOrderStatus(node.path("status").asText()))
                        .statusMessage(node.path("rejreason").asText(null))
                        .tag(node.path("remarks").asText(null))
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Shoonya order book: {}", e.getMessage());
        }
        return orders;
    }

    /**
     * Parses the JSON response from the positions API into a list of Position objects.
     *
     * @param json the JSON response string from the Shoonya API
     * @return a list of parsed Position objects
     */
    private List<Position> parsePositions(String json) {
        List<Position> positions = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String prd = node.path("prd").asText("I");
                    BookType bookType = "I".equalsIgnoreCase(prd) ? BookType.INTRADAY : BookType.POSITIONAL;
                    positions.add(Position.builder()
                        .accountId(config.getAccountId())
                        .brokerId(BROKER_ID)
                        .symbol(node.path("tsym").asText())
                        .exchange(node.path("exch").asText())
                        .instrumentToken(node.path("token").asText())
                        .productType(mapShoonyaProductType(prd))
                        .bookType(bookType)
                        .netQuantity(node.path("netqty").asInt(0))
                        .buyQuantity(node.path("daybuyqty").asInt(0))
                        .sellQuantity(node.path("daysellqty").asInt(0))
                        .buyAveragePrice(BigDecimal.valueOf(node.path("daybuyavgprc").asDouble(0.0)))
                        .sellAveragePrice(BigDecimal.valueOf(node.path("daysellavgprc").asDouble(0.0)))
                        .ltp(BigDecimal.valueOf(node.path("lp").asDouble(0.0)))
                        .mtmPnl(BigDecimal.valueOf(node.path("m2m").asDouble(0.0)))
                        .realizedPnl(BigDecimal.valueOf(node.path("rpnl").asDouble(0.0)))
                        .unrealizedPnl(BigDecimal.valueOf(node.path("urmtom").asDouble(0.0)))
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Shoonya positions: {}", e.getMessage());
        }
        return positions;
    }

    /**
     * Maps a ProductType enum to the corresponding Shoonya product code.
     *
     * @param type the ProductType to map
     * @return the Shoonya product code string
     */
    private String mapProductTypeToShoonya(ProductType type) {
        return switch (type) {
            case MIS -> "I";
            case NRML -> "M";
            case CNC -> "C";
        };
    }

    /**
     * Maps a Shoonya product code to the corresponding ProductType enum.
     *
     * @param shoonyaPrd the Shoonya product code string
     * @return the mapped ProductType enum value
     */
    private ProductType mapShoonyaProductType(String shoonyaPrd) {
        if ("I".equalsIgnoreCase(shoonyaPrd)) return ProductType.MIS;
        if ("M".equalsIgnoreCase(shoonyaPrd)) return ProductType.NRML;
        if ("C".equalsIgnoreCase(shoonyaPrd)) return ProductType.CNC;
        return ProductType.MIS;
    }

    /**
     * Maps an OrderType enum to the corresponding Shoonya order type code.
     *
     * @param type the OrderType to map
     * @return the Shoonya order type code string
     */
    private String mapOrderTypeToShoonya(OrderType type) {
        return switch (type) {
            case MARKET -> "MKT";
            case LIMIT -> "LMT";
            case SL_M -> "SL-MKT";
            case SL_L -> "SL-LMT";
        };
    }

    /**
     * Maps a Shoonya order type code to the corresponding OrderType enum.
     *
     * @param prctyp the Shoonya order type code string
     * @return the mapped OrderType enum value
     */
    private OrderType mapShoonyaOrderType(String prctyp) {
        if ("MKT".equalsIgnoreCase(prctyp)) return OrderType.MARKET;
        if ("LMT".equalsIgnoreCase(prctyp)) return OrderType.LIMIT;
        if ("SL-MKT".equalsIgnoreCase(prctyp)) return OrderType.SL_M;
        if ("SL-LMT".equalsIgnoreCase(prctyp)) return OrderType.SL_L;
        return OrderType.LIMIT;
    }

    /**
     * Maps a Shoonya order status string to the corresponding OrderStatus enum.
     *
     * @param status the Shoonya order status string
     * @return the mapped OrderStatus enum value
     */
    private OrderStatus mapShoonyaOrderStatus(String status) {
        if (status == null) return OrderStatus.PENDING;
        return switch (status.toUpperCase()) {
            case "COMPLETE" -> OrderStatus.FILLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "CANCELED", "CANCELLED" -> OrderStatus.CANCELLED;
            case "TRIGGER_PENDING" -> OrderStatus.TRIGGER_PENDING;
            case "OPEN" -> OrderStatus.OPEN;
            default -> OrderStatus.PENDING;
        };
    }

    /**
     * Executes an authenticated Shoonya REST call, transparently re-authenticating
     * and retrying exactly once when Shoonya rejects the session token
     * (HTTP 401/403). Shoonya kills sessions overnight / on expiry, so a cached
     * or on-disk token that is still "fresh" locally can be dead at the server —
     * without this retry, every subsequent call fails until a manual restart.
     *
     * @param apiCall the API call to execute with a valid session token
     * @param <T>     the response type
     * @return a reactive Mono containing the API response
     */
    private <T> Mono<T> withTokenRetry(java.util.function.Function<String, Mono<T>> apiCall) {
        Mono<T> op = authenticator.getAccessToken()
            .flatMap(apiCall)
            .onErrorResume(ShoonyaBrokerAdapter::isTokenError, ex -> {
                log.warn("Shoonya rejected session token - re-authenticating and retrying once");
                // Clear the dead token. Single-flight in ShoonyaAuthenticator guarantees that
                // all concurrent callers share ONE re-auth instead of each firing their own
                // (which previously caused the INVALID_SESSION death-spiral).
                authenticator.invalidateToken();
                // Small backoff so a burst of concurrent 401s doesn't instantly re-login.
                return Mono.delay(Duration.ofSeconds(1))
                    .then(authenticator.getAccessToken())
                    .flatMap(apiCall);
            });
        return bulkheadManager != null ? bulkheadManager.executeShoonya(op) : op;
    }

    /**
     * Determines whether an error indicates an invalid/expired Shoonya session
     * token (HTTP 401/403, or a ShoonyaSessionException raised when Shoonya returns
     * a 200 response whose body reports "Invalid Session"/"Not_Ok" auth failure).
     *
     * @param ex the error to inspect
     * @return true if the error is an authentication/token failure
     */
    private static boolean isTokenError(Throwable ex) {
        if (ex instanceof ShoonyaSessionException) {
            return true;
        }
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 401 || status == 403;
        }
        String msg = ex.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("403") || msg.toLowerCase().contains("unauthorized"));
    }

    /**
     * Thrown when a Shoonya REST response indicates the session token is no longer
     * valid (HTTP 200 body with stat=Not_Ok and a session-related message). Wrapping
     * it in a dedicated exception lets {@link #withTokenRetry} detect the failure and
     * transparently re-authenticate, which the generic 401/403 check could not do
     * for Shoonya's 200-with-error responses.
     */
    private static final class ShoonyaSessionException extends RuntimeException {
        ShoonyaSessionException(String message) {
            super(message);
        }
    }

    /**
     * Detects whether a Shoonya error message denotes an invalid/expired session
     * (as opposed to a benign business error like "insufficient funds"). Used to
     * decide whether to trigger a re-auth.
     */
    private static boolean isSessionError(String emsg) {
        if (emsg == null) return false;
        String l = emsg.toLowerCase();
        return l.contains("session")
            || l.contains("unauthoriz")
            || l.contains("login")
            || l.contains("token")
            || l.contains("not logged")
            || l.contains("expire")
            || l.contains("401")
            || l.contains("403");
    }

    /**
     * Inspects a Shoonya JSON response; throws {@link ShoonyaSessionException} when the
     * response reports an auth/session failure so the caller can re-authenticate.
     */
    private static void checkSession(JsonNode root) {
        if ("Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
            String emsg = root.path("emsg").asText("");
            if (isSessionError(emsg)) {
                throw new ShoonyaSessionException(emsg);
            }
        }
    }

    /**
     * Classifies a transport-level failure as a transient upstream outage that is
     * worth backing off and retrying: HTTP 502/503/504, request-level failures
     * (DNS/connect/TLS), and IO/timeout exceptions. Auth errors (401/403) are NOT
     * included — those go through the re-auth path instead.
     */
    private static boolean isTransientUpstreamError(Throwable ex) {
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            int s = wcre.getStatusCode().value();
            return s == 502 || s == 503 || s == 504;
        }
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            return true;
        }
        if (ex instanceof java.io.IOException) {
            return true;
        }
        String msg = ex.getMessage();
        if (msg != null) {
            String m = msg.toLowerCase();
            return m.contains("timed out") || m.contains("timeout")
                || m.contains("connection reset") || m.contains("broken pipe")
                || m.contains("connection refused") || m.contains("502")
                || m.contains("503") || m.contains("504");
        }
        return false;
    }
}
