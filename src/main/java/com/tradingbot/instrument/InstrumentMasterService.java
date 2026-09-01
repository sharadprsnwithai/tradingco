package com.tradingbot.instrument;

import com.tradingbot.model.Instrument;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance disk-backed SQLite Instrument Master with a bounded in-memory active token cache.
 * Keeps memory footprint minimal (<1 MB heap) on constrained VPS environments (1 GB RAM).
 */
@Service
public class InstrumentMasterService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMasterService.class);

    /** Normalizes the stored (possibly quote-wrapped) name column for matching. */
    private static final String NAME_MATCH = "REPLACE(name, '\"', '')";

    /** Normalizes the stored (possibly quote-wrapped) trading_symbol column for matching. */
    private static final String TRADINGSYMBOL_MATCH = "REPLACE(trading_symbol, '\"', '')";

    /**
     * Maps spot index names used by strategies (e.g. "NIFTY", "BANKNIFTY") to the Kite
     * index instrument tradingsymbols (e.g. "NIFTY 50", "NIFTY BANK"). The Kite master
     * has no "NSE:NIFTY" canonical row — the spot index trades as "NSE:NIFTY 50".
     */
    private static final Map<String, String> INDEX_TRADING_SYMBOLS = Map.of(
        "NIFTY", "NIFTY 50",
        "BANKNIFTY", "NIFTY BANK",
        "FINNIFTY", "NIFTY FIN SERVICE",
        "MIDCPNIFTY", "NIFTY MID SELECT",
        "NIFTYNXT50", "NIFTY NEXT 50",
        "NIFTY 50", "NIFTY 50",
        "NIFTY BANK", "NIFTY BANK",
        "NIFTY FIN SERVICE", "NIFTY FIN SERVICE",
        "NIFTY MID SELECT", "NIFTY MID SELECT",
        "NIFTY NEXT 50", "NIFTY NEXT 50"
    );

    private final String dbUrl;
    private final Map<String, Instrument> activeByCanonical = new ConcurrentHashMap<>();
    private final Map<String, Instrument> activeByKiteToken = new ConcurrentHashMap<>();
    private final Map<String, Instrument> activeByShoonyaToken = new ConcurrentHashMap<>();

    /**
     * Constructs the InstrumentMasterService with the configured SQLite database path.
     * Creates parent directories if they do not exist.
     *
     * @param dbPath the filesystem path to the SQLite instrument database file
     */
    public InstrumentMasterService(@Value("${bot.instrument.db-path:data/instruments.db}") String dbPath) {
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
     * Creates or validates the SQLite schema for the instruments table and its indexes.
     *
     * @throws RuntimeException if schema initialization fails
     */
    public void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS instruments (
                    canonical_symbol TEXT PRIMARY KEY,
                    kite_token TEXT,
                    shoonya_token TEXT,
                    exchange TEXT,
                    trading_symbol TEXT,
                    name TEXT,
                    lot_size INTEGER,
                    tick_size REAL,
                    instrument_type TEXT,
                    strike REAL,
                    expiry TEXT
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_kite_token ON instruments(kite_token);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shoonya_token ON instruments(exchange, shoonya_token);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lookup ON instruments(exchange, trading_symbol);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_options ON instruments(name, expiry, strike, instrument_type);");
            log.info("SQLite Instrument Master schema initialized at {}", dbUrl);
        } catch (SQLException e) {
            log.error("Failed to initialize SQLite instrument schema", e);
            throw new RuntimeException("SQLite initialization failed", e);
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
        }
        return conn;
    }

    /**
     * Batch insert or replace instruments into SQLite database.
     */
    public Mono<Void> saveInstruments(List<Instrument> instruments) {
        return Mono.fromRunnable(() -> {
            String sql = """
                INSERT OR REPLACE INTO instruments (
                    canonical_symbol, kite_token, shoonya_token, exchange,
                    trading_symbol, name, lot_size, tick_size, instrument_type, strike, expiry
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Instrument inst : instruments) {
                        ps.setString(1, inst.canonicalSymbol());
                        ps.setString(2, inst.kiteToken());
                        ps.setString(3, inst.shoonyaToken());
                        ps.setString(4, inst.exchange());
                        ps.setString(5, inst.tradingSymbol());
                        ps.setString(6, inst.name());
                        ps.setInt(7, inst.lotSize());
                        ps.setDouble(8, inst.tickSize() != null ? inst.tickSize().doubleValue() : 0.05);
                        ps.setString(9, inst.instrumentType());
                        if (inst.strike() != null) {
                            ps.setDouble(10, inst.strike().doubleValue());
                        } else {
                            ps.setNull(10, java.sql.Types.DOUBLE);
                        }
                        ps.setString(11, inst.expiry());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                log.info("Persisted {} instruments to SQLite master database", instruments.size());
            } catch (SQLException e) {
                log.error("Failed to batch save instruments", e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Look up instrument by canonical symbol (e.g., "NSE:RELIANCE").
     */
    public Mono<Instrument> findByCanonicalSymbol(String canonicalSymbol) {
        if (canonicalSymbol == null) return Mono.empty();
        Instrument cached = activeByCanonical.get(canonicalSymbol);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
            String sql = "SELECT * FROM instruments WHERE canonical_symbol = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, canonicalSymbol);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Resolves a (possibly abstract) market-data symbol to a concrete tradeable
     * instrument for live ticks / historical candles.
     *
     * Handles the abstract index-future symbols the strategies subscribe with
     * (e.g. {@code NFO:NIFTY_FUT}, {@code NFO:NIFTY_50}) which are NOT stored as
     * canonical symbols in the master. They are mapped to the current
     * nearest-expiry FUT contract of the underlying (e.g. {@code NFO:NIFTY25AUG24FUT}),
     * whose Kite token drives the actual feed.
     *
     * @param symbol abstract or concrete symbol (with or without exchange prefix)
     * @return the concrete instrument to subscribe / fetch history for, or empty
     */
    public Mono<Instrument> resolveForMarketData(String symbol) {
        if (symbol == null) return Mono.empty();
        return findByCanonicalSymbol(symbol)
            .switchIfEmpty(Mono.defer(() -> resolveAbstract(symbol)));
    }

    private Mono<Instrument> resolveAbstract(String symbol) {
        String s = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;

        // NFO:NIFTY_FUT / NFO:BANKNIFTY_FUT -> nearest FUT contract of the underlying
        if (s.endsWith("_FUT")) {
            String name = s.substring(0, s.length() - 4);
            log.info("[INSTR] '{}' not found as canonical — mapping to nearest {} FUT contract", symbol, name);
            return findNearestExpiring(name, "FUT");
        }

        // Index-style abstracts: NIFTY_50, BANKNIFTY_50, FINNIFTY_50, MIDCPNIFTY_50, ...
        // Strip the trailing _<digits> to recover the underlying name and map to its FUT.
        if (s.matches(".+_\\d+")) {
            String name = s.substring(0, s.lastIndexOf('_'));
            log.info("[INSTR] '{}' not found as canonical — mapping index '{}' to nearest FUT contract", symbol, name);
            return findNearestExpiring(name, "FUT");
        }

        // Spot index names on NSE (e.g. "NSE:NIFTY" from a strategy's spot subscription)
        // map to the actual index quote instrument (e.g. "NSE:NIFTY 50"). Without this,
        // token resolution fails ("No Kite token found for NSE:NIFTY") and the spot feed
        // plus spot historical warmup silently produce no data. NFO-prefixed symbols keep
        // falling through (NFO abstracts are futures-oriented).
        String indexTradingsymbol = INDEX_TRADING_SYMBOLS.get(s.toUpperCase());
        if (indexTradingsymbol != null && !symbol.startsWith("NFO:")) {
            log.info("[INSTR] '{}' not found as canonical — mapping to spot index '{}'", symbol, indexTradingsymbol);
            return findIndexInstrument(indexTradingsymbol);
        }

        return Mono.empty();
    }

    /**
     * Looks up the NSE spot index instrument by its Kite tradingsymbol (e.g. "NIFTY 50").
     *
     * @param tradingSymbol the Kite index tradingsymbol
     * @return the index instrument, or empty if not present in the master
     */
    private Mono<Instrument> findIndexInstrument(String tradingSymbol) {
        return Mono.fromCallable(() -> {
            String sql = "SELECT * FROM instruments WHERE exchange = 'NSE' AND instrument_type = 'EQ' AND "
                + TRADINGSYMBOL_MATCH + " = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tradingSymbol);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Look up instrument by Kite numeric token string.
     */
    public Mono<Instrument> findByKiteToken(String kiteToken) {
        if (kiteToken == null) return Mono.empty();
        Instrument cached = activeByKiteToken.get(kiteToken);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
            String sql = "SELECT * FROM instruments WHERE kite_token = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, kiteToken);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Look up instrument by Shoonya exchange and token string.
     */
    public Mono<Instrument> findByShoonyaToken(String exchange, String shoonyaToken) {
        if (shoonyaToken == null) return Mono.empty();
        String key = (exchange != null ? exchange : "NSE") + "|" + shoonyaToken;
        Instrument cached = activeByShoonyaToken.get(key);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> {
            String sql = "SELECT * FROM instruments WHERE exchange = ? AND shoonya_token = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, exchange);
                ps.setString(2, shoonyaToken);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Bulk-updates Shoonya instrument tokens keyed by canonical symbol.
     * Used by the Shoonya master sync to back-fill {@code shoonya_token} on the
     * rows already created from the Kite master.
     *
     * @param canonicalToToken map of canonicalSymbol -&gt; Shoonya token
     * @return Mono emitting the number of rows updated
     */
    public Mono<Integer> updateShoonyaTokens(Map<String, String> canonicalToToken) {
        if (canonicalToToken == null || canonicalToToken.isEmpty()) return Mono.just(0);
        return Mono.fromCallable(() -> {
            int updated = 0;
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE instruments SET shoonya_token = ? WHERE canonical_symbol = ?")) {
                for (Map.Entry<String, String> e : canonicalToToken.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    ps.setString(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                for (int r : results) if (r > 0) updated++;
            }
            return updated;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Returns the distinct underlying names of NFO FUT contracts (the F&O stock universe).
     * Used to build the gainers/losers universe for backup selection via another broker.
     */
    public Mono<List<String>> getDistinctFoUnderlyingNames() {
        return Mono.fromCallable(() -> {
            List<String> names = new ArrayList<>();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT name FROM instruments WHERE exchange='NFO' AND instrument_type='FUT'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String n = rs.getString(1);
                        if (n != null && !n.isBlank()) names.add(n);
                    }
                }
            }
            return names;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Find option contracts for a given underlying, expiry, strike, and CE/PE type.
     */
    public Flux<Instrument> findOptionContracts(String underlying, String expiry, BigDecimal strike, String instrumentType) {
        return Mono.fromCallable(() -> {
            List<Instrument> results = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM instruments WHERE " + NAME_MATCH + " = ?");
            List<Object> params = new ArrayList<>();
            params.add(underlying);

            if (expiry != null) {
                sql.append(" AND expiry = ?");
                params.add(expiry);
            }
            if (strike != null) {
                sql.append(" AND strike = ?");
                params.add(strike.doubleValue());
            }
            if (instrumentType != null) {
                sql.append(" AND instrument_type = ?");
                params.add(instrumentType);
            }

            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
            }
            return results;
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMapMany(Flux::fromIterable);
    }

    /**
     * Finds upcoming distinct expiry dates for an underlying and instrument type (sorted ascending).
     *
     * @param name           underlying name (e.g. "NIFTY")
     * @param instrumentType "CE", "PE", or "FUT"
     * @param limit          maximum number of upcoming expiry dates to return
     * @return a Flux of distinct expiry date strings (YYYY-MM-DD)
     */
    public Flux<String> findUpcomingExpiries(String name, String instrumentType, int limit) {
        return Mono.fromCallable(() -> {
            List<String> expiries = new ArrayList<>();
            String todayStr = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
            // NOTE: name must be matched through NAME_MATCH — the master stores names
            // quote-wrapped ('"NIFTY"'), so a raw `name = ?` comparison matches 0 rows.
            // Use the NAME_MATCH constant (REPLACE(name, '\"', '')) to correctly compare.
            String sql = "SELECT DISTINCT expiry FROM instruments WHERE "
                + NAME_MATCH + " = ? AND instrument_type = ? AND expiry >= ? ORDER BY expiry ASC LIMIT ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, instrumentType);
                ps.setString(3, todayStr);
                ps.setInt(4, limit > 0 ? limit : 5);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String exp = rs.getString("expiry");
                        if (exp != null && !exp.isBlank()) {
                            expiries.add(exp);
                        }
                    }
                }
            }
            return expiries;
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMapMany(Flux::fromIterable);
    }

    /**
     * Finds the nearest-expiry instrument of a given type for an underlying
     * (e.g. name="NIFTY", instrumentType="FUT" for the current NIFTY futures contract).
     * Only expiries on or after today (IST) are considered.
     *
     * @param name           underlying name as in the instruments dump (e.g. "NIFTY")
     * @param instrumentType "FUT", "CE", or "PE"
     * @return the nearest-expiry instrument, or empty if none found
     */
    public Mono<Instrument> findNearestExpiring(String name, String instrumentType) {
        return Mono.fromCallable(() -> {
            String todayStr = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
            String sql = "SELECT * FROM instruments WHERE " + NAME_MATCH
                + " = ? AND instrument_type = ? AND expiry >= ? ORDER BY expiry ASC LIMIT 1";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, instrumentType);
                ps.setString(3, todayStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Finds the ATM option contract for an underlying at the nearest expiry:
     * the strike closest to the given reference price (e.g. current NIFTY futures LTP).
     *
     * @param name       underlying name (e.g. "NIFTY")
     * @param refPrice   reference price to center the ATM strike on
     * @param optionType "CE" or "PE"
     * @return the ATM option instrument, or empty if none found
     */
    public Mono<Instrument> findNearestAtmOption(String name, double refPrice, String optionType) {
        return Mono.fromCallable(() -> {
            String todayStr = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
            String sql = "SELECT * FROM instruments WHERE " + NAME_MATCH
                + " = ? AND instrument_type = ? AND expiry >= ? AND expiry = (SELECT MIN(expiry) FROM instruments"
                + " WHERE " + NAME_MATCH + " = ? AND instrument_type = ? AND expiry >= ?) ORDER BY ABS(strike - ?) ASC LIMIT 1";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, optionType);
                ps.setString(3, todayStr);
                ps.setString(4, name);
                ps.setString(5, optionType);
                ps.setString(6, todayStr);
                ps.setDouble(7, refPrice);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Instrument inst = mapRow(rs);
                        cacheActive(inst);
                        return Optional.of(inst);
                    }
                }
            }
            return Optional.<Instrument>empty();
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    /**
     * Pin an actively traded instrument into the bounded fast-lookup memory cache.
     */
    public void cacheActive(Instrument inst) {
        if (inst == null) return;
        if (inst.canonicalSymbol() != null) activeByCanonical.put(inst.canonicalSymbol(), inst);
        if (inst.kiteToken() != null) activeByKiteToken.put(inst.kiteToken(), inst);
        if (inst.shoonyaToken() != null && inst.exchange() != null) {
            activeByShoonyaToken.put(inst.exchange() + "|" + inst.shoonyaToken(), inst);
            activeByShoonyaToken.put(inst.shoonyaToken(), inst);
        }
    }

    /**
     * Returns the number of instruments currently held in the active cache.
     *
     * @return the cache size as number of canonical symbols cached
     */
    public int getActiveCacheSize() {
        return activeByCanonical.size();
    }

    /**
     * Clears all instruments from the active in-memory cache.
     */
    public void clearActiveCache() {
        activeByCanonical.clear();
        activeByKiteToken.clear();
        activeByShoonyaToken.clear();
    }

    /**
     * Maps a {@link ResultSet} row to an {@link Instrument} record.
     *
     * @param rs the result set positioned at the current row
     * @return the mapped Instrument instance
     * @throws SQLException if a database access error occurs
     */
    private Instrument mapRow(ResultSet rs) throws SQLException {
        double strikeVal = rs.getDouble("strike");
        BigDecimal strike = rs.wasNull() ? null : BigDecimal.valueOf(strikeVal);
        double tickVal = rs.getDouble("tick_size");
        BigDecimal tickSize = rs.wasNull() ? new BigDecimal("0.05") : BigDecimal.valueOf(tickVal);

        return new Instrument(
            rs.getString("canonical_symbol"),
            rs.getString("kite_token"),
            rs.getString("shoonya_token"),
            rs.getString("exchange"),
            rs.getString("trading_symbol"),
            rs.getString("name"),
            rs.getInt("lot_size"),
            tickSize,
            rs.getString("instrument_type"),
            strike,
            rs.getString("expiry")
        );
    }
}
