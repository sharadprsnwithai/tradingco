package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.BrokerException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.StrategyExecutionGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.TradeLeg;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Monitors live short leg premiums and reconciles exchange stop-loss execution with breakeven
 * trailing.
 */
@Component
public final class PositionRiskManager {

  private static final Logger LOG = LoggerFactory.getLogger(PositionRiskManager.class);

  private final StrategyExecutionGateway gateway;
  private final StrategyStateRepository stateRepository;
  private final Broker broker;
  private final StrategyAlertService alertService;
  private final StrategyProperties props;

  public PositionRiskManager(
      StrategyExecutionGateway gateway,
      StrategyStateRepository stateRepository,
      Broker broker,
      StrategyAlertService alertService,
      StrategyProperties props) {
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.stateRepository =
        Objects.requireNonNull(stateRepository, "stateRepository must not be null");
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
    this.props = Objects.requireNonNull(props, "props must not be null");
  }

  /**
   * Evaluates active short legs against exchange SL order execution and live quote limits.
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

    // 1. Fetch live order book to detect exchange-level SL order fills
    Map<String, Order> orderBookMap = Map.of();
    try {
      final List<Order> book = broker.orderBook();
      if (book != null && !book.isEmpty()) {
        orderBookMap =
            book.stream()
                .filter(o -> o.orderId() != null)
                .collect(Collectors.toMap(o -> o.orderId().value(), o -> o, (a, b) -> a));
      }
    } catch (BrokerException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Notice querying order book for SL reconciliation: {}", e.getMessage(), e);
      }
    }

    final Map<InstrumentToken, Quote> quotes = broker.quotes(refs);

    // 2. Evaluate Call Leg SL (Exchange fill detection OR quote threshold breach)
    if (shortCe != null && shortCe.isOpen()) {
      boolean ceFilledAtExchange = false;
      BigDecimal ceExchangePrice = BigDecimal.ZERO;

      if (shortCe.slOrderId() != null && orderBookMap.containsKey(shortCe.slOrderId().value())) {
        final Order slOrder = orderBookMap.get(shortCe.slOrderId().value());
        if (slOrder.status() == OrderStatus.FILLED) {
          ceFilledAtExchange = true;
          ceExchangePrice =
              slOrder.averagePrice().compareTo(BigDecimal.ZERO) > 0
                  ? slOrder.averagePrice()
                  : shortCe.slPrice();
        }
      }

      final Quote q = quotes.get(shortCe.token());
      final boolean quoteTriggered =
          q != null && q.lastPrice() != null && q.lastPrice().compareTo(shortCe.slPrice()) >= 0;

      if (ceFilledAtExchange || quoteTriggered) {
        if (!ceFilledAtExchange && shortCe.slOrderId() != null) {
          // Cancel resting exchange SL order to prevent position flipping
          gateway.cancelOrder(shortCe.slOrderId());
        }

        final BigDecimal exitPrice =
            ceFilledAtExchange
                ? ceExchangePrice
                : gateway.executeBuyMarket(
                    shortCe.symbol(), shortCe.token(), derivExchange, shortCe.quantity());

        final boolean isBreakevenExit = shortCe.slPrice().compareTo(shortCe.entryPrice()) == 0;

        shortCe =
            new TradeLeg(
                shortCe.symbol(),
                shortCe.token(),
                shortCe.quantity(),
                shortCe.entryPrice(),
                exitPrice,
                shortCe.slPrice(),
                null,
                false);

        if (isBreakevenExit) {
          alertService.notifyBreakevenHit(shortCe, exitPrice, props.executionMode());
        } else {
          if (Boolean.TRUE.equals(props.trailToBreakeven())
              && shortPe != null
              && shortPe.isOpen()) {
            final Quote peQuote = quotes.get(shortPe.token());
            final BigDecimal peLtp =
                peQuote != null && peQuote.lastPrice() != null
                    ? peQuote.lastPrice()
                    : BigDecimal.ZERO;

            if (peLtp.compareTo(shortPe.entryPrice()) >= 0) {
              // Exchange rule: LTP >= TriggerPrice -> Emergency exit at market
              if (shortPe.slOrderId() != null) {
                gateway.cancelOrder(shortPe.slOrderId());
              }
              final BigDecimal peExit =
                  gateway.executeBuyMarket(
                      shortPe.symbol(), shortPe.token(), derivExchange, shortPe.quantity());
              shortPe =
                  new TradeLeg(
                      shortPe.symbol(),
                      shortPe.token(),
                      shortPe.quantity(),
                      shortPe.entryPrice(),
                      peExit,
                      shortPe.entryPrice(),
                      null,
                      false);
              alertService.notifyBreakevenHit(shortPe, peExit, props.executionMode());
            } else {
              if (shortPe.slOrderId() != null) {
                final BigDecimal peLimit =
                    roundToTick(shortPe.entryPrice().multiply(BigDecimal.valueOf(1.05)));
                gateway.modifyStopLoss(
                    shortPe.slOrderId(), shortPe.quantity(), shortPe.entryPrice(), peLimit);
              }
              shortPe =
                  new TradeLeg(
                      shortPe.symbol(),
                      shortPe.token(),
                      shortPe.quantity(),
                      shortPe.entryPrice(),
                      shortPe.exitPrice(),
                      shortPe.entryPrice(),
                      shortPe.slOrderId(),
                      true);
            }
          }
          alertService.notifyStopLossHit(shortCe, exitPrice, shortPe, props.executionMode());
        }
      }
    }

    // 3. Evaluate Put Leg SL (Exchange fill detection OR quote threshold breach)
    if (shortPe != null && shortPe.isOpen()) {
      boolean peFilledAtExchange = false;
      BigDecimal peExchangePrice = BigDecimal.ZERO;

      if (shortPe.slOrderId() != null && orderBookMap.containsKey(shortPe.slOrderId().value())) {
        final Order slOrder = orderBookMap.get(shortPe.slOrderId().value());
        if (slOrder.status() == OrderStatus.FILLED) {
          peFilledAtExchange = true;
          peExchangePrice =
              slOrder.averagePrice().compareTo(BigDecimal.ZERO) > 0
                  ? slOrder.averagePrice()
                  : shortPe.slPrice();
        }
      }

      final Quote q = quotes.get(shortPe.token());
      final boolean quoteTriggered =
          q != null && q.lastPrice() != null && q.lastPrice().compareTo(shortPe.slPrice()) >= 0;

      if (peFilledAtExchange || quoteTriggered) {
        if (!peFilledAtExchange && shortPe.slOrderId() != null) {
          // Cancel resting exchange SL order to prevent position flipping
          gateway.cancelOrder(shortPe.slOrderId());
        }

        final BigDecimal exitPrice =
            peFilledAtExchange
                ? peExchangePrice
                : gateway.executeBuyMarket(
                    shortPe.symbol(), shortPe.token(), derivExchange, shortPe.quantity());

        final boolean isBreakevenExit = shortPe.slPrice().compareTo(shortPe.entryPrice()) == 0;

        shortPe =
            new TradeLeg(
                shortPe.symbol(),
                shortPe.token(),
                shortPe.quantity(),
                shortPe.entryPrice(),
                exitPrice,
                shortPe.slPrice(),
                null,
                false);

        if (isBreakevenExit) {
          alertService.notifyBreakevenHit(shortPe, exitPrice, props.executionMode());
        } else {
          if (Boolean.TRUE.equals(props.trailToBreakeven())
              && shortCe != null
              && shortCe.isOpen()) {
            final Quote ceQuote = quotes.get(shortCe.token());
            final BigDecimal ceLtp =
                ceQuote != null && ceQuote.lastPrice() != null
                    ? ceQuote.lastPrice()
                    : BigDecimal.ZERO;

            if (ceLtp.compareTo(shortCe.entryPrice()) >= 0) {
              // Exchange rule: LTP >= TriggerPrice -> Emergency exit at market
              if (shortCe.slOrderId() != null) {
                gateway.cancelOrder(shortCe.slOrderId());
              }
              final BigDecimal ceExit =
                  gateway.executeBuyMarket(
                      shortCe.symbol(), shortCe.token(), derivExchange, shortCe.quantity());
              shortCe =
                  new TradeLeg(
                      shortCe.symbol(),
                      shortCe.token(),
                      shortCe.quantity(),
                      shortCe.entryPrice(),
                      ceExit,
                      shortCe.entryPrice(),
                      null,
                      false);
              alertService.notifyBreakevenHit(shortCe, ceExit, props.executionMode());
            } else {
              if (shortCe.slOrderId() != null) {
                final BigDecimal ceLimit =
                    roundToTick(shortCe.entryPrice().multiply(BigDecimal.valueOf(1.05)));
                gateway.modifyStopLoss(
                    shortCe.slOrderId(), shortCe.quantity(), shortCe.entryPrice(), ceLimit);
              }
              shortCe =
                  new TradeLeg(
                      shortCe.symbol(),
                      shortCe.token(),
                      shortCe.quantity(),
                      shortCe.entryPrice(),
                      shortCe.exitPrice(),
                      shortCe.entryPrice(),
                      shortCe.slOrderId(),
                      true);
            }
          }
          alertService.notifyStopLossHit(shortPe, exitPrice, shortCe, props.executionMode());
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

  private static BigDecimal roundToTick(BigDecimal price) {
    if (price == null) {
      return BigDecimal.ZERO;
    }
    return price
        .multiply(BigDecimal.valueOf(20))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(20), 2, java.math.RoundingMode.HALF_UP);
  }
}
