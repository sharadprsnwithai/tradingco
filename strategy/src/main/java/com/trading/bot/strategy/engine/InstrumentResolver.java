package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.strategy.model.StrikeSelection;
import com.trading.bot.strategy.model.TargetIndex;
import java.time.LocalDate;
import java.util.Locale;
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

  /**
   * Formats standard Kite option tradingsymbol for the specified expiry, strike, and option type.
   *
   * @param index target index
   * @param expiry expiry date
   * @param strike option strike price
   * @param optionType CE or PE
   * @return formatted tradingsymbol string (e.g. NIFTY26OCT25000CE or NIFTY26O2925000CE)
   */
  public String formatOptionSymbol(
      TargetIndex index, LocalDate expiry, int strike, String optionType) {
    Objects.requireNonNull(index, "index must not be null");
    Objects.requireNonNull(expiry, "expiry must not be null");
    Objects.requireNonNull(optionType, "optionType must not be null");

    final int year = expiry.getYear() % 100;
    final int month = expiry.getMonthValue();
    final int day = expiry.getDayOfMonth();

    // If next week falls into next month, this expiry is the monthly contract
    final boolean isMonthly = expiry.plusDays(7).getMonthValue() != month;

    if (isMonthly) {
      final String monthCode = expiry.getMonth().name().substring(0, 3).toUpperCase(Locale.ROOT);
      return String.format(
          Locale.ROOT, "%s%02d%s%d%s", index.name(), year, monthCode, strike, optionType);
    }

    final char mChar =
        switch (month) {
          case 10 -> 'O';
          case 11 -> 'N';
          case 12 -> 'D';
          default -> Character.forDigit(month, 10);
        };
    return String.format(
        Locale.ROOT, "%s%02d%c%02d%d%s", index.name(), year, mChar, day, strike, optionType);
  }

  /**
   * Generates a unique, collision-free numeric InstrumentToken for an option contract.
   *
   * @param index target index
   * @param strike strike price
   * @param optionType CE or PE
   * @return unique InstrumentToken
   */
  public InstrumentToken createOptionToken(TargetIndex index, int strike, String optionType) {
    Objects.requireNonNull(index, "index must not be null");
    Objects.requireNonNull(optionType, "optionType must not be null");
    final long typeOffset = "CE".equalsIgnoreCase(optionType) ? 1L : 2L;
    final long value = (index.ordinal() * 10_000_000L) + (strike * 10L) + typeOffset;
    return new InstrumentToken(value);
  }
}
