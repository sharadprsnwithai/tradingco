package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TickUtilsTest {

  @Test
  void nullPriceReturnsZero() {
    assertThat(TickUtils.roundToTick(null)).isEqualTo(BigDecimal.ZERO);
  }

  @Test
  void roundsToNearestFivePaise() {
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.02)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.00));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.03)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.05));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.07)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.05));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.08)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.10));
  }
}
