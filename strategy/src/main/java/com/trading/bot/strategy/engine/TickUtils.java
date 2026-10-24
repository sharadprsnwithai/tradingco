package com.trading.bot.strategy.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Utility for price tick alignment and rounding. */
public final class TickUtils {

  private static final BigDecimal TICKS_PER_UNIT = BigDecimal.valueOf(20);

  private TickUtils() {}

  /**
   * Rounds a price to the nearest exchange tick (0.05) using HALF_UP rounding.
   *
   * @param price input price
   * @return tick-rounded price or ZERO if null
   */
  public static BigDecimal roundToTick(BigDecimal price) {
    if (price == null) {
      return BigDecimal.ZERO;
    }
    return price
        .multiply(TICKS_PER_UNIT)
        .setScale(0, RoundingMode.HALF_UP)
        .divide(TICKS_PER_UNIT, 2, RoundingMode.HALF_UP);
  }
}
