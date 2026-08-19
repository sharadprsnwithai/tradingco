package com.tradingbot.adapter.shoonya;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ShoonyaBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaBrokerAdapter.class);
    private static final String BROKER_ID = "SHOONYA";

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a ShoonyaBrokerAdapter with the specified dependencies.
     *
     * @param config           the Shoonya configuration containing credentials and settings
     * @param authenticator    the authenticator for obtaining access tokens
     * @param webClientBuilder the Spring WebClient builder for HTTP communication
     * @param objectMapper     the Jackson ObjectMapper for JSON serialization/deserialization
     */
    public ShoonyaBrokerAdapter(ShoonyaConfig config, ShoonyaAuthenticator authenticator, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
            .baseUrl("https://api.shoonya.com")
            .build();
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
        return authenticator.getAccessToken()
            .flatMap(token -> {
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
                                return OrderResult.failure(request.tag(), emsg);
                            }
                        } catch (Exception e) {
                            return OrderResult.failure(request.tag(), "Parse error: " + e.getMessage());
                        }
                    })
                    .onErrorResume(ex -> {
                        log.error("Shoonya placeOrder failed: {}", ex.getMessage());
                        return Mono.just(OrderResult.failure(request.tag(), ex.getMessage()));
                    });
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderResult> modifyOrder(String orderId, OrderModifyRequest request) {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("actid", config.getAccountId());
                payload.put("norenordno", request.brokerOrderId() != null ? request.brokerOrderId() : orderId);
                payload.put("qty", String.valueOf(request.quantity()));
                payload.put("prctyp", mapOrderTypeToShoonya(request.orderType()));

                if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("prc", request.price().toPlainString());
                }
                if (request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                    payload.put("trgprc", request.triggerPrice().toPlainString());
                }

                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/ModifyOrder")
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
                                return OrderResult.failure(request.orderId(), root.path("emsg").asText());
                            }
                        } catch (Exception e) {
                            return OrderResult.failure(request.orderId(), "Parse error: " + e.getMessage());
                        }
                    })
                    .onErrorResume(ex -> Mono.just(OrderResult.failure(request.orderId(), ex.getMessage())));
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<Void> cancelOrder(String orderId) {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                Map<String, Object> payload = Map.of("uid", config.getUserId(), "norenordno", orderId);
                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/CancelOrder")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .onErrorResume(ex -> {
                        log.error("Failed to cancel Shoonya order {}: {}", orderId, ex.getMessage());
                        return Mono.empty();
                    });
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<List<Order>> getOrderBook() {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                Map<String, Object> payload = Map.of("uid", config.getUserId());
                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/OrderBook")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::parseOrderBook)
                    .onErrorResume(ex -> {
                        log.error("Failed to fetch Shoonya order book: {}", ex.getMessage());
                        return Mono.just(List.of());
                    });
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<List<Position>> getPositions() {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                Map<String, Object> payload = Map.of("uid", config.getUserId(), "actid", config.getAccountId());
                String formBody = buildFormBody(payload, token);

                log.info("Fetching Shoonya positions for user: {}", config.getUserId());
                return webClient.post()
                    .uri("/NorenWClientAPI/PositionBook")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::parsePositions)
                    .onErrorResume(ex -> {
                        log.error("Failed to fetch Shoonya positions: {}", ex.getMessage());
                        return Mono.just(List.of());
                    });
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<MarginInfo> getMargins() {
        return authenticator.getAccessToken()
            .flatMap(token -> {
                Map<String, Object> payload = Map.of("uid", config.getUserId(), "actid", config.getAccountId());
                String formBody = buildFormBody(payload, token);

                return webClient.post()
                    .uri("/NorenWClientAPI/Limits")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromValue(formBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(json -> {
                        try {
                            JsonNode root = objectMapper.readTree(json);
                            BigDecimal cash = BigDecimal.valueOf(root.path("cash").asDouble(0.0));
                            BigDecimal marginUsed = BigDecimal.valueOf(root.path("marginused").asDouble(0.0));
                            BigDecimal payin = BigDecimal.valueOf(root.path("payin").asDouble(0.0));
                            BigDecimal available = cash.add(payin).subtract(marginUsed);
                            BigDecimal total = cash.add(payin);
                            return MarginInfo.of(config.getAccountId(), BROKER_ID, available, marginUsed, total, cash);
                        } catch (Exception e) {
                            return MarginInfo.of(config.getAccountId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                        }
                    })
                    .onErrorResume(ex -> Mono.just(MarginInfo.of(config.getAccountId(), BROKER_ID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
            });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Flux<Tick> subscribeMarketData(List<String> symbols) {
        return Flux.interval(Duration.ofMillis(500))
            .map(i -> Tick.builder()
                .brokerId(BROKER_ID)
                .symbol(symbols.isEmpty() ? "NIFTY" : symbols.get((int) (i % symbols.size())))
                .exchange("NFO")
                .ltp(BigDecimal.valueOf(24000.0 + (Math.cos(i) * 20.0)))
                .open(BigDecimal.valueOf(23980.0))
                .high(BigDecimal.valueOf(24050.0))
                .low(BigDecimal.valueOf(23950.0))
                .close(BigDecimal.valueOf(23990.0))
                .volume(850L + i * 4)
                .timestamp(Instant.now())
                .build());
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
            return "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + token;
        } catch (Exception e) {
            return "jData={}&jKey=" + token;
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
}
