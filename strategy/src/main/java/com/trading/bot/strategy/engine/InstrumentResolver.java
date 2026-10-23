package com.trading.bot.strategy.engine;

import com.trading.bot.strategy.model.StrikeSelection;
import com.trading.bot.strategy.model.TargetIndex;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Calculates dynamic strike selections and contract offsets for NIFTY and SENSEX. */
@Component
public final class InstrumentResolver {

  /**
   * Computes the nearest ATM strike based on index step size with midpoint ceiling.
   *
   * @param index target index
   * @param spotPrice underlying spot price
   * @return rounded ATM strike
   */
  public int computeAtmStrike(TargetIndex index, double spotPrice) {
    Objects.requireNonNull(index, "index must not be null");
    final double step = index.strikeStep();
    return (int) (Math.round((spotPrice + 1e-9) / step) * step);
  }

  /**
   * Computes ATM strike and symmetric 20-strike OTM wings (+1000/-1000 Nifty, +2000/-2000 Sensex).
   *
   * @param index target index
   * @param spotPrice underlying spot price
   * @return StrikeSelection containing ATM and hedge strikes
   */
  public StrikeSelection selectStrikes(TargetIndex index, double spotPrice) {
    final int atm = computeAtmStrike(index, spotPrice);
    return new StrikeSelection(atm, atm + index.hedgeDistance(), atm - index.hedgeDistance());
  }
}
