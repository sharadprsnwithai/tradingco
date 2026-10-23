package com.trading.bot.broker.kite;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Determines whether Indian capital markets are open for trading (09:15-15:30 IST, Mon-Fri). */
public final class MarketHours {

  private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
  private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

  private MarketHours() {}

  /**
   * Evaluates if trading market is open at the specified instant in the given zone.
   *
   * @param instant instant to check
   * @param zone market time zone
   * @return true if market is currently open
   */
  public static boolean isOpen(Instant instant, ZoneId zone) {
    Objects.requireNonNull(instant, "instant must not be null");
    Objects.requireNonNull(zone, "zone must not be null");

    final ZonedDateTime zdt = instant.atZone(zone);
    final DayOfWeek dow = zdt.getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return false;
    }

    final LocalTime time = zdt.toLocalTime();
    return !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
  }
}
