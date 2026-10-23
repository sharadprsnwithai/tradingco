package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import java.math.BigDecimal;

/**
 * Strategy execution port abstracting order execution between LIVE broker placement and PAPER
 * simulated fills.
 */
public interface StrategyExecutionGateway {

  /**
   * Executes a market buy order for the specified contract.
   *
   * @param symbol tradingsymbol
   * @param token instrument token
   * @param exchange exchange
   * @param quantity order quantity
   * @return execution fill price
   */
  BigDecimal executeBuyMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity);

  /**
   * Executes a market sell order for the specified contract.
   *
   * @param symbol tradingsymbol
   * @param token instrument token
   * @param exchange exchange
   * @param quantity order quantity
   * @return execution fill price
   */
  BigDecimal executeSellMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity);

  /**
   * Places an exchange-level stop-loss limit buy order.
   *
   * @param symbol tradingsymbol
   * @param token instrument token
   * @param exchange exchange
   * @param quantity order quantity
   * @param triggerPrice stop-loss trigger price
   * @param limitPrice maximum execution limit price
   * @return placed order ID
   */
  OrderId placeStopLossBuy(
      String symbol,
      InstrumentToken token,
      Exchange exchange,
      int quantity,
      BigDecimal triggerPrice,
      BigDecimal limitPrice);

  /**
   * Modifies an existing exchange stop-loss order.
   *
   * @param orderId order ID
   * @param quantity order quantity
   * @param newTriggerPrice updated trigger price
   * @param newLimitPrice updated limit price
   */
  void modifyStopLoss(
      OrderId orderId, int quantity, BigDecimal newTriggerPrice, BigDecimal newLimitPrice);

  /**
   * Cancels a pending exchange order.
   *
   * @param orderId order ID
   */
  void cancelOrder(OrderId orderId);
}
