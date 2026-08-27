package com.tradingbot.database;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SQLite Operational Database Service for Orders, Positions, Risk Audit Logs,
 * and Authentic Exchange Historical Candle Storage (zero synthetic data).
 */
@Service
public class TradingDbService {

    private static final Logger log = LoggerFactory.getLogger(TradingDbService.class);

    private final String dbUrl;

    /**
     * Constructs the TradingDbService with the configured SQLite database path.
     * Creates parent directories if they do not exist.
     *
     * @param dbPath the filesystem path to the SQLite database file
     */
    public TradingDbService(@Value("${bot.db.path:data/trading_state.db}") String dbPath) {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.dbUrl = "jdbc:sqlite:" + dbPath;
    }

    /**
     * Initializes the database schema after dependency injection is complete.
     */
    @PostConstruct
    public void init() {
        initSchema();
    }

    /**
     * Creates or validates the SQLite schema including tables for orders,
     * positions, historical candles, and risk audit logs.
     *
     * @throws RuntimeException if schema initialization fails
     */
    public void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // 1. Orders table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id TEXT PRIMARY KEY,
                    broker_order_id TEXT,
                    account_id TEXT,
                    broker_id TEXT,
                    strategy_id TEXT,
                    symbol TEXT,
                    exchange TEXT,
                    instrument_token TEXT,
                    transaction_type TEXT,
                    quantity INTEGER,
                    filled_quantity INTEGER,
                    price REAL,
                    trigger_price REAL,
                    average_price REAL,
                    order_type TEXT,
                    product_type TEXT,
                    book_type TEXT,
                    status TEXT,
                    status_message TEXT,
                    tag TEXT,
                    created_at INTEGER,
                    updated_at INTEGER
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_account ON orders(account_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_strategy ON orders(strategy_id);");

            // 2. Positions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS positions (
                    id TEXT PRIMARY KEY,
                    account_id TEXT,
                    broker_id TEXT,
                    symbol TEXT,
                    exchange TEXT,
                    instrument_token TEXT,
                    product_type TEXT,
                    book_type TEXT,
                    net_quantity INTEGER,
                    buy_quantity INTEGER,
                    sell_quantity INTEGER,
                    buy_average_price REAL,
                    sell_average_price REAL,
                    ltp REAL,
                    mtm_pnl REAL,
                    realized_pnl REAL,
                    unrealized_pnl REAL,
                    auto_square_off INTEGER DEFAULT 1,
                    updated_at INTEGER
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_positions_book ON positions(account_id, book_type);");

            // Migrate: add auto_square_off if missing
            try {
                stmt.execute("ALTER TABLE positions ADD COLUMN auto_square_off INTEGER DEFAULT 1");
            } catch (Exception ignored) { /* column already exists */ }

            // 3. Authentic Historical Candles table (for deterministic replay backtesting)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS historical_candles (
                    symbol TEXT,
                    timeframe TEXT,
                    timestamp_epoch INTEGER,
                    open REAL,
                    high REAL,
                    low REAL,
                    close REAL,
                    volume INTEGER,
                    PRIMARY KEY(symbol, timeframe, timestamp_epoch)
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_candles_lookup ON historical_candles(symbol, timeframe, timestamp_epoch);");

            // 4. Risk & Kill Switch Audit Log table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS risk_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    strategy_id TEXT,
                    account_id TEXT,
                    action TEXT,
                    level TEXT,
                    reason TEXT,
                    timestamp_epoch INTEGER
                );
            """);

            // 5. VWAP strategy 9:30 / 11:00 baseline snapshots (price + PCR) for restart
            //    rehydration. Keyed by strategy + snapshot type + IST trade date so each
            //    trading day holds one row per snapshot; the latest process to capture it
            //    wins (INSERT OR REPLACE).
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vwap_baseline_snapshot (
                    strategy_id TEXT,
                    snapshot_type TEXT,
                    trade_date TEXT,
                    price REAL,
                    pcr REAL,
                    captured_at INTEGER,
                    PRIMARY KEY(strategy_id, snapshot_type, trade_date)
                );
            """);

            log.info("SQLite Operational Database schema initialized at {}", dbUrl);
        } catch (SQLException e) {
            log.error("Failed to initialize TradingDbService schema", e);
            throw new RuntimeException("SQLite Trading DB initialization failed", e);
        }
    }

    /**
     * Obtains a new JDBC connection to the SQLite database.
     *
     * @return an open database connection
     * @throws SQLException if a database access error occurs
     */
    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    // =========================================================================
    // Order Persistence
    // =========================================================================

    /**
     * Persists or replaces an order record in the database.
     *
     * @param order the order to persist; if null, no operation is performed
     * @return a {@link Mono} that completes when the save operation finishes
     */
    public Mono<Void> saveOrder(Order order) {
        if (order == null) return Mono.empty();
        return Mono.fromRunnable(() -> {
            String sql = """
                INSERT OR REPLACE INTO orders (
                    id, broker_order_id, account_id, broker_id, strategy_id, symbol, exchange, instrument_token,
                    transaction_type, quantity, filled_quantity, price, trigger_price, average_price,
                    order_type, product_type, book_type, status, status_message, tag, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, order.id());
                ps.setString(2, order.brokerOrderId());
                ps.setString(3, order.accountId());
                ps.setString(4, order.brokerId());
                ps.setString(5, order.strategyId());
                ps.setString(6, order.symbol());
                ps.setString(7, order.exchange());
                ps.setString(8, order.instrumentToken());
                ps.setString(9, order.transactionType() != null ? order.transactionType().name() : "BUY");
                ps.setInt(10, order.quantity());
                ps.setInt(11, order.filledQuantity());
                ps.setDouble(12, order.price() != null ? order.price().doubleValue() : 0.0);
                ps.setDouble(13, order.triggerPrice() != null ? order.triggerPrice().doubleValue() : 0.0);
                ps.setDouble(14, order.averagePrice() != null ? order.averagePrice().doubleValue() : 0.0);
                ps.setString(15, order.orderType() != null ? order.orderType().name() : "LIMIT");
                ps.setString(16, order.productType() != null ? order.productType().name() : "MIS");
                ps.setString(17, order.bookType() != null ? order.bookType().name() : "INTRADAY");
                ps.setString(18, order.status() != null ? order.status().name() : "PENDING");
                ps.setString(19, order.statusMessage());
                ps.setString(20, order.tag());
                ps.setLong(21, order.createdAt() != null ? order.createdAt().toEpochMilli() : System.currentTimeMillis());
                ps.setLong(22, order.updatedAt() != null ? order.updatedAt().toEpochMilli() : System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to persist order {}", order.id(), e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Finds an order by its internal ID or broker-assigned order ID.
     *
     * @param orderId the order ID or broker order ID to search for
     * @return a {@link Mono} emitting the matching order, or empty if not found
     */
    public Mono<Order> findOrderById(String orderId) {
        return Mono.fromCallable(() -> {
            String sql = "SELECT * FROM orders WHERE id = ? OR broker_order_id = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId);
                ps.setString(2, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapOrder(rs));
                    }
                }
            }
            return Optional.<Order>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Retrieves all orders with a PENDING, OPEN, or TRIGGER_PENDING status.
     *
     * @return a {@link Flux} of open orders
     */
    public Flux<Order> findOpenOrders() {
        return Mono.fromCallable(() -> {
            List<Order> list = new ArrayList<>();
            String sql = "SELECT * FROM orders WHERE status IN ('PENDING', 'OPEN', 'TRIGGER_PENDING')";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapOrder(rs));
                }
            }
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    /**
     * Retrieves all orders for a given account, ordered by creation time descending.
     *
     * @param accountId the account identifier to filter by
     * @return a {@link Flux} of orders belonging to the specified account
     */
    public Flux<Order> findOrdersByAccount(String accountId) {
        return Mono.fromCallable(() -> {
            List<Order> list = new ArrayList<>();
            String sql = "SELECT * FROM orders WHERE account_id = ? ORDER BY created_at DESC";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapOrder(rs));
                    }
                }
            }
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    // =========================================================================
    // Position Persistence
    // =========================================================================

    /**
     * Persists or replaces a position record in the database.
     *
     * @param pos the position to persist; if null, no operation is performed
     * @return a {@link Mono} that completes when the save operation finishes
     */
    public Mono<Void> savePosition(Position pos) {
        if (pos == null) return Mono.empty();
        return Mono.fromRunnable(() -> {
            String id = pos.accountId() + "_" + pos.symbol() + "_" + pos.productType();
            String sql = """
                INSERT OR REPLACE INTO positions (
                    id, account_id, broker_id, symbol, exchange, instrument_token,
                    product_type, book_type, net_quantity, buy_quantity, sell_quantity,
                    buy_average_price, sell_average_price, ltp, mtm_pnl, realized_pnl, unrealized_pnl,
                    auto_square_off, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, pos.accountId());
                ps.setString(3, pos.brokerId());
                ps.setString(4, pos.symbol());
                ps.setString(5, pos.exchange());
                ps.setString(6, pos.instrumentToken());
                ps.setString(7, pos.productType() != null ? pos.productType().name() : "MIS");
                ps.setString(8, pos.bookType() != null ? pos.bookType().name() : "INTRADAY");
                ps.setInt(9, pos.netQuantity());
                ps.setInt(10, pos.buyQuantity());
                ps.setInt(11, pos.sellQuantity());
                ps.setDouble(12, pos.buyAveragePrice() != null ? pos.buyAveragePrice().doubleValue() : 0.0);
                ps.setDouble(13, pos.sellAveragePrice() != null ? pos.sellAveragePrice().doubleValue() : 0.0);
                ps.setDouble(14, pos.ltp() != null ? pos.ltp().doubleValue() : 0.0);
                ps.setDouble(15, pos.mtmPnl() != null ? pos.mtmPnl().doubleValue() : 0.0);
                ps.setDouble(16, pos.realizedPnl() != null ? pos.realizedPnl().doubleValue() : 0.0);
                ps.setDouble(17, pos.unrealizedPnl() != null ? pos.unrealizedPnl().doubleValue() : 0.0);
                ps.setInt(18, pos.autoSquareOff() ? 1 : 0);
                ps.setLong(19, pos.updatedAt() != null ? pos.updatedAt().toEpochMilli() : System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to persist position for {}", pos.symbol(), e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Retrieves all positions from the database.
     *
     * @return a {@link Flux} of all stored positions
     */
    public Flux<Position> findAllPositions() {
        return Mono.fromCallable(() -> {
            List<Position> list = new ArrayList<>();
            String sql = "SELECT * FROM positions";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPosition(rs));
                }
            }
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    // =========================================================================
    // Authentic Historical Candles Persistence (No Synthetic Data)
    // =========================================================================

    /**
     * Batch persists historical candle data to the database within a transaction.
     *
     * @param candles the list of candles to persist; if null or empty, no operation is performed
     * @return a {@link Mono} that completes when the save operation finishes
     */
    public Mono<Void> saveHistoricalCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return Mono.empty();
        return Mono.fromRunnable(() -> {
            String sql = """
                INSERT OR REPLACE INTO historical_candles (
                    symbol, timeframe, timestamp_epoch, open, high, low, close, volume
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Candle c : candles) {
                        ps.setString(1, c.symbol());
                        ps.setString(2, c.timeframe());
                        ps.setLong(3, c.timestamp().toEpochMilli());
                        ps.setDouble(4, c.open().doubleValue());
                        ps.setDouble(5, c.high().doubleValue());
                        ps.setDouble(6, c.low().doubleValue());
                        ps.setDouble(7, c.close().doubleValue());
                        ps.setLong(8, c.volume());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                log.info("Saved {} authentic exchange candles into SQLite", candles.size());
            } catch (SQLException e) {
                log.error("Failed to save historical candles", e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Loads historical candles for a given symbol and timeframe, ordered by timestamp ascending.
     *
     * @param symbol   the trading symbol (e.g., "RELIANCE")
     * @param timeframe the candle timeframe (e.g., "5m", "1d")
     * @param limit    maximum number of candles to return
     * @return a {@link Flux} of candles in ascending chronological order
     */
    public Flux<Candle> loadHistoricalCandles(String symbol, String timeframe, int limit) {
        return Mono.fromCallable(() -> {
            List<Candle> list = new ArrayList<>();
            String sql = """
                SELECT * FROM historical_candles 
                WHERE symbol = ? AND timeframe = ? 
                ORDER BY timestamp_epoch DESC LIMIT ?
            """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, symbol);
                ps.setString(2, timeframe);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Candle(
                            rs.getString("symbol"),
                            rs.getString("timeframe"),
                            Instant.ofEpochMilli(rs.getLong("timestamp_epoch")),
                            BigDecimal.valueOf(rs.getDouble("open")),
                            BigDecimal.valueOf(rs.getDouble("high")),
                            BigDecimal.valueOf(rs.getDouble("low")),
                            BigDecimal.valueOf(rs.getDouble("close")),
                            rs.getLong("volume")
                        ));
                    }
                }
            }
            // Reverse to return ascending chronological order
            Collections.reverse(list);
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    // =========================================================================
    // Risk & Kill Switch Audit Log
    // =========================================================================

    /**
     * Records a risk or kill-switch audit log entry with a current timestamp.
     *
     * @param strategyId the strategy that triggered the audit event
     * @param accountId  the account affected by the event
     * @param action     the action taken (e.g., "KILL_SWITCH", "POSITION_LIMIT")
     * @param level      the severity level (e.g., "INFO", "WARNING", "CRITICAL")
     * @param reason     human-readable reason for the action
     * @return a {@link Mono} that completes when the log entry is persisted
     */
    public Mono<Void> logRiskAudit(String strategyId, String accountId, String action, String level, String reason) {
        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO risk_audit_log (strategy_id, account_id, action, level, reason, timestamp_epoch) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, strategyId);
                ps.setString(2, accountId);
                ps.setString(3, action);
                ps.setString(4, level);
                ps.setString(5, reason);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to record risk audit log", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Retrieves the most recent risk audit log entries (up to 100), ordered by ID descending.
     *
     * @return a {@link Flux} of {@link RiskAuditRecord} entries
     */
    public Flux<RiskAuditRecord> getRiskAuditLogs() {
        return Mono.fromCallable(() -> {
            List<RiskAuditRecord> list = new ArrayList<>();
            String sql = "SELECT * FROM risk_audit_log ORDER BY id DESC LIMIT 100";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RiskAuditRecord(
                        rs.getLong("id"),
                        rs.getString("strategy_id"),
                        rs.getString("account_id"),
                        rs.getString("action"),
                        rs.getString("level"),
                        rs.getString("reason"),
                        Instant.ofEpochMilli(rs.getLong("timestamp_epoch"))
                    ));
                }
            }
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    /**
     * Maps a {@link ResultSet} row to an {@link Order} record.
     *
     * @param rs the result set positioned at the current row
     * @return the mapped Order instance
     * @throws SQLException if a database access error occurs
     */
    private Order mapOrder(ResultSet rs) throws SQLException {
        return Order.builder()
            .id(rs.getString("id"))
            .brokerOrderId(rs.getString("broker_order_id"))
            .accountId(rs.getString("account_id"))
            .brokerId(rs.getString("broker_id"))
            .strategyId(rs.getString("strategy_id"))
            .symbol(rs.getString("symbol"))
            .exchange(rs.getString("exchange"))
            .instrumentToken(rs.getString("instrument_token"))
            .transactionType(TransactionType.valueOf(rs.getString("transaction_type")))
            .quantity(rs.getInt("quantity"))
            .filledQuantity(rs.getInt("filled_quantity"))
            .price(BigDecimal.valueOf(rs.getDouble("price")))
            .triggerPrice(BigDecimal.valueOf(rs.getDouble("trigger_price")))
            .averagePrice(BigDecimal.valueOf(rs.getDouble("average_price")))
            .orderType(OrderType.valueOf(rs.getString("order_type")))
            .productType(ProductType.valueOf(rs.getString("product_type")))
            .bookType(BookType.valueOf(rs.getString("book_type")))
            .status(OrderStatus.valueOf(rs.getString("status")))
            .statusMessage(rs.getString("status_message"))
            .tag(rs.getString("tag"))
            .createdAt(Instant.ofEpochMilli(rs.getLong("created_at")))
            .updatedAt(Instant.ofEpochMilli(rs.getLong("updated_at")))
            .build();
    }

    /**
     * Maps a {@link ResultSet} row to a {@link Position} record.
     *
     * @param rs the result set positioned at the current row
     * @return the mapped Position instance
     * @throws SQLException if a database access error occurs
     */
    private Position mapPosition(ResultSet rs) throws SQLException {
        return Position.builder()
            .accountId(rs.getString("account_id"))
            .brokerId(rs.getString("broker_id"))
            .symbol(rs.getString("symbol"))
            .exchange(rs.getString("exchange"))
            .instrumentToken(rs.getString("instrument_token"))
            .productType(ProductType.valueOf(rs.getString("product_type")))
            .bookType(BookType.valueOf(rs.getString("book_type")))
            .netQuantity(rs.getInt("net_quantity"))
            .buyQuantity(rs.getInt("buy_quantity"))
            .sellQuantity(rs.getInt("sell_quantity"))
            .buyAveragePrice(BigDecimal.valueOf(rs.getDouble("buy_average_price")))
            .sellAveragePrice(BigDecimal.valueOf(rs.getDouble("sell_average_price")))
            .ltp(BigDecimal.valueOf(rs.getDouble("ltp")))
            .mtmPnl(BigDecimal.valueOf(rs.getDouble("mtm_pnl")))
            .realizedPnl(BigDecimal.valueOf(rs.getDouble("realized_pnl")))
            .unrealizedPnl(BigDecimal.valueOf(rs.getDouble("unrealized_pnl")))
            .autoSquareOff(rs.getInt("auto_square_off") != 0)
            .updatedAt(Instant.ofEpochMilli(rs.getLong("updated_at")))
            .build();
    }

    public record RiskAuditRecord(long id, String strategyId, String accountId, String action, String level, String reason, Instant timestamp) {}

    /**
     * Persisted VWAP strategy baseline snapshot (price + PCR) captured at 9:30 / 11:00 IST,
     * used to rehydrate bias after a mid-day container restart so recovery uses the real
     * intraday PCR instead of a same-value approximation.
     *
     * @param strategyId   the strategy that produced the snapshot (e.g. VWAP_NIFTY_01)
     * @param snapshotType the snapshot kind: "930" or "1100"
     * @param tradeDate    IST trade date (yyyy-MM-dd)
     * @param price        underlying (Nifty futures) price at the snapshot time
     * @param pcr          Put-Call Ratio at the snapshot time
     * @param capturedAt   instant the snapshot was persisted
     */
    public record VwapBaselineSnapshot(
        String strategyId,
        String snapshotType,
        String tradeDate,
        double price,
        double pcr,
        Instant capturedAt
    ) {}

    // =========================================================================
    // VWAP Baseline Snapshot Persistence (restart rehydration)
    // =========================================================================

    /**
     * Persists or replaces a VWAP baseline snapshot for the given strategy / type / trade date.
     *
     * @param s the snapshot to persist; if null, no operation is performed
     * @return a {@link Mono} that completes when the save operation finishes
     */
    public Mono<Void> saveVwapSnapshot(VwapBaselineSnapshot s) {
        if (s == null) return Mono.empty();
        return Mono.fromRunnable(() -> {
            String sql = """
                INSERT OR REPLACE INTO vwap_baseline_snapshot
                    (strategy_id, snapshot_type, trade_date, price, pcr, captured_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, s.strategyId());
                ps.setString(2, s.snapshotType());
                ps.setString(3, s.tradeDate());
                ps.setDouble(4, s.price());
                ps.setDouble(5, s.pcr());
                ps.setLong(6, s.capturedAt() != null ? s.capturedAt().toEpochMilli() : System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to persist VWAP baseline snapshot for {}/{}", s.strategyId(), s.snapshotType(), e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Loads the persisted VWAP baseline snapshot for a strategy / type / IST trade date.
     *
     * @param strategyId   the strategy id (e.g. VWAP_NIFTY_01)
     * @param snapshotType the snapshot kind: "930" or "1100"
     * @param tradeDate    IST trade date (yyyy-MM-dd)
     * @return a {@link Mono} emitting the snapshot if present, otherwise empty
     */
    public Mono<Optional<VwapBaselineSnapshot>> loadVwapSnapshot(String strategyId, String snapshotType, String tradeDate) {
        return Mono.fromCallable(() -> {
            String sql = """
                SELECT * FROM vwap_baseline_snapshot
                WHERE strategy_id = ? AND snapshot_type = ? AND trade_date = ?
                """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, strategyId);
                ps.setString(2, snapshotType);
                ps.setString(3, tradeDate);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new VwapBaselineSnapshot(
                            rs.getString("strategy_id"),
                            rs.getString("snapshot_type"),
                            rs.getString("trade_date"),
                            rs.getDouble("price"),
                            rs.getDouble("pcr"),
                            Instant.ofEpochMilli(rs.getLong("captured_at"))
                        ));
                    }
                }
            }
            return Optional.<VwapBaselineSnapshot>empty();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
