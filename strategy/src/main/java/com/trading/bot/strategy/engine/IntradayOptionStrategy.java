package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.BrokerException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.StrategyExecutionGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.StrikeSelection;
import com.trading.bot.strategy.model.TargetIndex;
import com.trading.bot.strategy.model.TradeLeg;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Orchestrates the intraday option selling strategy across the daily lifecycle. */
@Component
public final class IntradayOptionStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(IntradayOptionStrategy.class);
  private static final String KOLKATA_ZONE = "Asia/Kolkata";
  private static final ZoneId IST = ZoneId.of(KOLKATA_ZONE);

  public static final long INDIA_VIX_TOKEN = 264969L;
  public static final String INDIA_VIX_SYMBOL = "INDIA VIX";

  private final Broker broker;
  private final StrategyExecutionGateway gateway;
  private final InstrumentResolver resolver;
  private final PositionRiskManager riskManager;
  private final StrategyStateRepository stateRepository;
  private final StrategyAlertService alertService;
  private final NotificationPublisher publisher;
  private final StrategyProperties props;
  private final Clock clock;
  private final java.util.concurrent.locks.ReentrantLock lock =
      new java.util.concurrent.locks.ReentrantLock();

  @Autowired
  public IntradayOptionStrategy(
      Broker broker,
      StrategyExecutionGateway gateway,
      InstrumentResolver resolver,
      PositionRiskManager riskManager,
      StrategyStateRepository stateRepository,
      StrategyAlertService alertService,
      NotificationPublisher publisher,
      StrategyProperties props) {
    this(
        broker,
        gateway,
        resolver,
        riskManager,
        stateRepository,
        alertService,
        publisher,
        props,
        Clock.system(IST));
  }

  public IntradayOptionStrategy(
      Broker broker,
      StrategyExecutionGateway gateway,
      InstrumentResolver resolver,
      PositionRiskManager riskManager,
      StrategyStateRepository stateRepository,
      StrategyAlertService alertService,
      NotificationPublisher publisher,
      StrategyProperties props,
      Clock clock) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.riskManager = Objects.requireNonNull(riskManager, "riskManager must not be null");
    this.stateRepository =
        Objects.requireNonNull(stateRepository, "stateRepository must not be null");
    this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Identifies target index for trading day (Mon/Tue -> NIFTY, Wed/Thu -> SENSEX, Fri/Wknd ->
   * null).
   *
   * @param date current date
   * @return TargetIndex or null if non-trading day
   */
  public TargetIndex getTargetIndexForDate(LocalDate date) {
    final DayOfWeek dow = date.getDayOfWeek();
    if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.TUESDAY || dow == DayOfWeek.FRIDAY) {
      return TargetIndex.NIFTY;
    }
    if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY) {
      return TargetIndex.SENSEX;
    }
    return null;
  }

  private static final String AVOID_GENERIC_EXC = "PMD.AvoidCatchingGenericException";

  /** Checkpoint 1: 09:45 AM IST - Buy 20 OTM CE and PE hedges with atomic rollback. */
  @SuppressWarnings(AVOID_GENERIC_EXC)
  public void executeHedge() {
    final LocalDate today = LocalDate.now(clock);
    final TargetIndex index = getTargetIndexForDate(today);
    if (index == null) {
      return;
    }

    lock.lock();
    try {
      final DailyTradeRecord existing = stateRepository.load(today).orElse(null);
      if (existing != null && existing.state() != StrategyState.IDLE) {
        return;
      }
    } finally {
      lock.unlock();
    }

    if (!isVixWithinThreshold()) {
      return;
    }

    final double spot = fetchSpotPrice(index);
    if (spot <= 0) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed fetching spot price for {} at 09:45 IST", index.name());
      }
      return;
    }

    final StrikeSelection strikes = resolver.selectStrikes(index, spot);
    final int qty = props.lots() * index.lotSize();
    final Exchange derivExchange =
        index.spotExchange() == Exchange.NSE ? Exchange.NFO : Exchange.BFO;

    final LocalDate expiry = ExpiryCalculator.getWeeklyExpiry(index, today);
    final String ceSymbol = resolver.formatOptionSymbol(index, expiry, strikes.otmCeStrike(), "CE");
    final String peSymbol = resolver.formatOptionSymbol(index, expiry, strikes.otmPeStrike(), "PE");
    final InstrumentToken ceToken = resolver.createOptionToken(index, strikes.otmCeStrike(), "CE");
    final InstrumentToken peToken = resolver.createOptionToken(index, strikes.otmPeStrike(), "PE");

    final TradeLeg hCe;
    try {
      final BigDecimal ceFill = gateway.executeBuyMarket(ceSymbol, ceToken, derivExchange, qty);
      hCe = new TradeLeg(ceSymbol, ceToken, qty, ceFill, BigDecimal.ZERO, true);
    } catch (RuntimeException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed executing CE hedge wing: {}", e.getMessage(), e);
      }
      return;
    }

    final TradeLeg hPe;
    try {
      final BigDecimal peFill = gateway.executeBuyMarket(peSymbol, peToken, derivExchange, qty);
      hPe = new TradeLeg(peSymbol, peToken, qty, peFill, BigDecimal.ZERO, true);
    } catch (RuntimeException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(
            "PE hedge failed after CE executed. Rolling back CE hedge wing: {}", e.getMessage(), e);
      }
      gateway.executeSellMarket(hCe.symbol(), hCe.token(), derivExchange, hCe.quantity());
      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.SYSTEM_ALERT,
              "Hedge Atomic Rollback",
              "PE hedge failed. Unwound CE hedge wing to prevent partial exposure: "
                  + e.getMessage()));
      return;
    }

    lock.lock();
    try {
      final DailyTradeRecord record =
          new DailyTradeRecord(
              today, index, StrategyState.HEDGED, hCe, hPe, null, null, BigDecimal.ZERO);
      stateRepository.save(record);
    } finally {
      lock.unlock();
    }

    alertService.notifyHedgeExecution(
        new DailyTradeRecord(
            today, index, StrategyState.HEDGED, hCe, hPe, null, null, BigDecimal.ZERO),
        spot,
        props.executionMode());
  }

  /** Checkpoint 2: 10:00 AM IST - Sell ATM CE and ATM PE straddle with atomic rollback. */
  @SuppressWarnings(AVOID_GENERIC_EXC)
  public void executeStraddle() {
    final LocalDate today = LocalDate.now(clock);
    final TargetIndex index = getTargetIndexForDate(today);
    if (index == null) {
      return;
    }

    DailyTradeRecord record;
    lock.lock();
    try {
      record = stateRepository.load(today).orElse(null);
    } finally {
      lock.unlock();
    }

    if (record == null || record.state() != StrategyState.HEDGED) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Aborting 10:00 straddle sell: 09:45 hedges not active for {}", today);
      }
      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.SYSTEM_ALERT,
              "Straddle Entry Aborted",
              "Cannot sell ATM straddle: 09:45 hedges are not active. Safety interlock engaged."));
      return;
    }

    if (!isVixWithinThreshold()) {
      return;
    }

    final double spot = fetchSpotPrice(index);
    final int atmStrike = resolver.computeAtmStrike(index, spot);
    final int qty = props.lots() * index.lotSize();
    final Exchange derivExchange =
        index.spotExchange() == Exchange.NSE ? Exchange.NFO : Exchange.BFO;

    final LocalDate expiry = ExpiryCalculator.getWeeklyExpiry(index, today);
    final String ceSymbol = resolver.formatOptionSymbol(index, expiry, atmStrike, "CE");
    final String peSymbol = resolver.formatOptionSymbol(index, expiry, atmStrike, "PE");
    final InstrumentToken ceToken = resolver.createOptionToken(index, atmStrike, "CE");
    final InstrumentToken peToken = resolver.createOptionToken(index, atmStrike, "PE");

    final double slMultiplier = 1.0 + (props.slPercentage() / 100.0);

    final TradeLeg sCe;
    try {
      final BigDecimal ceFill = gateway.executeSellMarket(ceSymbol, ceToken, derivExchange, qty);
      final BigDecimal ceSl =
          TickUtils.roundToTick(ceFill.multiply(BigDecimal.valueOf(slMultiplier)));
      final BigDecimal ceSlLimit = TickUtils.roundToTick(ceSl.multiply(BigDecimal.valueOf(1.05)));
      final OrderId ceSlOrderId =
          gateway.placeStopLossBuy(ceSymbol, ceToken, derivExchange, qty, ceSl, ceSlLimit);
      sCe = new TradeLeg(ceSymbol, ceToken, qty, ceFill, BigDecimal.ZERO, ceSl, ceSlOrderId, true);
    } catch (RuntimeException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed executing CE leg during straddle entry: {}", e.getMessage(), e);
      }
      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.SYSTEM_ALERT,
              "Straddle CE Sell Failed",
              "CE leg sell failed: " + e.getMessage()));
      return;
    }

    final TradeLeg sPe;
    try {
      final BigDecimal peFill = gateway.executeSellMarket(peSymbol, peToken, derivExchange, qty);
      final BigDecimal peSl =
          TickUtils.roundToTick(peFill.multiply(BigDecimal.valueOf(slMultiplier)));
      final BigDecimal peSlLimit = TickUtils.roundToTick(peSl.multiply(BigDecimal.valueOf(1.05)));
      final OrderId peSlOrderId =
          gateway.placeStopLossBuy(peSymbol, peToken, derivExchange, qty, peSl, peSlLimit);
      sPe = new TradeLeg(peSymbol, peToken, qty, peFill, BigDecimal.ZERO, peSl, peSlOrderId, true);
    } catch (RuntimeException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(
            "PE leg failed after CE executed. Rolling back CE leg immediately: {}",
            e.getMessage(),
            e);
      }
      if (sCe.slOrderId() != null) {
        gateway.cancelOrder(sCe.slOrderId());
      }
      gateway.executeBuyMarket(sCe.symbol(), sCe.token(), derivExchange, sCe.quantity());
      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.SYSTEM_ALERT,
              "Straddle Atomic Rollback",
              "PE leg failed. Unwound CE leg to prevent naked exposure: " + e.getMessage()));

      lock.lock();
      try {
        stateRepository.save(
            new DailyTradeRecord(
                today,
                index,
                StrategyState.ABORTED,
                closeLeg(record.hedgeCe()),
                closeLeg(record.hedgePe()),
                null,
                null,
                record.realizedPnl()));
      } finally {
        lock.unlock();
      }
      return;
    }

    final DailyTradeRecord updated =
        new DailyTradeRecord(
            today,
            index,
            StrategyState.STRADDLE_ACTIVE,
            record.hedgeCe(),
            record.hedgePe(),
            sCe,
            sPe,
            record.realizedPnl());
    lock.lock();
    try {
      stateRepository.save(updated);
    } finally {
      lock.unlock();
    }

    alertService.notifyStraddleExecution(updated, spot, props.executionMode());
  }

  /** Checkpoint 3: Continuous 1-second Risk & Stop-Loss Monitoring Loop. */
  @Scheduled(fixedDelay = 1000)
  @SuppressWarnings(AVOID_GENERIC_EXC)
  public void checkRisk() {
    final LocalDate today = LocalDate.now(clock);
    try {
      final DailyTradeRecord record;
      lock.lock();
      try {
        record = stateRepository.load(today).orElse(null);
      } finally {
        lock.unlock();
      }
      if (record != null
          && (record.state() == StrategyState.STRADDLE_ACTIVE
              || record.state() == StrategyState.ONE_LEG_STOPPED)) {
        riskManager.evaluateRisk(record);
      }
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Error during risk evaluation loop: {}", e.getMessage(), e);
      }
    }
  }

  /**
   * Resilient periodic checkpoint runner executing checkpoints within safe 4-minute time windows.
   */
  @Scheduled(fixedDelay = 5000)
  @SuppressWarnings(AVOID_GENERIC_EXC)
  public void scheduledCheckpoints() {
    try {
      final LocalTime now = LocalTime.now(clock);
      final LocalDate today = LocalDate.now(clock);
      if (getTargetIndexForDate(today) == null) {
        return;
      }

      // 09:45 window (09:45:00 to 09:49:00)
      if (!now.isBefore(props.hedgeTime()) && now.isBefore(props.hedgeTime().plusMinutes(4))) {
        executeHedge();
      }

      // 10:00 window (10:00:00 to 10:04:00)
      if (!now.isBefore(props.entryTime()) && now.isBefore(props.entryTime().plusMinutes(4))) {
        executeStraddle();
      }

      // 15:00 window (15:00:00 to 15:15:00)
      if (!now.isBefore(props.exitTime()) && now.isBefore(props.exitTime().plusMinutes(15))) {
        executeSquareOff();
      }
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Error during scheduled checkpoints loop: {}", e.getMessage(), e);
      }
    }
  }

  /** Checkpoint 4: 15:00 PM IST - Mandatory EOD Square-Off of all active legs. */
  public void executeSquareOff() {
    final LocalDate today = LocalDate.now(clock);
    DailyTradeRecord record;
    lock.lock();
    try {
      record = stateRepository.load(today).orElse(null);
    } finally {
      lock.unlock();
    }

    if (record == null
        || record.state() == StrategyState.COMPLETED
        || record.state() == StrategyState.IDLE) {
      return;
    }

    final Exchange derivExchange =
        record.index().spotExchange() == Exchange.NSE ? Exchange.NFO : Exchange.BFO;

    TradeLeg shortCe = record.shortCe();
    TradeLeg shortPe = record.shortPe();
    TradeLeg hedgeCe = record.hedgeCe();
    TradeLeg hedgePe = record.hedgePe();

    // 0. Cancel open exchange stop-loss orders
    if (shortCe != null && shortCe.slOrderId() != null) {
      gateway.cancelOrder(shortCe.slOrderId());
    }
    if (shortPe != null && shortPe.slOrderId() != null) {
      gateway.cancelOrder(shortPe.slOrderId());
    }

    // 1. Close remaining open short legs
    if (shortCe != null && shortCe.isOpen()) {
      final BigDecimal exit =
          gateway.executeBuyMarket(
              shortCe.symbol(), shortCe.token(), derivExchange, shortCe.quantity());
      shortCe =
          new TradeLeg(
              shortCe.symbol(),
              shortCe.token(),
              shortCe.quantity(),
              shortCe.entryPrice(),
              exit,
              shortCe.slPrice(),
              false);
    }
    if (shortPe != null && shortPe.isOpen()) {
      final BigDecimal exit =
          gateway.executeBuyMarket(
              shortPe.symbol(), shortPe.token(), derivExchange, shortPe.quantity());
      shortPe =
          new TradeLeg(
              shortPe.symbol(),
              shortPe.token(),
              shortPe.quantity(),
              shortPe.entryPrice(),
              exit,
              shortPe.slPrice(),
              false);
    }

    // 2. Close long hedge wings
    if (hedgeCe != null && hedgeCe.isOpen()) {
      final BigDecimal exit =
          gateway.executeSellMarket(
              hedgeCe.symbol(), hedgeCe.token(), derivExchange, hedgeCe.quantity());
      hedgeCe =
          new TradeLeg(
              hedgeCe.symbol(),
              hedgeCe.token(),
              hedgeCe.quantity(),
              hedgeCe.entryPrice(),
              exit,
              hedgeCe.slPrice(),
              false);
    }
    if (hedgePe != null && hedgePe.isOpen()) {
      final BigDecimal exit =
          gateway.executeSellMarket(
              hedgePe.symbol(), hedgePe.token(), derivExchange, hedgePe.quantity());
      hedgePe =
          new TradeLeg(
              hedgePe.symbol(),
              hedgePe.token(),
              hedgePe.quantity(),
              hedgePe.entryPrice(),
              exit,
              hedgePe.slPrice(),
              false);
    }

    final BigDecimal pnlCe = shortCe != null ? shortCe.calculatePnl(true) : BigDecimal.ZERO;
    final BigDecimal pnlPe = shortPe != null ? shortPe.calculatePnl(true) : BigDecimal.ZERO;
    final BigDecimal pnlHedgeCe = hedgeCe != null ? hedgeCe.calculatePnl(false) : BigDecimal.ZERO;
    final BigDecimal pnlHedgePe = hedgePe != null ? hedgePe.calculatePnl(false) : BigDecimal.ZERO;
    final BigDecimal totalPnl = pnlCe.add(pnlPe).add(pnlHedgeCe).add(pnlHedgePe);

    final DailyTradeRecord completed =
        new DailyTradeRecord(
            today,
            record.index(),
            StrategyState.COMPLETED,
            hedgeCe,
            hedgePe,
            shortCe,
            shortPe,
            totalPnl);
    lock.lock();
    try {
      stateRepository.save(completed);
    } finally {
      lock.unlock();
    }

    alertService.notifyFinalSquareOff(completed, totalPnl, props.executionMode());
  }

  /**
   * Recovers mid-day crashes on startup or executes catch-up square-off if started after 15:00 IST.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartupCheck() {
    final LocalDate today = LocalDate.now(clock);
    final DailyTradeRecord record = stateRepository.load(today).orElse(null);
    if (record != null
        && (record.state() == StrategyState.STRADDLE_ACTIVE
            || record.state() == StrategyState.ONE_LEG_STOPPED)) {
      final LocalTime nowTime = LocalTime.now(clock);
      if (nowTime.isAfter(props.exitTime())) {
        if (LOG.isWarnEnabled()) {
          LOG.warn(
              "Started post-15:00 with active positions from earlier crash. Executing catch-up"
                  + " square-off.");
        }
        executeSquareOff();
      }
    }
  }

  private static TradeLeg closeLeg(TradeLeg leg) {
    if (leg == null) {
      return null;
    }
    return new TradeLeg(
        leg.symbol(),
        leg.token(),
        leg.quantity(),
        leg.entryPrice(),
        leg.entryPrice(),
        leg.slPrice(),
        leg.slOrderId(),
        false);
  }

  private double fetchSpotPrice(TargetIndex index) {
    try {
      final QuoteRef ref =
          new QuoteRef(
              index.spotExchange(), index.spotSymbol(), new InstrumentToken(index.spotToken()));
      final Map<InstrumentToken, Quote> quotes = broker.quotes(List.of(ref));
      final Quote q = quotes.get(ref.token());
      if (q != null && q.lastPrice() != null) {
        return q.lastPrice().doubleValue();
      }
    } catch (BrokerException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed fetching spot price for {}: {}", index.spotSymbol(), e.getMessage(), e);
      }
    }
    return 0.0;
  }

  private boolean isVixWithinThreshold() {
    try {
      final QuoteRef vixRef =
          new QuoteRef(Exchange.NSE, INDIA_VIX_SYMBOL, new InstrumentToken(INDIA_VIX_TOKEN));
      final Map<InstrumentToken, Quote> quotes = broker.quotes(List.of(vixRef));
      final Quote q = quotes.get(vixRef.token());
      if (q != null && q.lastPrice() != null) {
        final double vix = q.lastPrice().doubleValue();
        if (vix > props.maxVixThreshold()) {
          if (LOG.isWarnEnabled()) {
            LOG.warn(
                "India VIX ({}) exceeds threshold ({}). Circuit breaker engaged.",
                vix,
                props.maxVixThreshold());
          }
          publisher.publish(
              Notification.of(
                  Severity.CRITICAL,
                  NotificationType.SYSTEM_ALERT,
                  "VIX Circuit Breaker Engaged",
                  String.format(
                      Locale.ROOT,
                      "India VIX is %.2f, exceeding max allowable threshold %.2f. Trading gated.",
                      vix,
                      props.maxVixThreshold())));
          return false;
        }
      }
    } catch (BrokerException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Notice checking India VIX: {}", e.getMessage());
      }
    }
    return true;
  }
}
