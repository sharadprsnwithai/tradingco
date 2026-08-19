package com.tradingbot.adapter.kite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.BrokerAdapter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class KiteBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(KiteBrokerAdapter.class);
    private static final String BROKER_ID = "ZERODHA";

    private final KiteConfig config;
    private final KiteAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new KiteBrokerAdapter with the provided dependencies.
     *
     * @param config           the Kite broker configuration
     * @param authenticator    the authenticator for managing Kite sessions
     * @param webClientBuilder the WebClient builder for HTTP communication
     * @param objectMapper     the Jackson ObjectMapper for JSON parsing
     */
    public KiteBrokerAdapter(KiteConfig config, KiteAuthenticator authenticator, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
            .baseUrl(config.getBaseUrl())
            .defaultHeader("X-Kite-Version", "3")
            .build();
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
        return authenticator.getAccessToken()
            .flatMap(token -> {
                var bodyBuilder = BodyInserters.fromFormData("tradingsymbol", request.symbol())
                    .with("exchange", request.exchange() != null ? request.exchange() : "NFO")
                    .with("transaction_type", request.transactionType().name())
                    .with("order_type", mapOrderTypeToKite(request.orderType()))
                    .with("quantity", String.valueOf(request.quantity()))
                    .with("product", mapProductTypeToKite(request.productType()))
                    .with("validity", "DAY");

                if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                    bodyBuilder = bodyBuilder.with("price", request.price().toPlainString());
                }
                if (request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
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
                        log.error("Error placing Kite order for {}: {}", request.symbol(), ex.getMessage());
                        return Mono.just(OrderResult.failure(request.tag(), ex.getMessage()));
                    });
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
        return authenticator.getAccessToken()
            .flatMap(token -> {
                var bodyBuilder = BodyInserters.fromFormData("quantity", String.valueOf(request.quantity()))
                    .with("order_type", mapOrderTypeToKite(request.orderType()))
                    .with("validity", "DAY");

                if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
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
                    .onErrorResume(ex -> Mono.just(OrderResult.failure(request.orderId(), ex.getMessage())));
            });
    }

    /**
     * Cancels an existing order with Kite broker.
     *
     * @param orderId the broker order ID to cancel
     * @return a reactive Mono that completes when cancellation is done
     */
    @Override
    public Mono<Void> cancelOrder(String orderId) {
        return authenticator.getAccessToken()
            .flatMap(token -> webClient.delete()
                .uri("/orders/regular/{order_id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(ex -> {
                    log.error("Failed to cancel Kite order {}: {}", orderId, ex.getMessage());
                    return Mono.empty();
                })
            );
    }

    /**
     * Retrieves the order book from Kite broker.
     *
     * @return a reactive Mono containing the list of orders
     */
    @Override
    public Mono<List<Order>> getOrderBook() {
        return authenticator.getAccessToken()
            .flatMap(token -> webClient.get()
                .uri("/orders")
                .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseOrderBook)
                .onErrorResume(ex -> {
                    log.error("Failed to fetch Kite order book: {}", ex.getMessage());
                    return Mono.just(List.of());
                })
            );
    }

    /**
     * Retrieves current positions from Kite broker.
     *
     * @return a reactive Mono containing the list of positions
     */
    @Override
    public Mono<List<Position>> getPositions() {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                log.info("Fetching Kite positions with access_token: {}...", token.substring(0, Math.min(6, token.length())));
                return webClient.get()
                    .uri("/portfolio/positions")
                    .header(HttpHeaders.AUTHORIZATION, "token " + config.getApiKey() + ":" + token)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        log.debug("Kite positions raw JSON: {}", json);
                        return parsePositions(json);
                    })
                    .onErrorResume(ex -> {
                        log.error("Failed to fetch Kite positions: {}", ex.getMessage(), ex);
                        return Mono.just(List.of());
                    });
            });
    }

    /**
     * Retrieves margin information from Kite broker.
     *
     * @return a reactive Mono containing the margin information
     */
    @Override
    public Mono<MarginInfo> getMargins() {
        return authenticator.getAccessToken()
            .flatMap(token -> webClient.get()
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
                .onErrorResume(ex -> Mono.just(MarginInfo.of(config.getUserId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
            );
    }

    /**
     * Subscribes to market data for the specified symbols.
     * Returns a reactive tick stream with simulated data.
     *
     * @param symbols the list of trading symbols to subscribe to
     * @return a reactive Flux emitting Tick objects for the subscribed symbols
     */
    @Override
    public Flux<Tick> subscribeMarketData(List<String> symbols) {
        // Returns reactive tick stream (bridged to live WebSocket or synthetic ticker)
        return Flux.interval(Duration.ofMillis(500))
            .map(i -> Tick.builder()
                .brokerId(BROKER_ID)
                .symbol(symbols.isEmpty() ? "NIFTY" : symbols.get((int) (i % symbols.size())))
                .exchange("NFO")
                .ltp(BigDecimal.valueOf(24000.0 + (Math.sin(i) * 20.0)))
                .open(BigDecimal.valueOf(23980.0))
                .high(BigDecimal.valueOf(24050.0))
                .low(BigDecimal.valueOf(23950.0))
                .close(BigDecimal.valueOf(23990.0))
                .volume(1000L + i * 5)
                .timestamp(Instant.now())
                .build());
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
