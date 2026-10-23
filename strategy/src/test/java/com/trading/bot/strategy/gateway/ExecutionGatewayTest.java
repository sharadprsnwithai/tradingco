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

    when(broker.placeOrder(any())).thenReturn(new OrderId("order-101"));

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

    // Live gateway returns estimated price or non-negative
    assertThat(price).isGreaterThanOrEqualTo(BigDecimal.ZERO);
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
