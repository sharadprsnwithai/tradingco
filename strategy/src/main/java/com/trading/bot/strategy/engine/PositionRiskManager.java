package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
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
import com.trading.bot.strategy.model.TradeLeg;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Monitors live short leg premiums and executes stop-losses with breakeven trailing. */
@Component
public final class PositionRiskManager {

  private final StrategyExecutionGateway gateway;
  private final StrategyStateRepository stateRepository;
  private final Broker broker;
  private final NotificationPublisher publisher;
  private final StrategyProperties props;

  public PositionRiskManager(
      StrategyExecutionGateway gateway,
      StrategyStateRepository stateRepository,
      Broker broker,
      NotificationPublisher publisher,
      StrategyProperties props) {
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.stateRepository =
        Objects.requireNonNull(stateRepository, "stateRepository must not be null");
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.props = Objects.requireNonNull(props, "props must not be null");
  }

  /**
   * Evaluates active short legs against stop-loss and breakeven rules.
   *
   * @param record current daily trade record
   * @return updated daily trade record
   */
  public DailyTradeRecord evaluateRisk(DailyTradeRecord record) {
    if (record == null
        || (record.state() != StrategyState.STRADDLE_ACTIVE
            && record.state() != StrategyState.ONE_LEG_STOPPED)) {
      return record;
    }

    TradeLeg shortCe = record.shortCe();
    TradeLeg shortPe = record.shortPe();

    final Exchange derivExchange =
        record.index().spotExchange() == Exchange.NSE ? Exchange.NFO : Exchange.BFO;

    final List<QuoteRef> refs = new ArrayList<>();
    if (shortCe != null && shortCe.isOpen()) {
      refs.add(new QuoteRef(derivExchange, shortCe.symbol(), shortCe.token()));
    }
    if (shortPe != null && shortPe.isOpen()) {
      refs.add(new QuoteRef(derivExchange, shortPe.symbol(), shortPe.token()));
    }

    if (refs.isEmpty()) {
      return record;
    }

    final Map<InstrumentToken, Quote> quotes = broker.quotes(refs);

    // 1. Evaluate Call Leg SL
    if (shortCe != null && shortCe.isOpen()) {
      final Quote q = quotes.get(shortCe.token());
      if (q != null && q.lastPrice() != null && q.lastPrice().compareTo(shortCe.slPrice()) >= 0) {
        gateway.executeBuyMarket(
            shortCe.symbol(), shortCe.token(), derivExchange, shortCe.quantity());
        shortCe =
            new TradeLeg(
                shortCe.symbol(),
                shortCe.token(),
                shortCe.quantity(),
                shortCe.entryPrice(),
                shortCe.slPrice(),
                false);

        publisher.publish(
            Notification.of(
                Severity.WARN,
                NotificationType.SYSTEM_ALERT,
                "Stop-Loss Hit: " + shortCe.symbol(),
                "Call leg hit SL at " + q.lastPrice() + " (entry=" + shortCe.entryPrice() + ")"));

        if (Boolean.TRUE.equals(props.trailToBreakeven()) && shortPe != null && shortPe.isOpen()) {
          shortPe =
              new TradeLeg(
                  shortPe.symbol(),
                  shortPe.token(),
                  shortPe.quantity(),
                  shortPe.entryPrice(),
                  shortPe.entryPrice(),
                  true);

          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.SYSTEM_ALERT,
                  "Breakeven Trailed: " + shortPe.symbol(),
                  "Put leg SL moved to entry cost " + shortPe.entryPrice()));
        }
      }
    }

    // 2. Evaluate Put Leg SL
    if (shortPe != null && shortPe.isOpen()) {
      final Quote q = quotes.get(shortPe.token());
      if (q != null && q.lastPrice() != null && q.lastPrice().compareTo(shortPe.slPrice()) >= 0) {
        gateway.executeBuyMarket(
            shortPe.symbol(), shortPe.token(), derivExchange, shortPe.quantity());
        shortPe =
            new TradeLeg(
                shortPe.symbol(),
                shortPe.token(),
                shortPe.quantity(),
                shortPe.entryPrice(),
                shortPe.slPrice(),
                false);

        publisher.publish(
            Notification.of(
                Severity.WARN,
                NotificationType.SYSTEM_ALERT,
                "Stop-Loss Hit: " + shortPe.symbol(),
                "Put leg hit SL at " + q.lastPrice() + " (entry=" + shortPe.entryPrice() + ")"));

        if (Boolean.TRUE.equals(props.trailToBreakeven()) && shortCe != null && shortCe.isOpen()) {
          shortCe =
              new TradeLeg(
                  shortCe.symbol(),
                  shortCe.token(),
                  shortCe.quantity(),
                  shortCe.entryPrice(),
                  shortCe.entryPrice(),
                  true);

          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.SYSTEM_ALERT,
                  "Breakeven Trailed: " + shortCe.symbol(),
                  "Call leg SL moved to entry cost " + shortCe.entryPrice()));
        }
      }
    }

    final boolean ceOpen = shortCe != null && shortCe.isOpen();
    final boolean peOpen = shortPe != null && shortPe.isOpen();

    final StrategyState newState;
    if (!ceOpen || !peOpen) {
      newState = StrategyState.ONE_LEG_STOPPED;
    } else {
      newState = StrategyState.STRADDLE_ACTIVE;
    }

    final DailyTradeRecord updated =
        new DailyTradeRecord(
            record.date(),
            record.index(),
            newState,
            record.hedgeCe(),
            record.hedgePe(),
            shortCe,
            shortPe,
            record.realizedPnl());

    stateRepository.save(updated);
    return updated;
  }
}
