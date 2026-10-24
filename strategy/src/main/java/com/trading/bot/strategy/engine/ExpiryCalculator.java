package com.trading.bot.strategy.engine;

import com.trading.bot.strategy.model.TargetIndex;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/** Calculates current weekly option expiry dates for NIFTY and SENSEX. */
public final class ExpiryCalculator {

  private ExpiryCalculator() {}

  /**
   * Computes the nearest upcoming weekly expiry date for the given index.
   *
   * @param index target index
   * @param currentDate current trading date
   * @return weekly expiry date
   */
  public static LocalDate getWeeklyExpiry(TargetIndex index, LocalDate currentDate) {
    Objects.requireNonNull(index, "index must not be null");
    Objects.requireNonNull(currentDate, "currentDate must not be null");

    // Nifty expires on Tuesday, Sensex on Thursday (plus Friday Nifty 1-DTE targeting next Tuesday)
    final DayOfWeek targetExpiryDay =
        index == TargetIndex.NIFTY ? DayOfWeek.TUESDAY : DayOfWeek.THURSDAY;

    if (currentDate.getDayOfWeek().getValue() <= targetExpiryDay.getValue()) {
      return currentDate.with(TemporalAdjusters.nextOrSame(targetExpiryDay));
    }
    return currentDate.with(TemporalAdjusters.next(targetExpiryDay));
  }
}
