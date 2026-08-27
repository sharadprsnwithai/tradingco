package com.tradingbot.adapter.kite;

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
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnError;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class KiteBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(KiteBrokerAdapter.class);
    private static final String BROKER_ID = "ZERODHA";

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final InstrumentMasterService instrumentMaster;

    // --- Live WebSocket (KiteTicker) state ---
    private final Sinks.Many<Tick> tickSink = Sinks.many().multicast().onBackpressureBuffer();
    private final AtomicReference<KiteTicker> tickerRef = new AtomicReference<>();
    // The access token the currently-connected ticker was built with. Used to detect
    // when a re-auth minted a new token so the live WS can be re-keyed (Kite 403 fix).
    private final AtomicReference<String> tickerToken = new AtomicReference<>();
    private final Object tickerRekeyLock = new Object();
    private volatile long lastWsRekeyAttemptMillis = 0;
    private final Map<Long, Set<String>> tokenToSymbols = new ConcurrentHashMap<>();
    private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

    /**
     * Constructs a new KiteBrokerAdapter with the provided dependencies.
     *
     * @param config            the Kite broker configuration
     * @param authenticator     the authenticator for managing Kite sessions
     * @param webClientBuilder  the WebClient builder for HTTP communication
     * @param objectMapper      the Jackson ObjectMapper for JSON parsing
     * @param instrumentMaster  the instrument master for symbol ↔ token resolution
     */
    public KiteBrokerAdapter(KiteConfig config, KiteAuthenticator authenticator, WebClient.Builder webClientBuilder, ObjectMapper objectMapper, InstrumentMasterService instrumentMaster) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.instrumentMaster = instrumentMaster;
        this.webClient = webClientBuilder
            .baseUrl(config.getBaseUrl())
            .defaultHeader("X-Kite-Version", "3")
            .build();
        // Re-key the live WebSocket whenever a fresh access token is minted (e.g. after
        // daily expiry), otherwise SDK auto-reconnect keeps using the stale token → 403.
        authenticator.setTokenRenewedListener(this::rekeyTickerOnTokenRenewal);
    }

    /**
     * Returns the broker identifier for Zerodha.
     *
     * @return the broker ID string "ZERODHA"
     */
    @Override
    public String getBrokerId() {
        return BROKER_ID;
    }

    /**
     * Returns the account identifier from the configuration.
     *
     * @return the user ID configured for Kite
     */
    @Override
    public String getAccountId() {
        return config.getUserId();
    }

    /**
     * Initiates authentication with Kite broker.
     *
     * @return a reactive Mono that completes when authentication is done
     */
    @Override
    public Mono<Void> authenticate() {
        return authenticator.authenticate().then();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * Checks if the current session is valid.
     *
     * @return a reactive Mono containing true if session is valid, false otherwise
     */
    @Override
    public Mono<Boolean> isSessionValid() {
        return Mono.just(authenticator.hasValidSession());
    }

    /**
     * Places a new order with Kite broker.
     *
     * @param request the order request containing symbol, quantity, price, and other details
     * @return a reactive Mono containing the order result with broker order ID
     */
    @Override
    public Mono<OrderResult> placeOrder(OrderRequest request) {
        return withTokenRetry(token -> {
            var bodyBuilder = BodyInserters.fromFormData("tradingsymbol", request.symbol())
                .with("exchange", request.exchange() != null ? request.exchange() : "NFO")
                .with("transaction_type", request.transactionType().name())
                .with("order_type", mapOrderTypeToKite(request.orderType()))
                .with("quantity", String.valueOf(request.quantity()))
                .with("product", mapProductTypeToKite(request.productType()))
                .with("validity", "DAY");

            // Kite rejects a price field on MARKET orders
            if (request.orderType() != OrderType.MARKET && request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                bodyBuilder = bodyBuilder.with("price", request.price().toPlainString());
            }
            // trigger_price is only meaningful for SL/SL-M orders
            if ((request.orderType() == OrderType.SL_M || request.orderType() == OrderType.SL_L)
                && request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                bodyBuilder = bodyBuilder.with("trigger_price", request.triggerPrice().toPlainString());
            }
            if (request.tag() != null) {
                bodyBuilder = bodyBuilder.with("tag", request.tag());
            }

            return webClient.post()
                .uri("/orders/regular")
                .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(bodyBuilder)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        if ("success".equalsIgnoreCase(root.path("status").asText())) {
                            String brokerOrderId = root.path("data").path("order_id").asText();
                            return OrderResult.success(brokerOrderId, request.tag(), OrderStatus.OPEN);
                        } else {
                            String msg = root.path("message").asText("Kite order placement failed");
                            return OrderResult.failure(request.tag(), msg);
                        }
                    } catch (Exception e) {
                        return OrderResult.failure(request.tag(), "Error parsing Kite response: " + e.getMessage());
                    }
                })
                .onErrorResume(ex -> {
                    if (isTokenError(ex)) return Mono.error(ex); // propagate so withTokenRetry re-authenticates
                    log.error("Error placing Kite order for {}: {}", request.symbol(), ex.getMessage());
                    return Mono.just(OrderResult.failure(request.tag(), ex.getMessage()));
                });
        })
        // Token retry also failed (or non-token error after retry): fail gracefully, never throw
        .onErrorResume(ex -> {
            log.error("Kite order placement failed for {} after token retry: {}", request.symbol(), ex.getMessage());
            return Mono.just(OrderResult.failure(request.tag(), ex.getMessage()));
        });
    }

    /**
     * Modifies an existing order with Kite broker.
     *
     * @param orderId the broker order ID to modify
     * @param request the modification request containing new quantity, order type, price, etc.
     * @return a reactive Mono containing the order result
     */
    @Override
    public Mono<OrderResult> modifyOrder(String orderId, OrderModifyRequest request) {
        return withTokenRetry(token -> {
            var bodyBuilder = BodyInserters.fromFormData("quantity", String.valueOf(request.quantity()))
                .with("order_type", mapOrderTypeToKite(request.orderType()))
                .with("validity", "DAY");

            if (request.orderType() != OrderType.MARKET && request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                bodyBuilder = bodyBuilder.with("price", request.price().toPlainString());
            }
            if (request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                bodyBuilder = bodyBuilder.with("trigger_price", request.triggerPrice().toPlainString());
            }

            return webClient.put()
                .uri("/orders/regular/{order_id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(bodyBuilder)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        if ("success".equalsIgnoreCase(root.path("status").asText())) {
                            String brokerOrderId = root.path("data").path("order_id").asText(orderId);
                            return OrderResult.success(brokerOrderId, request.orderId(), OrderStatus.OPEN);
                        } else {
                            return OrderResult.failure(request.orderId(), root.path("message").asText());
                        }
                    } catch (Exception e) {
                        return OrderResult.failure(request.orderId(), "Parse error: " + e.getMessage());
                    }
                })
                .onErrorResume(ex -> {
                    if (isTokenError(ex)) return Mono.error(ex); // propagate so withTokenRetry re-authenticates
                    return Mono.just(OrderResult.failure(request.orderId(), ex.getMessage()));
                });
        })
        // Token retry also failed: fail gracefully, never throw
        .onErrorResume(ex -> Mono.just(OrderResult.failure(request.orderId(), ex.getMessage())));
    }

    /**
     * Cancels an existing order with Kite broker.
     *
     * @param orderId the broker order ID to cancel
     * @return a reactive Mono that completes when cancellation is done
     */
    @Override
    public Mono<Void> cancelOrder(String orderId) {
        return withTokenRetry(token -> webClient.delete()
            .uri("/orders/regular/{order_id}", orderId)
            .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
            .retrieve()
            .bodyToMono(Void.class)
            .onErrorResume(ex -> {
                if (isTokenError(ex)) return Mono.error(ex); // propagate so withTokenRetry re-authenticates
                log.error("Failed to cancel Kite order {}: {}", orderId, ex.getMessage());
                return Mono.empty();
            })
        )
        // Token retry also failed: fail gracefully, never throw
        .onErrorResume(ex -> {
            log.error("Failed to cancel Kite order {} after token retry: {}", orderId, ex.getMessage());
            return Mono.empty();
        });
    }

    /**
     * Retrieves the order book from Kite broker.
     *
     * @return a reactive Mono containing the list of orders
     */
    @Override
    public Mono<List<Order>> getOrderBook() {
        // Errors propagate to callers (reconciler handles them) — an empty list here
        // would be indistinguishable from "no orders", which is dangerous for a money system.
        return withTokenRetry(token -> webClient.get()
            .uri("/orders")
            .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseOrderBook)
        );
    }

    /**
     * Retrieves current positions from Kite broker.
     *
     * @return a reactive Mono containing the list of positions
     */
    @Override
    public Mono<List<Position>> getPositions() {
        // Errors propagate — silently returning empty would make rehydration believe
        // there are no open positions after a crash, risking double-entry.
        return withTokenRetry(token -> webClient.get()
            .uri("/portfolio/positions")
            .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
            .retrieve()
            .bodyToMono(String.class)
            .map(json -> {
                log.debug("Kite positions raw JSON: {}", json);
                return parsePositions(json);
            })
        );
    }

    /**
     * Retrieves margin information from Kite broker.
     *
     * @return a reactive Mono containing the margin information
     */
    @Override
    public Mono<MarginInfo> getMargins() {
        return withTokenRetry(token -> webClient.get()
            .uri("/user/margins")
            .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
            .retrieve()
            .bodyToMono(String.class)
            .map(json -> {
                try {
                    JsonNode root = objectMapper.readTree(json);
                    JsonNode equity = root.path("data").path("equity");
                    BigDecimal available = BigDecimal.valueOf(equity.path("available").path("live_balance").asDouble(0.0));
                    BigDecimal used = BigDecimal.valueOf(equity.path("utilised").path("debits").asDouble(0.0));
                    BigDecimal total = available.add(used);
                    BigDecimal cash = BigDecimal.valueOf(equity.path("available").path("cash").asDouble(0.0));
                    return MarginInfo.of(config.getUserId(), BROKER_ID, available, used, total, cash);
                } catch (Exception e) {
                    return MarginInfo.of(config.getUserId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                }
            })
            .onErrorResume(ex -> {
                if (isTokenError(ex)) return Mono.error(ex); // propagate so withTokenRetry re-authenticates
                return Mono.error(ex); // margins are informational but still fail loudly
            })
        );
    }

    /**
     * Subscribes to real market data for the specified symbols via Kite's binary
     * WebSocket feed (KiteTicker). Symbols are canonical ("NSE:RELIANCE") and are
     * resolved to Kite instrument tokens through the instrument master — which must
     * be synced first (see InstrumentSyncService, runs at 08:30 IST pre-market).
     * The ticker auto-reconnects internally; prolonged silence is caught by the
     * MarketDataHub watchdog which handles cross-broker failover.
     *
     * @param symbols the list of canonical trading symbols to subscribe to
     * @return a reactive Flux emitting real exchange Tick objects for the subscribed symbols
     */
    @Override
    public Flux<Tick> subscribeMarketData(List<String> symbols) {
        if (!config.isEnabled()) {
            log.debug("Kite adapter disabled - market data feed not started");
            return Flux.empty();
        }
        return authenticator.getAccessToken()
            .flatMapMany(token -> Mono.fromCallable(() -> resolveTokens(symbols))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // JDBC lookups must not block reactor threads
                .flatMapMany(newTokens -> {
                    if (newTokens.isEmpty() && subscribedTokens.isEmpty()) {
                        log.warn("No Kite instrument tokens resolved for {} symbols - feed NOT started. " +
                            "Ensure the instrument master is synced (runs at 08:30 IST pre-market).", symbols.size());
                        return Flux.empty();
                    }
                    ensureTickerConnected(token, newTokens);
                    return tickSink.asFlux();
                }));
    }

    /**
     * Resolves canonical symbols to Kite instrument tokens via the instrument master.
     */
    private List<Long> resolveTokens(List<String> symbols) {
        List<Long> tokens = new ArrayList<>();
        for (String sym : symbols) {
            try {
                var inst = instrumentMaster.resolveForMarketData(sym).blockOptional();
                if (inst.isPresent() && inst.get().kiteToken() != null && !inst.get().kiteToken().isBlank()) {
                    long token = Long.parseLong(inst.get().kiteToken().trim());
                    registerTokenMapping(token, sym);
                    tokens.add(token);
                } else {
                    log.warn("No Kite token found for {} - skipping (instrument master not synced?)", sym);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve Kite token for {}: {}", sym, e.getMessage());
            }
        }
        return tokens;
    }

    /**
     * Creates the KiteTicker on first use and subscribes any new tokens on subsequent calls.
     * NOTE: KiteTicker constructor takes (accessToken, apiKey) in that order.
     */
    private synchronized void ensureTickerConnected(String accessToken, List<Long> newTokens) {
        KiteTicker existing = tickerRef.get();
        if (existing != null && existing.isConnectionOpen()) {
            ArrayList<Long> toAdd = new ArrayList<>();
            for (Long t : newTokens) {
                if (subscribedTokens.add(t)) toAdd.add(t);
            }
            if (!toAdd.isEmpty()) {
                existing.subscribe(toAdd);
                existing.setMode(toAdd, KiteTicker.modeQuote);
                log.info("Kite WebSocket: subscribed {} additional tokens (total {})", toAdd.size(), subscribedTokens.size());
            }
            return;
        }

        log.info("Connecting Kite WebSocket (KiteTicker) for {} tokens...", newTokens.size());
        KiteTicker ticker = createTicker(accessToken);
        subscribedTokens.addAll(newTokens);
        tickerRef.set(ticker);
        tickerToken.set(accessToken);
        ticker.connect();
    }

    /**
     * Builds a fully configured (but not yet connected) KiteTicker bound to the given
     * access token. The token is baked into the SDK client and reused on every internal
     * auto-reconnect, so callers MUST rebuild the ticker (see {@link #rekeyTickerOnTokenRenewal})
     * whenever a fresh access token is minted, otherwise reconnects fail with 403.
     */
    private KiteTicker createTicker(String accessToken) {
        KiteTicker ticker = new KiteTicker(accessToken, config.getApiKey());
        ticker.setTryReconnection(true);
        try {
            ticker.setMaximumRetries(100);
            ticker.setMaximumRetryInterval(30);
        } catch (KiteException e) {
            log.warn("Could not set ticker reconnection params: {}", e.getMessage());
        }

        ticker.setOnConnectedListener(() -> {
            ArrayList<Long> all = new ArrayList<>(subscribedTokens);
            log.info("Kite WebSocket CONNECTED - subscribing {} tokens in quote mode", all.size());
            if (!all.isEmpty()) {
                ticker.subscribe(all);
                ticker.setMode(all, KiteTicker.modeQuote);
            }
        });
        AtomicLong tickCounter = new AtomicLong();
        ticker.setOnTickerArrivalListener(sdkTicks -> {
            long count = tickCounter.addAndGet(sdkTicks != null ? sdkTicks.size() : 0);
            if (count == 1 || count % 2000 == 0) {
                log.debug("Kite ticker arrivals: {} ticks received so far", count);
            }
            for (com.zerodhatech.models.Tick sdkTick : sdkTicks) {
                Set<String> syms = tokenToSymbols.get(sdkTick.getInstrumentToken());
                if (syms == null) continue;
                for (String sym : syms) {
                    Tick mapped = mapSdkTick(sdkTick, sym);
                    if (mapped != null) {
                        tickSink.tryEmitNext(mapped);
                    }
                }
            }
        });
        ticker.setOnDisconnectedListener(() ->
            log.warn("Kite WebSocket disconnected - SDK auto-reconnect is active"));
        ticker.setOnErrorListener(new OnError() {
            @Override public void onError(Exception exception) {
                String msg = exception != null ? exception.getMessage() : null;
                log.error("Kite WebSocket error: {}", msg);
                if (isAuthFailure(msg)) {
                    log.warn("Kite WebSocket handshake/auth failure detected - triggering token re-auth and ticker re-key");
                    triggerWsReauth();
                }
            }
            @Override public void onError(KiteException kiteException) {
                log.error("Kite WebSocket KiteException: {}", kiteException.getMessage());
            }
            @Override public void onError(String error) {
                log.error("Kite WebSocket error: {}", error);
            }
        });
        return ticker;
    }

    /**
     * Rebuilds the live WebSocket ticker with a freshly minted access token. Invoked by
     * {@link KiteAuthenticator}'s tokenRenewedListener whenever a new token is produced
     * (headless login / manual token / daily re-auth). This is the core fix for the
     * Kite 403 Forbidden issue: the SDK caches the token it was constructed with, so
     * after a REST re-auth the WS must be reconstructed to stop reconnecting on a dead token.
     */
    private void rekeyTickerOnTokenRenewal(String newToken) {
        if (newToken == null) return;
        if (newToken.equals(tickerToken.get())) return; // already on this token, nothing to do
        synchronized (tickerRekeyLock) {
            if (newToken.equals(tickerToken.get())) return;
            KiteTicker old = tickerRef.get();
            if (old == null) {
                // Not connected yet; remember the token so the next connect uses it.
                tickerToken.set(newToken);
                return;
            }
            log.info("Kite access token renewed - rebuilding WebSocket ticker with fresh token");
            try { old.disconnect(); } catch (Exception ignore) {}
            tickerRef.set(null);
            KiteTicker fresh = createTicker(newToken);
            tickerRef.set(fresh);
            tickerToken.set(newToken);
            fresh.connect();
        }
    }

    /**
     * Fallback triggered when the WebSocket itself reports an auth/handshake failure (HTTP 403).
     * Invalidates the cached token, forces a fresh re-auth, and (via the tokenRenewedListener)
     * rebuilds the ticker with the new token. Debounced to avoid a re-key storm while the SDK's
     * auto-reconnect keeps reporting the same failure.
     */
    private void triggerWsReauth() {
        long now = System.currentTimeMillis();
        if (now - lastWsRekeyAttemptMillis < 60_000) {
            return;
        }
        lastWsRekeyAttemptMillis = now;
        log.warn("Kite WebSocket auth failure - invalidating token and refreshing via re-auth");
        authenticator.invalidateToken();
        authenticator.getAccessToken()
            .doOnNext(this::rekeyTickerOnTokenRenewal)
            .onErrorResume(ex -> {
                log.error("Kite WebSocket re-auth failed: {}", ex.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }

    private static boolean isAuthFailure(String msg) {
        if (msg == null) return false;
        return msg.contains("403")
            || msg.contains("Forbidden")
            || msg.contains("Unauthorized")
            || msg.contains("Switching Protocols");
    }

    /**
     * Registers a token → canonical symbol mapping for tick routing.
     * Package-private so unit tests can seed mappings without a WebSocket.
     */
    void registerTokenMapping(long token, String canonicalSymbol) {
        tokenToSymbols.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(canonicalSymbol);
    }

    /**
     * Maps an SDK tick to the internal Tick model, routing to the first registered
     * symbol for the token. For unit testing (single-symbol registrations).
     */
    Tick mapSdkTick(com.zerodhatech.models.Tick t) {
        Set<String> syms = tokenToSymbols.get(t.getInstrumentToken());
        if (syms == null || syms.isEmpty()) return null;
        return mapSdkTick(t, syms.iterator().next());
    }

    /**
     * Maps an SDK tick to the internal Tick model for a specific (abstract) symbol.
     * Allows fan-out when several abstract symbols share one physical contract token
     * (e.g. NFO:NIFTY_FUT and NFO:NIFTY_50 both map to the NIFTY futures token).
     */
    Tick mapSdkTick(com.zerodhatech.models.Tick t, String canonical) {
        String exchange = canonical.contains(":") ? canonical.substring(0, canonical.indexOf(':')) : "NSE";
        Date ts = t.getTickTimestamp() != null ? t.getTickTimestamp() : t.getLastTradedTime();
        return Tick.builder()
            .brokerId(BROKER_ID)
            .symbol(canonical)
            .exchange(exchange)
            .instrumentToken(String.valueOf(t.getInstrumentToken()))
            .ltp(BigDecimal.valueOf(t.getLastTradedPrice()))
            .open(BigDecimal.valueOf(t.getOpenPrice()))
            .high(BigDecimal.valueOf(t.getHighPrice()))
            .low(BigDecimal.valueOf(t.getLowPrice()))
            .close(BigDecimal.valueOf(t.getClosePrice()))
            .volume(t.getVolumeTradedToday())
            .timestamp(ts != null ? ts.toInstant() : Instant.now())
            .build();
    }

    /**
     * Disconnects the Kite WebSocket on application shutdown.
     */
    @PreDestroy
    public void shutdownTicker() {
        KiteTicker ticker = tickerRef.getAndSet(null);
        if (ticker != null) {
            try {
                ticker.disconnect();
                log.info("Kite WebSocket disconnected on shutdown");
            } catch (Exception e) {
                log.warn("Error disconnecting Kite ticker: {}", e.getMessage());
            }
        }
        subscribedTokens.clear();
        tokenToSymbols.clear();
    }

    /**
     * Parses the order book JSON response from Kite API.
     *
     * @param json the JSON response string containing order data
     * @return a list of Order objects parsed from the JSON
     */
    private List<Order> parseOrderBook(String json) {
        List<Order> orders = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    orders.add(Order.builder()
                        .id(node.path("tag").asText(node.path("order_id").asText()))
                        .brokerOrderId(node.path("order_id").asText())
                        .accountId(config.getUserId())
                        .brokerId(BROKER_ID)
                        .symbol(node.path("tradingsymbol").asText())
                        .exchange(node.path("exchange").asText())
                        .instrumentToken(node.path("instrument_token").asText())
                        .transactionType(TransactionType.valueOf(node.path("transaction_type").asText("BUY")))
                        .quantity(node.path("quantity").asInt())
                        .filledQuantity(node.path("filled_quantity").asInt())
                        .price(BigDecimal.valueOf(node.path("price").asDouble()))
                        .triggerPrice(BigDecimal.valueOf(node.path("trigger_price").asDouble()))
                        .averagePrice(BigDecimal.valueOf(node.path("average_price").asDouble()))
                        .orderType(mapKiteOrderType(node.path("order_type").asText()))
                        .productType(mapKiteProductType(node.path("product").asText()))
                        .bookType("MIS".equalsIgnoreCase(node.path("product").asText()) ? BookType.INTRADAY : BookType.POSITIONAL)
                        .status(mapKiteOrderStatus(node.path("status").asText()))
                        .statusMessage(node.path("status_message").asText(null))
                        .tag(node.path("tag").asText(null))
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Kite order book JSON: {}", e.getMessage());
        }
        return orders;
    }

    /**
     * Parses the positions JSON response from Kite API.
     *
     * @param json the JSON response string containing position data
     * @return a list of Position objects parsed from the JSON
     */
    private List<Position> parsePositions(String json) {
        List<Position> positions = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode net = root.path("data").path("net");
            if (net.isArray()) {
                for (JsonNode node : net) {
                    String product = node.path("product").asText("MIS");
                    BookType bookType = "MIS".equalsIgnoreCase(product) ? BookType.INTRADAY : BookType.POSITIONAL;
                    positions.add(Position.builder()
                        .accountId(config.getUserId())
                        .brokerId(BROKER_ID)
                        .symbol(node.path("tradingsymbol").asText())
                        .exchange(node.path("exchange").asText())
                        .instrumentToken(node.path("instrument_token").asText())
                        .productType(mapKiteProductType(product))
                        .bookType(bookType)
                        .netQuantity(node.path("quantity").asInt())
                        .buyQuantity(node.path("buy_quantity").asInt())
                        .sellQuantity(node.path("sell_quantity").asInt())
                        .buyAveragePrice(BigDecimal.valueOf(node.path("buy_price").asDouble()))
                        .sellAveragePrice(BigDecimal.valueOf(node.path("sell_price").asDouble()))
                        .ltp(BigDecimal.valueOf(node.path("last_price").asDouble()))
                        .mtmPnl(BigDecimal.valueOf(node.path("m2m").asDouble()))
                        .realizedPnl(BigDecimal.valueOf(node.path("realised").asDouble()))
                        .unrealizedPnl(BigDecimal.valueOf(node.path("unrealised").asDouble()))
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Kite positions JSON: {}", e.getMessage());
        }
        return positions;
    }

    /**
     * Executes an authenticated Kite API call, transparently re-authenticating and
     * retrying exactly once when Kite rejects the access token (HTTP 401/403).
     * Kite access tokens expire daily, so a mid-session expiry would otherwise fail
     * every subsequent call until a manual restart.
     *
     * @param apiCall the API call to execute with a valid access token
     * @param <T>     the response type
     * @return a reactive Mono containing the API response
     */
    private <T> Mono<T> withTokenRetry(java.util.function.Function<String, Mono<T>> apiCall) {
        return authenticator.getAccessToken()
            .flatMap(apiCall)
            .onErrorResume(KiteBrokerAdapter::isTokenError, ex -> {
                log.warn("Kite rejected access token (401/403) - re-authenticating and retrying once");
                authenticator.invalidateToken();
                return authenticator.getAccessToken().flatMap(apiCall);
            });
    }

    /**
     * Determines whether an error indicates an invalid/expired Kite access token
     * (HTTP 401 Unauthorized or 403 Forbidden, which Kite returns as a TokenException).
     *
     * @param ex the error to inspect
     * @return true if the error is an authentication/token failure
     */
    private static boolean isTokenError(Throwable ex) {
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 401 || status == 403;
        }
        String msg = ex.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("403"));
    }

    /**
     * Maps the internal OrderType enum to Kite's order type string.
     *
     * @param type the internal order type
     * @return the corresponding Kite order type string
     */
    private String mapOrderTypeToKite(OrderType type) {
        return switch (type) {
            case MARKET -> "MARKET";
            case LIMIT -> "LIMIT";
            case SL_M -> "SL-M";
            case SL_L -> "SL";
        };
    }

    /**
     * Maps Kite's order type string to the internal OrderType enum.
     *
     * @param kiteType the Kite order type string
     * @return the corresponding internal OrderType, defaults to LIMIT if unknown
     */
    private OrderType mapKiteOrderType(String kiteType) {
        if ("MARKET".equalsIgnoreCase(kiteType)) return OrderType.MARKET;
        if ("LIMIT".equalsIgnoreCase(kiteType)) return OrderType.LIMIT;
        if ("SL-M".equalsIgnoreCase(kiteType)) return OrderType.SL_M;
        if ("SL".equalsIgnoreCase(kiteType)) return OrderType.SL_L;
        return OrderType.LIMIT;
    }

    /**
     * Maps the internal ProductType enum to Kite's product type string.
     *
     * @param type the internal product type
     * @return the corresponding Kite product type string
     */
    private String mapProductTypeToKite(ProductType type) {
        return switch (type) {
            case MIS -> "MIS";
            case NRML -> "NRML";
            case CNC -> "CNC";
        };
    }

    /**
     * Maps Kite's product type string to the internal ProductType enum.
     *
     * @param kiteProduct the Kite product type string
     * @return the corresponding internal ProductType, defaults to MIS if unknown
     */
    private ProductType mapKiteProductType(String kiteProduct) {
        if ("MIS".equalsIgnoreCase(kiteProduct)) return ProductType.MIS;
        if ("NRML".equalsIgnoreCase(kiteProduct)) return ProductType.NRML;
        if ("CNC".equalsIgnoreCase(kiteProduct)) return ProductType.CNC;
        return ProductType.MIS;
    }

    /**
     * Maps Kite's order status string to the internal OrderStatus enum.
     *
     * @param status the Kite order status string
     * @return the corresponding internal OrderStatus, defaults to PENDING if unknown
     */
    private OrderStatus mapKiteOrderStatus(String status) {
        if (status == null) return OrderStatus.PENDING;
        return switch (status.toUpperCase()) {
            case "COMPLETE" -> OrderStatus.FILLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "TRIGGER PENDING" -> OrderStatus.TRIGGER_PENDING;
            case "OPEN" -> OrderStatus.OPEN;
            default -> OrderStatus.PENDING;
        };
    }
}
