package com.trading.bot.broker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class DomainModelTest {

  private static final String SYMBOL = "NIFTY50";

  @Test
  void limitOrderWithoutPriceThrowsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                new OrderRequest(
                    new InstrumentToken(256265L),
                    SYMBOL,
                    Exchange.NSE,
                    TransactionType.BUY,
                    OrderType.LIMIT,
                    Product.MIS,
                    Validity.DAY,
                    50,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LIMIT");
  }

  @Test
  void zeroQuantityThrowsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                new OrderRequest(
                    new InstrumentToken(256265L),
                    SYMBOL,
                    Exchange.NSE,
                    TransactionType.BUY,
                    OrderType.MARKET,
                    Product.MIS,
                    Validity.DAY,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void stoplossOrderWithoutTriggerPriceThrowsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                new OrderRequest(
                    new InstrumentToken(256265L),
                    SYMBOL,
                    Exchange.NSE,
                    TransactionType.BUY,
                    OrderType.SL,
                    Product.MIS,
                    Validity.DAY,
                    50,
                    Optional.of(BigDecimal.valueOf(100)),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trigger");
  }

  @Test
  void validLimitOrderSucceeds() {
    final OrderRequest req =
        new OrderRequest(
            new InstrumentToken(256265L),
            SYMBOL,
            Exchange.NSE,
            TransactionType.BUY,
            OrderType.LIMIT,
            Product.MIS,
            Validity.DAY,
            50,
            Optional.of(BigDecimal.valueOf(100.5)),
            Optional.empty(),
            Optional.of("tag-1"));

    assertThat(req.quantity()).isEqualTo(50);
    assertThat(req.price()).contains(BigDecimal.valueOf(100.5));
    assertThat(req.tag()).contains("tag-1");
  }

  @Test
  void orderTypeSlmToKiteMatchesWireRepresentation() {
    assertThat(OrderType.SL_M.toKite()).isEqualTo("SL M");
    assertThat(OrderType.MARKET.toKite()).isEqualTo("MARKET");
    assertThat(OrderType.LIMIT.toKite()).isEqualTo("LIMIT");
    assertThat(OrderType.SL.toKite()).isEqualTo("SL");
  }
}
