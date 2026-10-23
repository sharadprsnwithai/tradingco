package com.trading.bot.strategy.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.broker.api.model.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class ExecutionGatewayTest {

  private Quote makeQuote(InstrumentToken token, String symbol, BigDecimal price) {
    return new Quote(
        token,
        symbol,
        price,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        1000L);
  }

  @Test
  void liveGatewayPlacesRealMarketOrdersThroughBroker() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = new LiveStrategyGateway(broker);

    final OrderId orderId = new OrderId("order-101");
    when(broker.placeOrder(any())).thenReturn(orderId);

    final com.trading.bot.broker.api.model.Order filledOrder =
        new com.trading.bot.broker.api.model.Order(
            orderId,
            java.util.Optional.empty(),
            Exchange.NFO,
            "NIFTY26OCT25000CE",
            TransactionType.BUY,
            OrderType.MARKET,
            com.trading.bot.broker.api.model.Product.MIS,
            com.trading.bot.broker.api.model.Validity.DAY,
            com.trading.bot.broker.api.model.OrderStatus.FILLED,
            "COMPLETE",
            130,
            130,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(120.50),
            "",
            java.time.Instant.now());
    when(broker.orderBook()).thenReturn(List.of(filledOrder));

    final InstrumentToken token = new InstrumentToken(256265L);
    final BigDecimal price =
        gateway.executeBuyMarket("NIFTY26OCT25000CE", token, Exchange.NFO, 130);

    final ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
    verify(broker).placeOrder(captor.capture());

    final OrderRequest req = captor.getValue();
    assertThat(req.tradingsymbol()).isEqualTo("NIFTY26OCT25000CE");
    assertThat(req.transactionType()).isEqualTo(TransactionType.BUY);
    assertThat(req.orderType()).isEqualTo(OrderType.MARKET);
    assertThat(req.quantity()).isEqualTo(130);

    // Live gateway extracts execution averagePrice directly from the order book
    assertThat(price).isEqualByComparingTo(BigDecimal.valueOf(120.50));
  }

  @Test
  void paperGatewaySimulatesFillsUsingQuotesAndNeverCallsPlaceOrder() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = new PaperStrategyGateway(broker);

    final InstrumentToken token = new InstrumentToken(356265L);
    final QuoteRef ref = new QuoteRef(Exchange.NFO, "NIFTY26OCT25000PE", token);
    when(broker.quotes(List.of(ref)))
        .thenReturn(
            Map.of(token, makeQuote(token, "NIFTY26OCT25000PE", BigDecimal.valueOf(115.50))));

    final BigDecimal fillPrice =
        gateway.executeSellMarket("NIFTY26OCT25000PE", token, Exchange.NFO, 130);

    assertThat(fillPrice).isEqualByComparingTo(BigDecimal.valueOf(115.50));
    verify(broker, never()).placeOrder(any());
  }
}
