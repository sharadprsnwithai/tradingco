package com.tradingbot.strategy.ironfly;

import com.tradingbot.database.TradingDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence service for Iron Fly positions, legs, and adjustments.
 */
@Service
public class IronFlyDbService {

    private static final Logger log = LoggerFactory.getLogger(IronFlyDbService.class);

    private final String dbUrl;

    public IronFlyDbService(TradingDbService dbService) {
        this.dbUrl = "jdbc:sqlite:" + System.getProperty("bot.db.path", "data/trading_state.db");
    }

    /**
     * Initializes the Iron Fly schema tables.
     */
    public void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ironfly_positions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    underlying TEXT NOT NULL,
                    atm_strike INTEGER,
                    net_credit REAL,
                    original_credit REAL,
                    entry_spot REAL,
                    status TEXT NOT NULL,
                    total_lots INTEGER,
                    created_at INTEGER,
                    closed_at INTEGER
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ironfly_legs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    position_id INTEGER,
                    strike INTEGER,
                    option_type TEXT,
                    is_short INTEGER,
                    entry_price REAL,
                    current_price REAL,
                    lot_size INTEGER,
                    leg_type TEXT,
                    status TEXT,
                    closed_at INTEGER,
                    FOREIGN KEY (position_id) REFERENCES ironfly_positions(id)
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ironfly_adjustments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    position_id INTEGER,
                    side TEXT,
                    adjusted_at INTEGER,
                    old_short_strike INTEGER,
                    new_short_strike INTEGER,
                    old_long_strike INTEGER,
                    new_long_strike INTEGER,
                    credit_delta REAL,
                    reason TEXT,
                    FOREIGN KEY (position_id) REFERENCES ironfly_positions(id)
                );
            """);

            log.info("Iron Fly schema initialized");
        } catch (SQLException e) {
            log.error("Failed to initialize Iron Fly schema", e);
            throw new RuntimeException("Iron Fly DB initialization failed", e);
        }
    }

    /**
     * Saves an Iron Fly position and its legs to the database.
     *
     * @return the generated position ID
     */
    public Mono<Long> savePosition(IronFlyPosition position) {
        return Mono.fromCallable(() -> {
            String sql = """
                INSERT INTO ironfly_positions (underlying, atm_strike, net_credit, original_credit,
                    entry_spot, status, total_lots, created_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, position.underlying());
                ps.setInt(2, position.getAtmStrike());
                ps.setDouble(3, position.getCurrentNetCredit().doubleValue());
                ps.setDouble(4, position.originalCredit() != null ? position.originalCredit().doubleValue() : 0.0);
                ps.setDouble(5, position.entrySpotPrice() != null ? position.entrySpotPrice().doubleValue() : 0.0);
                ps.setString(6, position.status().name());
                ps.setInt(7, position.totalLotSize());
                ps.setLong(8, position.createdAt() != null ? position.createdAt().toEpochMilli() : System.currentTimeMillis());
                ps.setLong(9, position.closedAt() != null ? position.closedAt().toEpochMilli() : 0);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        saveLegs(conn, id, position);
                        return id;
                    }
                }
            }
            throw new RuntimeException("Failed to get generated position ID");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void saveLegs(Connection conn, long positionId, IronFlyPosition position) throws SQLException {
        String sql = """
            INSERT INTO ironfly_legs (position_id, strike, option_type, is_short, entry_price,
                current_price, lot_size, leg_type, status, closed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            saveLeg(conn, ps, positionId, position.shortCall(), "SHORT_CALL");
            saveLeg(conn, ps, positionId, position.shortPut(), "SHORT_PUT");
            saveLeg(conn, ps, positionId, position.longCallHedge(), "LONG_CALL_HEDGE");
            saveLeg(conn, ps, positionId, position.longPutHedge(), "LONG_PUT_HEDGE");
        }
    }

    private void saveLeg(Connection conn, PreparedStatement ps, long positionId, OptionLeg leg, String legType) throws SQLException {
        if (leg == null) return;
        ps.setLong(1, positionId);
        ps.setInt(2, leg.strike());
        ps.setString(3, leg.optionType().name());
        ps.setInt(4, leg.isShort() ? 1 : 0);
        ps.setDouble(5, leg.entryPrice() != null ? leg.entryPrice().doubleValue() : 0.0);
        ps.setDouble(6, leg.currentPrice() != null ? leg.currentPrice().doubleValue() : 0.0);
        ps.setInt(7, leg.lotSize());
        ps.setString(8, legType);
        ps.setString(9, "ACTIVE");
        ps.setLong(10, 0);
        ps.addBatch();
        ps.executeBatch();
    }

    /**
     * Saves an adjustment record.
     */
    public Mono<Void> saveAdjustment(long positionId, AdjustmentRecord record) {
        return Mono.fromRunnable(() -> {
            String sql = """
                INSERT INTO ironfly_adjustments (position_id, side, adjusted_at,
                    old_short_strike, new_short_strike, old_long_strike, new_long_strike,
                    credit_delta, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, positionId);
                ps.setString(2, record.side().name());
                ps.setLong(3, record.adjustedAt().toEpochMilli());
                ps.setInt(4, record.oldShortStrike());
                ps.setInt(5, record.newShortStrike());
                ps.setInt(6, record.oldLongStrike());
                ps.setInt(7, record.newLongStrike());
                ps.setDouble(8, record.creditDelta().doubleValue());
                ps.setString(9, record.reason());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to save adjustment", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Updates position status and net credit.
     */
    public Mono<Void> updatePositionStatus(long positionId, String status, double netCredit) {
        return Mono.fromRunnable(() -> {
            String sql = "UPDATE ironfly_positions SET status = ?, net_credit = ? WHERE id = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setDouble(2, netCredit);
                ps.setLong(3, positionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to update position status", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Closes a position by setting closed_at timestamp.
     */
    public Mono<Void> closePosition(long positionId) {
        return Mono.fromRunnable(() -> {
            String sql = "UPDATE ironfly_positions SET status = 'CLOSED', closed_at = ? WHERE id = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, positionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to close position", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Finds all active (non-closed) Iron Fly positions.
     */
    public Flux<IronFlyPosition> findActivePositions() {
        return Mono.fromCallable(() -> {
            List<IronFlyPosition> list = new ArrayList<>();
            String sql = "SELECT * FROM ironfly_positions WHERE status IN ('RECOMMENDED', 'DISCOVERED', 'TRACKING', 'ADJUSTED')";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPosition(rs));
                }
            }
            return list;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    /**
     * Finds an active position by underlying symbol.
     */
    public Mono<IronFlyPosition> findActiveByUnderlying(String underlying) {
        return findActivePositions()
            .filter(p -> p.underlying().equalsIgnoreCase(underlying))
            .take(1)
            .singleOrEmpty();
    }

    private IronFlyPosition mapPosition(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<AdjustmentRecord> adjustments = loadAdjustments(id);
        java.util.Map<String, OptionLeg> legs = loadLegs(id);

        return new IronFlyPosition(
            rs.getString("underlying"),
            legs.get("SHORT_CALL"),
            legs.get("SHORT_PUT"),
            legs.get("LONG_CALL_HEDGE"),
            legs.get("LONG_PUT_HEDGE"),
            BigDecimal.valueOf(rs.getDouble("entry_spot")),
            BigDecimal.valueOf(rs.getDouble("net_credit")),
            BigDecimal.valueOf(rs.getDouble("original_credit")),
            rs.getInt("total_lots"),
            IronFlyStatus.valueOf(rs.getString("status")),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            rs.getLong("closed_at") > 0 ? Instant.ofEpochMilli(rs.getLong("closed_at")) : null,
            adjustments
        );
    }

    private java.util.Map<String, OptionLeg> loadLegs(long positionId) {
        java.util.Map<String, OptionLeg> legs = new java.util.HashMap<>();
        String sql = "SELECT * FROM ironfly_legs WHERE position_id = ? AND (status != 'CLOSED' OR status IS NULL)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, positionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String legType = rs.getString("leg_type");
                    int strike = rs.getInt("strike");
                    OptionType optionType = OptionType.valueOf(rs.getString("option_type"));
                    boolean isShort = rs.getInt("is_short") == 1;
                    BigDecimal entryPrice = BigDecimal.valueOf(rs.getDouble("entry_price"));
                    BigDecimal currentPrice = BigDecimal.valueOf(rs.getDouble("current_price"));
                    int lotSize = rs.getInt("lot_size");
                    String symbol = strike + optionType.name();

                    OptionLeg leg = new OptionLeg(
                        symbol, strike, optionType, isShort, entryPrice, currentPrice, 0.0, lotSize
                    );
                    legs.put(legType, leg);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load legs for position {}", positionId, e);
        }
        return legs;
    }

    private List<AdjustmentRecord> loadAdjustments(long positionId) {
        List<AdjustmentRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM ironfly_adjustments WHERE position_id = ? ORDER BY adjusted_at ASC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, positionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AdjustmentRecord(
                        AdjustmentSide.valueOf(rs.getString("side")),
                        Instant.ofEpochMilli(rs.getLong("adjusted_at")),
                        rs.getInt("old_short_strike"),
                        rs.getInt("new_short_strike"),
                        rs.getInt("old_long_strike"),
                        rs.getInt("new_long_strike"),
                        BigDecimal.valueOf(rs.getDouble("credit_delta")),
                        rs.getString("reason")
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load adjustments for position {}", positionId);
        }
        return list;
    }

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
}
