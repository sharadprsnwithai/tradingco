package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Simulates fills using real-time quotes without placing real capital at risk. */
@Component
public final class PaperStrategyGateway implements StrategyExecutionGateway {

  private final Broker broker;

  public PaperStrategyGateway(Broker broker) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
  }

  @Override
  public BigDecimal executeBuyMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    return sampleMarketPrice(symbol, token, exchange);
  }

  @Override
  public BigDecimal executeSellMarket(
      String symbol, InstrumentToken token, Exchange exchange, int quantity) {
    return sampleMarketPrice(symbol, token, exchange);
  }

  private BigDecimal sampleMarketPrice(String symbol, InstrumentToken token, Exchange exchange) {
    final Map<InstrumentToken, Quote> quotes =
        broker.quotes(List.of(new QuoteRef(exchange, symbol, token)));
    final Quote quote = quotes.get(token);
    if (quote == null || quote.lastPrice() == null) {
      return BigDecimal.ZERO;
    }
    return quote.lastPrice();
  }
}
