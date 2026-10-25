package com.trading.bot.strategy.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BacktestStrategyGatewayTest {

  @Test
  void simulatesFillsAndSlOrdersCorrectly() {
    final BacktestStrategyGateway gateway = new BacktestStrategyGateway();
    final InstrumentToken token = new InstrumentToken(101L);
    gateway.setCurrentPrice(token, BigDecimal.valueOf(150.50));

    final BigDecimal fill = gateway.executeBuyMarket("TEST", token, Exchange.NFO, 65);
    assertThat(fill).isEqualByComparingTo(BigDecimal.valueOf(150.50));

    final OrderId slId =
        gateway.placeStopLossBuy(
            "TEST", token, Exchange.NFO, 65, BigDecimal.valueOf(160.0), BigDecimal.valueOf(165.0));
    assertThat(slId).isNotNull();
    assertThat(gateway.getSlOrders()).hasSize(1);

    gateway.cancelOrder(slId);
    assertThat(gateway.getSlOrders()).isEmpty();
  }
}
