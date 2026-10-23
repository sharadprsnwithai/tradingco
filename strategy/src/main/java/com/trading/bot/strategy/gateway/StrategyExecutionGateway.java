package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
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
}
