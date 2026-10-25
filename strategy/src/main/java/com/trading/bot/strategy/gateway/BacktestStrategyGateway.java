package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Backtest strategy gateway simulating fills and orders using historical candle data. */
public final class BacktestStrategyGateway implements StrategyExecutionGateway {

  private final Map<InstrumentToken, BigDecimal> currentPrices = new HashMap<>();
  private final AtomicLong orderIdCounter = new AtomicLong(1000L);
  private final Map<OrderId, SimulatedSlOrder> slOrders = new HashMap<>();

  public void setCurrentPrice(InstrumentToken token, BigDecimal price) {
    if (token != null && price != null) {
      currentPrices.put(token, price);
    }
  }

  @Override
  public BigDecimal executeBuyMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    return currentPrices.getOrDefault(token, BigDecimal.valueOf(100.0));
  }

  @Override
  public BigDecimal executeSellMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    return currentPrices.getOrDefault(token, BigDecimal.valueOf(100.0));
  }

  @Override
  public OrderId placeStopLossBuy(
      String symbol,
      InstrumentToken token,
      Exchange exchange,
      int quantity,
      BigDecimal triggerPrice,
      BigDecimal limitPrice) {
    final OrderId id = new OrderId("bk-sl-" + orderIdCounter.incrementAndGet());
    slOrders.put(id, new SimulatedSlOrder(symbol, token, quantity, triggerPrice, limitPrice, true));
    return id;
  }

  @Override
  public void modifyStopLoss(
      OrderId orderId, int quantity, BigDecimal newTriggerPrice, BigDecimal newLimitPrice) {
    if (orderId != null && slOrders.containsKey(orderId)) {
      SimulatedSlOrder ord = slOrders.get(orderId);
      slOrders.put(
          orderId,
          new SimulatedSlOrder(
              ord.symbol(), ord.token(), quantity, newTriggerPrice, newLimitPrice, true));
    }
  }

  @Override
  public void cancelOrder(OrderId orderId) {
    if (orderId != null) {
      slOrders.remove(orderId);
    }
  }

  public Map<OrderId, SimulatedSlOrder> getSlOrders() {
    return slOrders;
  }

  public record SimulatedSlOrder(
      String symbol,
      InstrumentToken token,
      int quantity,
      BigDecimal triggerPrice,
      BigDecimal limitPrice,
      boolean isOpen) {}
}
