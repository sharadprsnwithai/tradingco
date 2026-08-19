package com.tradingbot.oms;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.database.TradingDbService;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.marketdata.CircularCandleBuffer;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import com.tradingbot.model.Order;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.model.enums.TransactionType;
import com.tradingbot.risk.RiskManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Order Management System (OMS).
 * Converts Signals into Marketable LIMIT orders with dynamic slippage buffer,
 * routes execution to isolated broker adapters, enforces pre-trade RMS,
 * and maintains a 4-second hybrid push-with-polling reconciler.
 */
@Service
public class OrderManagerService {

    private static final Logger log = LoggerFactory.getLogger(OrderManagerService.class);

    private final BrokerAdapterRegistry brokerRegistry;
    private final TradingDbService dbService;
    private final RiskManager riskManager;
    private final InstrumentMasterService instrumentMaster;
    private final MarketDataHub marketDataHub;

    private final double slippageBufferPercent;
    private final int reconcileIntervalSeconds;
    private volatile boolean paperTrading;

    // In-memory hot order book: orderId -> Order
    private final Map<String, Order> orderBook = new ConcurrentHashMap<>();
    private final Sinks.Many<Order> orderSink = Sinks.many().multicast().directBestEffort();

    private final AtomicLong orderSequence = new AtomicLong(1);
    private Disposable reconcilerSubscription;

    /**
     * Constructs the OrderManagerService with all required dependencies.
     *
     * @param brokerRegistry          registry of broker adapters for order routing
     * @param dbService                database service for persisting order state
     * @param riskManager              risk management service for pre-trade validation
     * @param instrumentMaster         instrument master service for lookup and tick sizes
     * @param marketDataHub            market data hub for candle and tick data
     * @param slippageBufferPercent    percentage buffer added to price for marketable limit orders
     * @param reconcileIntervalSeconds interval in seconds for the hybrid push-with-polling reconciler
     * @param paperTrading             whether to simulate order fills without broker submission
     */
    public OrderManagerService(
        BrokerAdapterRegistry brokerRegistry,
        TradingDbService dbService,
        RiskManager riskManager,
        InstrumentMasterService instrumentMaster,
        MarketDataHub marketDataHub,
        @Value("${bot.oms.slippage-buffer-percent:0.5}") double slippageBufferPercent,
        @Value("${bot.oms.reconcile-interval-seconds:4}") int reconcileIntervalSeconds,
        @Value("${bot.paper-trading.enabled:false}") boolean paperTrading
    ) {
        this.brokerRegistry = brokerRegistry;
        this.dbService = dbService;
        this.riskManager = riskManager;
        this.instrumentMaster = instrumentMaster;
        this.marketDataHub = marketDataHub;
        this.slippageBufferPercent = slippageBufferPercent;
        this.reconcileIntervalSeconds = reconcileIntervalSeconds;
        this.paperTrading = paperTrading;
    }

    /**
     * Initializes the OrderManagerService by starting the hybrid reconciler after dependency injection.
     */
    @PostConstruct
    public void init() {
        startHybridReconciler();
    }

    /**
     * Executes a trading Signal through RMS validation, marketable LIMIT price derivation,
     * and broker execution routing.
     */
    public Mono<Order> executeSignal(Signal signal) {
        if (signal == null) return Mono.empty();

        return riskManager.validateSignal(signal)
            .flatMap(riskResult -> {
                if (!riskResult.approved()) {
                    log.warn("Signal REJECTED by RMS: rule={}, reason={}", riskResult.ruleName(), riskResult.reason());
                    Order rejectedOrder = buildRejectedOrder(signal, riskResult.ruleName() + ": " + riskResult.reason());
                    orderBook.put(rejectedOrder.id(), rejectedOrder);
                    orderSink.tryEmitNext(rejectedOrder);
                    return dbService.saveOrder(rejectedOrder).thenReturn(rejectedOrder);
                }

                return prepareAndPlaceOrder(signal);
            });
    }

    /**
     * Prepares an order from a validated signal by deriving the marketable limit price,
     * constructing the order and request, then routing it to the appropriate broker adapter.
     *
     * @param signal the validated trading signal to convert into an order
     * @return a {@link Mono} emitting the resulting {@link Order} after broker placement
     */
    private Mono<Order> prepareAndPlaceOrder(Signal signal) {
        String orderId = generateOrderId();
        TransactionType txnType = mapSignalToTransactionType(signal.signalType());

        return instrumentMaster.findByCanonicalSymbol(signal.symbol())
            .defaultIfEmpty(Instrument.builder().canonicalSymbol(signal.symbol()).tickSize(new BigDecimal("0.05")).build())
            .flatMap(instrument -> {
                BigDecimal tickSize = instrument.tickSize() != null ? instrument.tickSize() : new BigDecimal("0.05");
                BigDecimal executionLimitPrice = calculateMarketableLimitPrice(signal, txnType, tickSize);

                Order initialOrder = Order.builder()
                    .id(orderId)
                    .accountId(signal.targetAccountId())
                    .strategyId(signal.strategyId())
                    .symbol(signal.symbol())
                    .exchange(signal.exchange() != null ? signal.exchange() : "NSE")
                    .instrumentToken(instrument.kiteToken() != null ? instrument.kiteToken() : instrument.shoonyaToken())
                    .transactionType(txnType)
                    .quantity(signal.quantity())
                    .filledQuantity(0)
                    .price(executionLimitPrice)
                    .triggerPrice(signal.triggerPrice())
                    .averagePrice(BigDecimal.ZERO)
                    .orderType(signal.orderType() != null ? signal.orderType() : OrderType.LIMIT)
                    .productType(signal.productType() != null ? signal.productType() : ProductType.MIS)
                    .bookType(signal.bookType() != null ? signal.bookType() : BookType.INTRADAY)
                    .status(OrderStatus.PENDING)
                    .tag(signal.tag())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

                orderBook.put(orderId, initialOrder);
                orderSink.tryEmitNext(initialOrder);

                OrderRequest req = OrderRequest.builder()
                    .accountId(signal.targetAccountId())
                    .symbol(signal.symbol())
                    .exchange(initialOrder.exchange())
                    .instrumentToken(initialOrder.instrumentToken())
                    .transactionType(txnType)
                    .quantity(signal.quantity())
                    .price(executionLimitPrice)
                    .triggerPrice(signal.triggerPrice())
                    .orderType(initialOrder.orderType())
                    .productType(initialOrder.productType())
                    .tag(signal.tag())
                    .strategyId(signal.strategyId())
                    .build();

                if (paperTrading) {
                    log.info("PAPER TRADING EXECUTION [{}]: Simulating fill for {} x {} @ {}",
                        orderId, signal.symbol(), signal.quantity(), executionLimitPrice);
                    OrderResult paperResult = OrderResult.success("PAPER_" + UUID.randomUUID().toString().substring(0, 8), signal.tag(), OrderStatus.FILLED);
                    return handleOrderResult(orderId, "PAPER_BROKER", paperResult);
                }

                return resolveBrokerAdapter(signal.targetAccountId())
                    .flatMap(adapter -> {
                        Order orderWithBroker = Order.builder()
                            .id(initialOrder.id())
                            .brokerId(adapter.getBrokerId())
                            .accountId(initialOrder.accountId())
                            .strategyId(initialOrder.strategyId())
                            .symbol(initialOrder.symbol())
                            .exchange(initialOrder.exchange())
                            .instrumentToken(initialOrder.instrumentToken())
                            .transactionType(initialOrder.transactionType())
                            .quantity(initialOrder.quantity())
                            .filledQuantity(initialOrder.filledQuantity())
                            .price(initialOrder.price())
                            .triggerPrice(initialOrder.triggerPrice())
                            .averagePrice(initialOrder.averagePrice())
                            .orderType(initialOrder.orderType())
                            .productType(initialOrder.productType())
                            .bookType(initialOrder.bookType())
                            .status(initialOrder.status())
                            .tag(initialOrder.tag())
                            .createdAt(initialOrder.createdAt())
                            .updatedAt(initialOrder.updatedAt())
                            .build();

                        orderBook.put(orderId, orderWithBroker);

                        return adapter.placeOrder(req)
                            .flatMap(result -> handleOrderResult(orderId, adapter.getBrokerId(), result))
                            .onErrorResume(ex -> {
                                log.error("Order placement exception for {}: {}", orderId, ex.getMessage(), ex);
                                Order failed = updateOrderStatus(orderId, OrderStatus.REJECTED, "Broker error: " + ex.getMessage(), null, BigDecimal.ZERO, 0);
                                return Mono.just(failed);
                            });
                    });
            });
    }

    /**
     * Processes the broker's order placement result, updating the local order book
     * and persisting the order to the database.
     *
     * @param orderId   the local order identifier
     * @param brokerId  the broker identifier that executed the order
     * @param result    the result returned by the broker adapter
     * @return a {@link Mono} emitting the updated {@link Order} with the new status
     */
    private Mono<Order> handleOrderResult(String orderId, String brokerId, OrderResult result) {
        Order current = orderBook.get(orderId);
        if (current == null) return Mono.empty();

        if (result.success()) {
            OrderStatus status = result.status() != null ? result.status() : OrderStatus.OPEN;
            Order updated = Order.builder()
                .id(current.id())
                .brokerOrderId(result.brokerOrderId())
                .accountId(current.accountId())
                .brokerId(brokerId)
                .strategyId(current.strategyId())
                .symbol(current.symbol())
                .exchange(current.exchange())
                .instrumentToken(current.instrumentToken())
                .transactionType(current.transactionType())
                .quantity(current.quantity())
                .filledQuantity(status == OrderStatus.FILLED ? current.quantity() : 0)
                .price(current.price())
                .triggerPrice(current.triggerPrice())
                .averagePrice(current.price())
                .orderType(current.orderType())
                .productType(current.productType())
                .bookType(current.bookType())
                .status(status)
                .statusMessage(result.message())
                .tag(current.tag())
                .createdAt(current.createdAt())
                .updatedAt(Instant.now())
                .build();

            orderBook.put(orderId, updated);
            orderSink.tryEmitNext(updated);
            log.info("ORDER PLACED [{}] brokerId={} status={}", orderId, result.brokerOrderId(), status);
            return dbService.saveOrder(updated).thenReturn(updated);
        } else {
            Order rejected = updateOrderStatus(orderId, OrderStatus.REJECTED, result.message(), null, BigDecimal.ZERO, 0);
            return Mono.just(rejected);
        }
    }

    /**
     * Cancel an open order on the broker.
     */
    public Mono<Void> cancelOrder(String orderId) {
        Order order = orderBook.get(orderId);
        if (order == null || order.status() == OrderStatus.FILLED || order.status() == OrderStatus.CANCELLED) {
            return Mono.empty();
        }

        return resolveBrokerAdapter(order.accountId())
            .flatMap(adapter -> adapter.cancelOrder(order.brokerOrderId() != null ? order.brokerOrderId() : order.id()))
            .doOnSuccess(v -> updateOrderStatus(orderId, OrderStatus.CANCELLED, "Cancelled by user/system", null, BigDecimal.ZERO, 0))
            .then();
    }

    /**
     * Cancel all open orders for a specific account or globally across all accounts.
     */
    public Mono<Void> cancelAllOpenOrders(String accountId) {
        return Flux.fromIterable(new ArrayList<>(orderBook.values()))
            .filter(o -> (accountId == null || accountId.equalsIgnoreCase(o.accountId()))
                && (o.status() == OrderStatus.OPEN || o.status() == OrderStatus.PENDING || o.status() == OrderStatus.TRIGGER_PENDING))
            .flatMap(o -> cancelOrder(o.id()))
            .then();
    }

    /**
     * Periodic 4-second hybrid reconciler checking broker order books against local state.
     */
    private void startHybridReconciler() {
        this.reconcilerSubscription = Flux.interval(Duration.ofSeconds(reconcileIntervalSeconds))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(i -> reconcileAllBrokers())
            .subscribe(
                v -> {},
                err -> log.error("Error in hybrid OMS reconciler: {}", err.getMessage())
            );
    }

    /**
     * Reconciles the local order book with broker order books across all registered brokers,
     * synchronizing status discrepancies.
     *
     * @return a {@link Mono} completing when all broker reconciliations are attempted
     */
    public Mono<Void> reconcileAllBrokers() {
        return brokerRegistry.getAll()
            .flatMap(adapter -> adapter.getOrderBook()
                .flatMapMany(Flux::fromIterable)
                .doOnNext(this::reconcileSingleOrder)
                .onErrorResume(e -> {
                    log.warn("Reconciler failed for broker {}: {}", adapter.getBrokerId(), e.getMessage());
                    return Flux.empty();
                })
            )
            .then();
    }

    /**
     * Reconciles a single broker order with the local order book by matching
     * on broker order ID or local order ID and syncing status changes.
     *
     * @param brokerOrder the order fetched from the broker's order book
     */
    private void reconcileSingleOrder(Order brokerOrder) {
        if (brokerOrder == null) return;
        // Match by brokerOrderId or local tag
        Order localOrder = null;
        if (brokerOrder.brokerOrderId() != null) {
            for (Order o : orderBook.values()) {
                if (brokerOrder.brokerOrderId().equals(o.brokerOrderId()) || brokerOrder.brokerOrderId().equals(o.id())) {
                    localOrder = o;
                    break;
                }
            }
        }

        if (localOrder != null && localOrder.status() != brokerOrder.status()) {
            log.info("RECONCILER STATE SYNC: Order {} status changed from {} -> {}",
                localOrder.id(), localOrder.status(), brokerOrder.status());
            Order updated = Order.builder()
                .id(localOrder.id())
                .brokerOrderId(brokerOrder.brokerOrderId())
                .accountId(localOrder.accountId())
                .brokerId(localOrder.brokerId())
                .strategyId(localOrder.strategyId())
                .symbol(localOrder.symbol())
                .exchange(localOrder.exchange())
                .instrumentToken(localOrder.instrumentToken())
                .transactionType(localOrder.transactionType())
                .quantity(localOrder.quantity())
                .filledQuantity(brokerOrder.filledQuantity())
                .price(localOrder.price())
                .triggerPrice(localOrder.triggerPrice())
                .averagePrice(brokerOrder.averagePrice())
                .orderType(localOrder.orderType())
                .productType(localOrder.productType())
                .bookType(localOrder.bookType())
                .status(brokerOrder.status())
                .statusMessage(brokerOrder.statusMessage())
                .tag(localOrder.tag())
                .createdAt(localOrder.createdAt())
                .updatedAt(Instant.now())
                .build();

            orderBook.put(localOrder.id(), updated);
            orderSink.tryEmitNext(updated);
            dbService.saveOrder(updated).subscribe();
        }
    }

    /**
     * Computes Marketable LIMIT price with dynamic 0.5% buffer rounded to tick size.
     */
    public BigDecimal calculateMarketableLimitPrice(Signal signal, TransactionType txnType, BigDecimal tickSize) {
        BigDecimal basePrice = signal.price();
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
            Optional<Candle> last = marketDataHub.getCandleAggregator().getBuffer(signal.symbol(), "1")
                .flatMap(CircularCandleBuffer::getLast);
            basePrice = last.map(Candle::close).orElse(BigDecimal.valueOf(100.0));
        }

        double multiplier = (txnType == TransactionType.BUY)
            ? (1.0 + (slippageBufferPercent / 100.0))
            : (1.0 - (slippageBufferPercent / 100.0));

        BigDecimal rawPrice = basePrice.multiply(BigDecimal.valueOf(multiplier));
        return roundToTick(rawPrice, tickSize);
    }

    public static BigDecimal roundToTick(BigDecimal price, BigDecimal tickSize) {
        if (price == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return price != null ? price.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.HALF_UP);
        return ticks.multiply(tickSize).setScale(2, RoundingMode.HALF_UP);
    }

    private Mono<BrokerAdapter> resolveBrokerAdapter(String targetAccountId) {
        if (targetAccountId == null || targetAccountId.isBlank()) {
            return brokerRegistry.getByBrokerId("ZERODHA")
                .switchIfEmpty(brokerRegistry.getAll().next());
        }
        return brokerRegistry.getByAccountId(targetAccountId)
            .switchIfEmpty(brokerRegistry.getByBrokerId(targetAccountId))
            .switchIfEmpty(brokerRegistry.getByBrokerId("ZERODHA"));
    }

    /**
     * Maps a {@link SignalType} to its corresponding {@link TransactionType}.
     *
     * @param signalType the signal type to map
     * @return the corresponding {@link TransactionType} (BUY or SELL)
     */
    private TransactionType mapSignalToTransactionType(SignalType signalType) {
        return switch (signalType) {
            case ENTRY_LONG, EXIT_SHORT, EXIT_PARTIAL_SHORT -> TransactionType.BUY;
            case ENTRY_SHORT, EXIT_LONG, EXIT_PARTIAL_LONG -> TransactionType.SELL;
            case CANCEL -> TransactionType.BUY;
        };
    }

    /**
     * Builds a rejected {@link Order} record for signals that fail risk validation.
     *
     * @param signal the signal that was rejected
     * @param reason the rejection reason from the risk manager
     * @return a new {@link Order} with status {@link OrderStatus#REJECTED}
     */
    private Order buildRejectedOrder(Signal signal, String reason) {
        String id = generateOrderId();
        return Order.builder()
            .id(id)
            .accountId(signal.targetAccountId())
            .strategyId(signal.strategyId())
            .symbol(signal.symbol())
            .transactionType(mapSignalToTransactionType(signal.signalType()))
            .quantity(signal.quantity())
            .price(signal.price())
            .orderType(signal.orderType())
            .productType(signal.productType())
            .bookType(signal.bookType())
            .status(OrderStatus.REJECTED)
            .statusMessage(reason)
            .tag(signal.tag())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }

    /**
     * Updates the status and metadata of an existing order in the local order book,
     * persists the change, and emits the update to subscribers.
     *
     * @param orderId       the local order identifier
     * @param status        the new {@link OrderStatus} to set
     * @param message       status message describing the update
     * @param brokerOrderId the broker-assigned order ID, or {@code null} to retain existing
     * @param avgPrice      the average execution price, or {@code BigDecimal.ZERO} to retain existing
     * @param filledQty     the filled quantity, or {@code 0} to retain existing
     * @return the updated {@link Order}, or {@code null} if the order was not found
     */
    private Order updateOrderStatus(String orderId, OrderStatus status, String message, String brokerOrderId, BigDecimal avgPrice, int filledQty) {
        Order current = orderBook.get(orderId);
        if (current == null) return null;

        Order updated = Order.builder()
            .id(current.id())
            .brokerOrderId(brokerOrderId != null ? brokerOrderId : current.brokerOrderId())
            .accountId(current.accountId())
            .brokerId(current.brokerId())
            .strategyId(current.strategyId())
            .symbol(current.symbol())
            .exchange(current.exchange())
            .instrumentToken(current.instrumentToken())
            .transactionType(current.transactionType())
            .quantity(current.quantity())
            .filledQuantity(filledQty > 0 ? filledQty : current.filledQuantity())
            .price(current.price())
            .triggerPrice(current.triggerPrice())
            .averagePrice(avgPrice.compareTo(BigDecimal.ZERO) > 0 ? avgPrice : current.averagePrice())
            .orderType(current.orderType())
            .productType(current.productType())
            .bookType(current.bookType())
            .status(status)
            .statusMessage(message)
            .tag(current.tag())
            .createdAt(current.createdAt())
            .updatedAt(Instant.now())
            .build();

        orderBook.put(orderId, updated);
        orderSink.tryEmitNext(updated);
        dbService.saveOrder(updated).subscribe();
        return updated;
    }

    /**
     * Generates a unique order identifier using the current timestamp and an atomic sequence counter.
     *
     * @return a unique order ID string in the format {@code ORD_<timestamp>_<sequence>}
     */
    private String generateOrderId() {
        return "ORD_" + System.currentTimeMillis() + "_" + orderSequence.getAndIncrement();
    }

    /**
     * Checks whether paper trading mode is currently enabled.
     *
     * @return {@code true} if paper trading is enabled, {@code false} otherwise
     */
    public boolean isPaperTrading() {
        return paperTrading;
    }

    /**
     * Enables or disables paper trading mode at runtime.
     *
     * @param paperTrading {@code true} to enable paper trading, {@code false} to disable it
     */
    public void setPaperTrading(boolean paperTrading) {
        this.paperTrading = paperTrading;
        log.info("Paper trading mode set to: {}", paperTrading);
    }

    /**
     * Returns a reactive stream of all order state changes. Subscribers receive
     * updates whenever an order is created, placed, filled, cancelled, or rejected.
     *
     * @return a {@link Flux} of {@link Order} representing real-time order updates
     */
    public Flux<Order> getOrderStream() {
        return orderSink.asFlux();
    }

    /**
     * Returns all orders that are currently open, pending, or trigger-pending.
     *
     * @return an unmodifiable list of active {@link Order} instances
     */
    public List<Order> getOpenOrders() {
        return orderBook.values().stream()
            .filter(o -> o.status() == OrderStatus.OPEN || o.status() == OrderStatus.PENDING || o.status() == OrderStatus.TRIGGER_PENDING)
            .toList();
    }

    /**
     * Retrieves an order by its local order identifier.
     *
     * @param orderId the local order ID to look up
     * @return an {@link Optional} containing the {@link Order} if found, or empty otherwise
     */
    public Optional<Order> getOrder(String orderId) {
        return Optional.ofNullable(orderBook.get(orderId));
    }

    /**
     * Disposes the hybrid reconciler subscription on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        if (reconcilerSubscription != null && !reconcilerSubscription.isDisposed()) {
            reconcilerSubscription.dispose();
        }
    }
}
