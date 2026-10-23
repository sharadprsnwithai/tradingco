package com.trading.bot.strategy.gateway;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.BrokerException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes real trades on Zerodha Kite Connect via the Broker port. */
@Component("liveStrategyGateway")
@ConditionalOnProperty(name = "strategy.execution-mode", havingValue = "LIVE")
public final class LiveStrategyGateway implements StrategyExecutionGateway {

  private static final Logger LOG = LoggerFactory.getLogger(LiveStrategyGateway.class);

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

    broker.placeOrder(request);
    return fetchEstimatedPrice(symbol, token, exchange);
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

    broker.placeOrder(request);
    return fetchEstimatedPrice(symbol, token, exchange);
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
