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

  private final Broker broker;
  private final StrategyExecutionGateway gateway;
  private final InstrumentResolver resolver;
  private final PositionRiskManager riskManager;
  private final StrategyStateRepository stateRepository;
  private final StrategyAlertService alertService;
  private final NotificationPublisher publisher;
  private final StrategyProperties props;
  private final Clock clock;

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
    if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.TUESDAY) {
      return TargetIndex.NIFTY;
    }
    if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY) {
      return TargetIndex.SENSEX;
    }
    return null;
  }

  /** Checkpoint 1: 09:45 AM IST - Buy 20 OTM CE and PE hedges. */
  @Scheduled(cron = "0 45 9 * * MON-THU", zone = KOLKATA_ZONE)
  public void executeHedge() {
    final LocalDate today = LocalDate.now(clock);
    final TargetIndex index = getTargetIndexForDate(today);
    if (index == null) {
      return;
    }

    final DailyTradeRecord existing = stateRepository.load(today).orElse(null);
    if (existing != null && existing.state() != StrategyState.IDLE) {
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

    final String ceSymbol = index.name() + "_H_CE_" + strikes.otmCeStrike();
    final String peSymbol = index.name() + "_H_PE_" + strikes.otmPeStrike();
    final InstrumentToken ceToken = new InstrumentToken(strikes.otmCeStrike());
    final InstrumentToken peToken = new InstrumentToken(strikes.otmPeStrike());

    final BigDecimal ceFill = gateway.executeBuyMarket(ceSymbol, ceToken, derivExchange, qty);
    final BigDecimal peFill = gateway.executeBuyMarket(peSymbol, peToken, derivExchange, qty);

    final TradeLeg hCe = new TradeLeg(ceSymbol, ceToken, qty, ceFill, BigDecimal.ZERO, true);
    final TradeLeg hPe = new TradeLeg(peSymbol, peToken, qty, peFill, BigDecimal.ZERO, true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            today, index, StrategyState.HEDGED, hCe, hPe, null, null, BigDecimal.ZERO);
    stateRepository.save(record);

    alertService.notifyHedgeExecution(record, spot, props.executionMode());
  }

  /** Checkpoint 2: 10:00 AM IST - Sell ATM CE and ATM PE straddle. */
  @Scheduled(cron = "0 0 10 * * MON-THU", zone = KOLKATA_ZONE)
  public void executeStraddle() {
    final LocalDate today = LocalDate.now(clock);
    final TargetIndex index = getTargetIndexForDate(today);
    if (index == null) {
      return;
    }

    final DailyTradeRecord record = stateRepository.load(today).orElse(null);
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

    final double spot = fetchSpotPrice(index);
    final int atmStrike = resolver.computeAtmStrike(index, spot);
    final int qty = props.lots() * index.lotSize();
    final Exchange derivExchange =
        index.spotExchange() == Exchange.NSE ? Exchange.NFO : Exchange.BFO;

    final String ceSymbol = index.name() + "_ATM_CE_" + atmStrike;
    final String peSymbol = index.name() + "_ATM_PE_" + atmStrike;
    final InstrumentToken ceToken = new InstrumentToken(atmStrike);
    final InstrumentToken peToken = new InstrumentToken(atmStrike);

    final BigDecimal ceFill = gateway.executeSellMarket(ceSymbol, ceToken, derivExchange, qty);
    final BigDecimal peFill = gateway.executeSellMarket(peSymbol, peToken, derivExchange, qty);

    final double slMultiplier = 1.0 + (props.slPercentage() / 100.0);
    final BigDecimal ceSl = roundToTick(ceFill.multiply(BigDecimal.valueOf(slMultiplier)));
    final BigDecimal ceSlLimit = roundToTick(ceSl.multiply(BigDecimal.valueOf(1.05)));

    final BigDecimal peSl = roundToTick(peFill.multiply(BigDecimal.valueOf(slMultiplier)));
    final BigDecimal peSlLimit = roundToTick(peSl.multiply(BigDecimal.valueOf(1.05)));

    // Immediately place exchange-level Stop-Loss orders with Kite at ~10:00 AM
    final OrderId ceSlOrderId =
        gateway.placeStopLossBuy(ceSymbol, ceToken, derivExchange, qty, ceSl, ceSlLimit);
    final OrderId peSlOrderId =
        gateway.placeStopLossBuy(peSymbol, peToken, derivExchange, qty, peSl, peSlLimit);

    final TradeLeg sCe =
        new TradeLeg(ceSymbol, ceToken, qty, ceFill, BigDecimal.ZERO, ceSl, ceSlOrderId, true);
    final TradeLeg sPe =
        new TradeLeg(peSymbol, peToken, qty, peFill, BigDecimal.ZERO, peSl, peSlOrderId, true);

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
    stateRepository.save(updated);

    alertService.notifyStraddleExecution(updated, spot, props.executionMode());
  }

  /** Checkpoint 3: Continuous 1-second Risk & Stop-Loss Monitoring Loop. */
  @Scheduled(fixedDelay = 1000)
  public void checkRisk() {
    final LocalDate today = LocalDate.now(clock);
    final DailyTradeRecord record = stateRepository.load(today).orElse(null);
    if (record != null
        && (record.state() == StrategyState.STRADDLE_ACTIVE
            || record.state() == StrategyState.ONE_LEG_STOPPED)) {
      riskManager.evaluateRisk(record);
    }
  }

  /** Checkpoint 4: 15:00 PM IST - Mandatory EOD Square-Off of all active legs. */
  @Scheduled(cron = "0 0 15 * * MON-THU", zone = KOLKATA_ZONE)
  public void executeSquareOff() {
    final LocalDate today = LocalDate.now(clock);
    final DailyTradeRecord record = stateRepository.load(today).orElse(null);
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
    stateRepository.save(completed);

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

  private static BigDecimal roundToTick(BigDecimal price) {
    if (price == null) {
      return BigDecimal.ZERO;
    }
    return price
        .multiply(BigDecimal.valueOf(20))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(20), 2, java.math.RoundingMode.HALF_UP);
  }

  private double fetchSpotPrice(TargetIndex index) {
    try {
      final QuoteRef ref =
          new QuoteRef(index.spotExchange(), index.spotSymbol(), new InstrumentToken(256265L));
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
}
