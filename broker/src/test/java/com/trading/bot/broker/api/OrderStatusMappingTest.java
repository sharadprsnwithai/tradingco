package com.trading.bot.broker.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class OrderStatusMappingTest {

  @ParameterizedTest
  @CsvSource({
    "OPEN, OPEN",
    "TRIGGER PENDING, OPEN",
    "COMPLETE, FILLED",
    "CANCELLED, CANCELLED",
    "REJECTED, REJECTED",
    "EXPIRED, EXPIRED",
    "OPEN PENDING, PENDING",
    "PUT ORDER REQ RECEIVED, PENDING",
    "MODIFY REQ RECEIVED, PENDING",
    "CANCEL REQ RECEIVED, PENDING",
    "SOME NEW STATUS, UNKNOWN",
    "'', UNKNOWN"
  })
  void kiteStatusMapsToDomainStatus(String raw, OrderStatus expected) {
    assertThat(OrderStatus.fromKite(raw)).isEqualTo(expected);
  }

  @Test
  void terminalStatusesAreCorrectlyIdentified() {
    assertThat(OrderStatus.isTerminal(OrderStatus.FILLED)).isTrue();
    assertThat(OrderStatus.isTerminal(OrderStatus.CANCELLED)).isTrue();
    assertThat(OrderStatus.isTerminal(OrderStatus.REJECTED)).isTrue();
    assertThat(OrderStatus.isTerminal(OrderStatus.EXPIRED)).isTrue();

    assertThat(OrderStatus.isTerminal(OrderStatus.OPEN)).isFalse();
    assertThat(OrderStatus.isTerminal(OrderStatus.PENDING)).isFalse();
    assertThat(OrderStatus.isTerminal(OrderStatus.PARTIAL)).isFalse();
    assertThat(OrderStatus.isTerminal(OrderStatus.UNKNOWN)).isFalse();
  }
}
