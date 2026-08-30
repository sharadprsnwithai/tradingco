package com.tradingbot.position;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.model.enums.TransactionType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.StrategyEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Position Manager enforcing strict separation between Intraday (MIS) and Positional (NRML/CNC) Books.
 * Tracks real-time MTM P&L, persists to SQLite, and executes automated 15:18 EOD intraday square-offs.
 */
@Service
public class PositionManagerService {

    private static final Logger log = LoggerFactory.getLogger(PositionManagerService.class);

    private final BrokerAdapterRegistry brokerRegistry;
    private final OrderManagerService oms;
    private final TradingDbService dbService;
    private final MarketDataHub marketDataHub;
    private final StrategyEngine strategyEngine;

    // Hard-partitioned books: account_symbol_product -> Position
    private final Map<String, Position> intradayBook = new ConcurrentHashMap<>();
    private final Map<String, Position> positionalBook = new ConcurrentHashMap<>();

    private final List<Disposable> subscriptions = new ArrayList<>();

    /**
     * Constructs the PositionManagerService with all required dependencies.
     *
     * @param brokerRegistry  registry of broker adapters for position fetching
     * @param oms             order manager service for order stream subscription
     * @param dbService       database service for persisting position state
     * @param marketDataHub   market data hub for tick stream subscription
     * @param strategyEngine  strategy engine for dispatching scheduled events
     */
    public PositionManagerService(
        BrokerAdapterRegistry brokerRegistry,
        OrderManagerService oms,
        TradingDbService dbService,
        MarketDataHub marketDataHub,
        StrategyEngine strategyEngine
    ) {
        this.brokerRegistry = brokerRegistry;
        this.oms = oms;
        this.dbService = dbService;
        this.marketDataHub = marketDataHub;
        this.strategyEngine = strategyEngine;
    }

    /**
     * Initializes the PositionManagerService by subscribing to order fill events
     * and market tick streams. Rehydration of positions from broker APIs is NOT done
     * here on purpose: at {@code @PostConstruct} time the broker sessions may not yet
     * be authenticated (they are established by the startup ApplicationRunner), so an
     * eager rehydrate would race and silently yield zero positions. Instead, rehydration
     * is triggered explicitly by the startup sequence once both brokers are authed.
     */
    @PostConstruct
    public void init() {
        // 1. Subscribe to order stream to update positions on filled orders
        Disposable orderSub = oms.getOrderStream()
            .filter(o -> o.status() == OrderStatus.FILLED)
            .publishOn(Schedulers.boundedElastic())
            .subscribe(this::onOrderFilled, err -> log.error("Error processing order fill in PositionManager: {}", err.getMessage()));
        subscriptions.add(orderSub);

        // 2. Subscribe to tick stream to update live MTM P&L
        Disposable tickSub = marketDataHub.getTickStream()
            .publishOn(Schedulers.boundedElastic())
            .subscribe(this::onTick, err -> log.error("Error processing tick in PositionManager: {}", err.getMessage()));
        subscriptions.add(tickSub);
    }

    /**
     * Updates in-memory position state on order fill.
     */
    public synchronized void onOrderFilled(Order order) {
        if (order == null || order.symbol() == null) return;

        Map<String, Position> targetBook = (order.bookType() == BookType.POSITIONAL || order.productType() == ProductType.CNC || order.productType() == ProductType.NRML)
            ? positionalBook
            : intradayBook;

        String key = generateKey(order.accountId(), order.symbol(), order.productType());
        Position current = targetBook.get(key);

        int fillQty = order.filledQuantity() > 0 ? order.filledQuantity() : order.quantity();
        BigDecimal price = order.averagePrice().compareTo(BigDecimal.ZERO) > 0 ? order.averagePrice() : order.price();

        if (current == null) {
            int netQty = (order.transactionType() == TransactionType.BUY) ? fillQty : -fillQty;
            int buyQty = (order.transactionType() == TransactionType.BUY) ? fillQty : 0;
            int sellQty = (order.transactionType() == TransactionType.SELL) ? fillQty : 0;
            BigDecimal buyAvg = (order.transactionType() == TransactionType.BUY) ? price : BigDecimal.ZERO;
            BigDecimal sellAvg = (order.transactionType() == TransactionType.SELL) ? price : BigDecimal.ZERO;

            Position newPos = Position.builder()
                .accountId(order.accountId())
                .brokerId(order.brokerId())
                .strategyId(order.strategyId())
                .symbol(order.symbol())
                .exchange(order.exchange())
                .instrumentToken(order.instrumentToken())
                .productType(order.productType())
                .bookType(order.bookType())
                .netQuantity(netQty)
                .buyQuantity(buyQty)
                .sellQuantity(sellQty)
                .buyAveragePrice(buyAvg)
                .sellAveragePrice(sellAvg)
                .ltp(price)
                .mtmPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .unrealizedPnl(BigDecimal.ZERO)
                .updatedAt(Instant.now())
                .build();

            targetBook.put(key, newPos);
            dbService.savePosition(newPos).subscribe(null, err -> log.error("Failed to save new position: {}", err.getMessage()));
            log.info("Opened new {} position: {} x {} @ {}", order.bookType(), order.symbol(), netQty, price);
        } else {
            // Update existing position
            int prevNet = current.netQuantity();
            int newNet = (order.transactionType() == TransactionType.BUY) ? prevNet + fillQty : prevNet - fillQty;
            int buyQty = current.buyQuantity() + (order.transactionType() == TransactionType.BUY ? fillQty : 0);
            int sellQty = current.sellQuantity() + (order.transactionType() == TransactionType.SELL ? fillQty : 0);

            BigDecimal realized = current.realizedPnl();
            // If reducing or flipping position, calculate realized P&L
            if ((prevNet > 0 && order.transactionType() == TransactionType.SELL) || (prevNet < 0 && order.transactionType() == TransactionType.BUY)) {
                int closedQty = Math.min(Math.abs(prevNet), fillQty);
                BigDecimal pnlPerUnit = (prevNet > 0)
                    ? price.subtract(current.buyAveragePrice())
                    : current.sellAveragePrice().subtract(price);
                realized = realized.add(pnlPerUnit.multiply(BigDecimal.valueOf(closedQty)));
            }

            BigDecimal buyAvg = current.buyAveragePrice();
            if (order.transactionType() == TransactionType.BUY) {
                if (prevNet >= 0) {
                    BigDecimal totalBuyCost = (current.buyAveragePrice().multiply(BigDecimal.valueOf(current.buyQuantity())))
                        .add(price.multiply(BigDecimal.valueOf(fillQty)));
                    buyAvg = buyQty > 0 ? totalBuyCost.divide(BigDecimal.valueOf(buyQty), 2, RoundingMode.HALF_UP) : price;
                } else if (newNet > 0) {
                    // Position flipped from SHORT to LONG: cost basis of remaining long quantity is the execution price
                    buyAvg = price;
                }
            }

            BigDecimal sellAvg = current.sellAveragePrice();
            if (order.transactionType() == TransactionType.SELL) {
                if (prevNet <= 0) {
                    BigDecimal totalSellCost = (current.sellAveragePrice().multiply(BigDecimal.valueOf(current.sellQuantity())))
                        .add(price.multiply(BigDecimal.valueOf(fillQty)));
                    sellAvg = sellQty > 0 ? totalSellCost.divide(BigDecimal.valueOf(sellQty), 2, RoundingMode.HALF_UP) : price;
                } else if (newNet < 0) {
                    // Position flipped from LONG to SHORT: cost basis of remaining short quantity is the execution price
                    sellAvg = price;
                }
            }

            String stratId = order.strategyId() != null ? order.strategyId() : current.strategyId();
            Position updated = Position.builder()
                .accountId(current.accountId())
                .brokerId(current.brokerId())
                .strategyId(stratId)
                .symbol(current.symbol())
                .exchange(current.exchange())
                .instrumentToken(current.instrumentToken())
                .productType(current.productType())
                .bookType(current.bookType())
                .netQuantity(newNet)
                .buyQuantity(buyQty)
                .sellQuantity(sellQty)
                .buyAveragePrice(buyAvg)
                .sellAveragePrice(sellAvg)
                .ltp(price)
                .mtmPnl(realized)
                .realizedPnl(realized)
                .unrealizedPnl(BigDecimal.ZERO)
                .updatedAt(Instant.now())
                .build();

            targetBook.put(key, updated);
            dbService.savePosition(updated).subscribe(null, err -> log.error("Failed to save updated position: {}", err.getMessage()));
            log.info("Updated {} position: {} netQty={} (realized P&L: ₹{})",
                current.bookType(), current.symbol(), newNet, realized);
        }
    }

    /**
     * Updates live MTM and unrealized P&L on incoming market ticks.
     */
    public void onTick(Tick tick) {
        if (tick == null || tick.symbol() == null || tick.ltp() == null) return;

        updateBookMtm(intradayBook, tick);
        updateBookMtm(positionalBook, tick);
    }

    /**
     * Updates the MTM P&amp;L and unrealized P&amp;L for all positions in a book
     * based on the latest market tick.
     *
     * @param book the position book (intraday or positional) to update
     * @param tick the latest market tick containing the last traded price
     */
    private void updateBookMtm(Map<String, Position> book, Tick tick) {
        for (Map.Entry<String, Position> entry : book.entrySet()) {
            Position pos = entry.getValue();
            if (pos.symbol().equalsIgnoreCase(tick.symbol()) && pos.netQuantity() != 0) {
                BigDecimal ltp = tick.ltp();
                BigDecimal unrealized;
                if (pos.netQuantity() > 0) {
                    unrealized = ltp.subtract(pos.buyAveragePrice()).multiply(BigDecimal.valueOf(pos.netQuantity()));
                } else {
                    unrealized = pos.sellAveragePrice().subtract(ltp).multiply(BigDecimal.valueOf(Math.abs(pos.netQuantity())));
                }

                BigDecimal totalMtm = pos.realizedPnl().add(unrealized);

                Position updated = Position.builder()
                    .accountId(pos.accountId())
                    .brokerId(pos.brokerId())
                    .symbol(pos.symbol())
                    .exchange(pos.exchange())
                    .instrumentToken(pos.instrumentToken())
                    .productType(pos.productType())
                    .bookType(pos.bookType())
                    .netQuantity(pos.netQuantity())
                    .buyQuantity(pos.buyQuantity())
                    .sellQuantity(pos.sellQuantity())
                    .buyAveragePrice(pos.buyAveragePrice())
                    .sellAveragePrice(pos.sellAveragePrice())
                    .ltp(ltp)
                    .mtmPnl(totalMtm)
                    .realizedPnl(pos.realizedPnl())
                    .unrealizedPnl(unrealized)
                    .updatedAt(Instant.now())
                    .build();

                book.put(entry.getKey(), updated);
            }
        }
    }

    /**
     * EOD Automated Square-off (15:18 IST).
     * Scans IntradayBook and generates market-exit orders for all open positions
     * where autoSquareOff is true. Positions with autoSquareOff=false are skipped.
     */
    public Mono<Void> executeEodIntradaySquareOff() {
        log.warn("15:14 EOD INTRADAY AUTO SQUARE-OFF TRIGGERED. Notifying strategies and liquidating open IntradayBook positions.");
        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_SQUARE_OFF));

        List<Position> openIntraday = getOpenIntradayPositions().stream()
            .filter(Position::autoSquareOff)
            .filter(pos -> pos.netQuantity() != 0)
            .toList();
        if (openIntraday.isEmpty()) {
            log.info("No unmanaged open intraday positions remaining for EOD square-off");
            return Mono.empty();
        }

        return Flux.fromIterable(openIntraday)
            .flatMap(pos -> {
                SignalType sigType = pos.netQuantity() > 0 ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
                int qty = Math.abs(pos.netQuantity());

                Signal exitSignal = Signal.builder()
                    .strategyId("EOD_SQUARE_OFF")
                    .targetAccountId(pos.accountId())
                    .symbol(pos.symbol())
                    .exchange(pos.exchange())
                    .signalType(sigType)
                    .quantity(qty)
                    .price(pos.ltp())
                    .orderType(OrderType.MARKET)
                    .productType(pos.productType())
                    .bookType(BookType.INTRADAY)
                    .tag("EOD_15:14_AUTO_SQUARE_OFF")
                    .build();

                log.info("Submitting safety EOD square-off order for {} x {} @ {}", pos.symbol(), qty, pos.ltp());
                return oms.executeSignal(exitSignal);
            })
            .then();
    }

    /**
     * Rehydrates positions directly from broker APIs (Source of Truth).
     */
    public Mono<Void> rehydratePositionsFromBrokers() {
        return brokerRegistry.getAll()
            .filter(BrokerAdapter::isEnabled)
            .flatMap(adapter -> adapter.getPositions()
                .flatMapMany(Flux::fromIterable)
                .doOnNext(pos -> {
                    String key = generateKey(pos.accountId(), pos.symbol(), pos.productType());
                    if (pos.bookType() == BookType.POSITIONAL || pos.productType() == ProductType.CNC || pos.productType() == ProductType.NRML) {
                        positionalBook.put(key, pos);
                    } else {
                        intradayBook.put(key, pos);
                    }
                    dbService.savePosition(pos).subscribe();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to rehydrate positions from broker {}: {}", adapter.getBrokerId(), e.getMessage());
                    return Flux.empty();
                })
            )
            .then()
            .doOnSuccess(v -> log.info("Rehydrated {} intraday and {} positional positions from brokers",
                intradayBook.size(), positionalBook.size()));
    }

    /**
     * Returns all open intraday positions with non-zero net quantity.
     *
     * @return a list of open {@link Position} instances in the intraday book
     */
    public List<Position> getOpenIntradayPositions() {
        return intradayBook.values().stream()
            .filter(p -> p.netQuantity() != 0)
            .toList();
    }

    /**
     * Returns all open positional positions with non-zero net quantity.
     *
     * @return a list of open {@link Position} instances in the positional book
     */
    public List<Position> getOpenPositionalPositions() {
        return positionalBook.values().stream()
            .filter(p -> p.netQuantity() != 0)
            .toList();
    }

    /**
     * Returns all positions from both intraday and positional books as a combined list.
     *
     * @return an unmodifiable list of all {@link Position} instances across both books
     */
    public List<Position> getAllPositions() {
        List<Position> all = new ArrayList<>(intradayBook.values());
        all.addAll(positionalBook.values());
        return Collections.unmodifiableList(all);
    }

    /**
     * Retrieves all open positions across both intraday and positional books.
     */
    public List<Position> getAllOpenPositions() {
        List<Position> list = new ArrayList<>();
        intradayBook.values().stream().filter(p -> p.netQuantity() != 0).forEach(list::add);
        positionalBook.values().stream().filter(p -> p.netQuantity() != 0).forEach(list::add);
        return Collections.unmodifiableList(list);
    }

    /**
     * Retrieves all open positions belonging to a specific strategy.
     */
    public List<Position> getOpenPositionsByStrategy(String strategyId) {
        if (strategyId == null) return List.of();
        return getAllOpenPositions().stream()
            .filter(p -> strategyId.equalsIgnoreCase(p.strategyId()))
            .toList();
    }

    /**
     * Calculates the sum of all negative unrealized MTM P&L for a strategy.
     */
    public BigDecimal getTotalUnrealizedLossForStrategy(String strategyId) {
        if (strategyId == null) return BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (Position p : getAllOpenPositions()) {
            if (strategyId.equalsIgnoreCase(p.strategyId())) {
                BigDecimal unPnl = p.unrealizedPnl();
                if (unPnl != null && unPnl.compareTo(BigDecimal.ZERO) < 0) {
                    totalLoss = totalLoss.add(unPnl.abs());
                }
            }
        }
        return totalLoss;
    }

    /**
     * Calculates the global sum of all negative unrealized MTM P&L across all open positions.
     */
    public BigDecimal getTotalUnrealizedLossGlobal() {
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (Position p : getAllOpenPositions()) {
            BigDecimal unPnl = p.unrealizedPnl();
            if (unPnl != null && unPnl.compareTo(BigDecimal.ZERO) < 0) {
                totalLoss = totalLoss.add(unPnl.abs());
            }
        }
        return totalLoss;
    }

    /**
     * Retrieves a specific position by account, symbol, and product type.
     * Searches both intraday and positional books.
     *
     * @param accountId   the account identifier
     * @param symbol      the trading symbol
     * @param productType the product type (MIS, NRML, CNC)
     * @return an {@link Optional} containing the {@link Position} if found, or empty otherwise
     */
    public Optional<Position> getPosition(String accountId, String symbol, ProductType productType) {
        String key = generateKey(accountId, symbol, productType);
        Position p = intradayBook.get(key);
        if (p == null) {
            p = positionalBook.get(key);
        }
        return Optional.ofNullable(p);
    }

    /**
     * Generates a unique composite key for a position based on account, symbol, and product type.
     *
     * @param accountId   the account identifier (defaults to "DEFAULT" if null)
     * @param symbol      the trading symbol
     * @param productType the product type (defaults to "MIS" if null)
     * @return a composite key string in the format {@code <accountId>_<symbol>_<productType>}
     */
    private String generateKey(String accountId, String symbol, ProductType productType) {
        return (accountId != null ? accountId : "DEFAULT") + "_" + symbol + "_" + (productType != null ? productType.name() : "MIS");
    }

    /**
     * Disposes all active subscriptions on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        for (Disposable d : subscriptions) {
            if (!d.isDisposed()) d.dispose();
        }
    }
}
