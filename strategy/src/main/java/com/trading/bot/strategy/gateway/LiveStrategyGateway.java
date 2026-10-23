package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.BrokerException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderPatch;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes real trades on Zerodha Kite Connect via the Broker port. */
@Component("liveStrategyGateway")
@ConditionalOnProperty(name = "strategy.execution-mode", havingValue = "LIVE")
public final class LiveStrategyGateway implements StrategyExecutionGateway {

  private static final Logger LOG = LoggerFactory.getLogger(LiveStrategyGateway.class);
  private static final BigDecimal MIN_TICK_FALLBACK = BigDecimal.valueOf(0.05);

  private final Broker broker;

  public LiveStrategyGateway(Broker broker) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
  }

  @Override
  public BigDecimal executeBuyMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    final OrderRequest request =
        new OrderRequest(
            token,
            symbol,
            exchange,
            TransactionType.BUY,
            OrderType.MARKET,
            Product.MIS,
            Validity.DAY,
            quantity,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    final OrderId orderId = broker.placeOrder(request);
    return fetchExecutionPrice(orderId, symbol, token, exchange);
  }

  @Override
  public BigDecimal executeSellMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    final OrderRequest request =
        new OrderRequest(
            token,
            symbol,
            exchange,
            TransactionType.SELL,
            OrderType.MARKET,
            Product.MIS,
            Validity.DAY,
            quantity,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    final OrderId orderId = broker.placeOrder(request);
    return fetchExecutionPrice(orderId, symbol, token, exchange);
  }

  @Override
  public OrderId placeStopLossBuy(
      String symbol,
      InstrumentToken token,
      Exchange exchange,
      int quantity,
      BigDecimal triggerPrice,
      BigDecimal limitPrice) {
    final OrderRequest request =
        new OrderRequest(
            token,
            symbol,
            exchange,
            TransactionType.BUY,
            OrderType.SL,
            Product.MIS,
            Validity.DAY,
            quantity,
            Optional.of(limitPrice),
            Optional.of(triggerPrice),
            Optional.of("sl-" + (System.currentTimeMillis() % 1_000_000L)));

    return broker.placeOrder(request);
  }

  @Override
  public void modifyStopLoss(
      OrderId orderId, int quantity, BigDecimal newTriggerPrice, BigDecimal newLimitPrice) {
    if (orderId == null) {
      return;
    }
    final OrderPatch patch =
        new OrderPatch(
            OptionalInt.of(quantity), Optional.of(newLimitPrice), Optional.of(newTriggerPrice));
    broker.modifyOrder(orderId, patch);
  }

  @Override
  public void cancelOrder(OrderId orderId) {
    if (orderId == null) {
      return;
    }
    try {
      broker.cancelOrder(orderId);
    } catch (BrokerException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Notice cancelling stop-loss order {}: {}", orderId.value(), e.getMessage(), e);
      }
    }
  }

  private BigDecimal fetchExecutionPrice(
      OrderId orderId, String symbol, InstrumentToken token, Exchange exchange) {
    if (orderId != null) {
      for (int i = 0; i < 5; i++) {
        try {
          final List<Order> book = broker.orderBook();
          if (book != null) {
            for (final Order o : book) {
              if (orderId.equals(o.orderId())
                  && o.averagePrice() != null
                  && o.averagePrice().compareTo(BigDecimal.ZERO) > 0) {
                return o.averagePrice();
              }
            }
          }
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (BrokerException e) {
          if (LOG.isWarnEnabled()) {
            LOG.warn(
                "Notice fetching order book for fill price {}: {}",
                orderId.value(),
                e.getMessage());
          }
        }
      }
    }

    final BigDecimal estimated = fetchEstimatedPrice(symbol, token, exchange);
    if (estimated.compareTo(BigDecimal.ZERO) > 0) {
      return estimated;
    }
    return MIN_TICK_FALLBACK;
  }

  private BigDecimal fetchEstimatedPrice(String symbol, InstrumentToken token, Exchange exchange) {
    try {
      final Map<InstrumentToken, Quote> quotes =
          broker.quotes(List.of(new QuoteRef(exchange, symbol, token)));
      final Quote q = quotes.get(token);
      return q != null && q.lastPrice() != null ? q.lastPrice() : BigDecimal.ZERO;
    } catch (BrokerException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Failed fetching estimated execution price for {}: {}", symbol, e.getMessage(), e);
      }
      return BigDecimal.ZERO;
    }
  }
}
