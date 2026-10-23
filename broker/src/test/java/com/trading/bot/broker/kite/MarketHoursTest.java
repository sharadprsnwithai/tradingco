package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class MarketHoursTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Test
  void weekendDaysAreAlwaysClosed() {
    // Saturday 2026-10-24 11:00 IST
    final Instant sat = Instant.parse("2026-10-24T05:30:00Z");
    assertThat(MarketHours.isOpen(sat, IST)).isFalse();

    // Sunday 2026-10-25 12:00 IST (Review Focus 4)
    final Instant sun = Instant.parse("2026-10-25T06:30:00Z");
    assertThat(MarketHours.isOpen(sun, IST)).isFalse();
  }

  @Test
  void fridayBoundaryConditions() {
    // Friday 15:29:59 IST -> open
    final Instant friBeforeClose = Instant.parse("2026-10-23T09:59:59Z");
    assertThat(MarketHours.isOpen(friBeforeClose, IST)).isTrue();

    // Friday 15:30:00 IST -> closed
    final Instant friAtClose = Instant.parse("2026-10-23T10:00:00Z");
    assertThat(MarketHours.isOpen(friAtClose, IST)).isFalse();
  }

  @Test
  void mondayBoundaryConditions() {
    // Monday 09:14:59 IST -> closed
    final Instant monBeforeOpen = Instant.parse("2026-10-26T03:44:59Z");
    assertThat(MarketHours.isOpen(monBeforeOpen, IST)).isFalse();

    // Monday 09:15:00 IST -> open
    final Instant monAtOpen = Instant.parse("2026-10-26T03:45:00Z");
    assertThat(MarketHours.isOpen(monAtOpen, IST)).isTrue();
  }

  @Test
  void zoneIndependenceUsingUtcEquivalents() {
    // Wednesday 10:00 IST == 04:30 UTC
    final Instant wedOpen = Instant.parse("2026-10-21T04:30:00Z");
    assertThat(MarketHours.isOpen(wedOpen, IST)).isTrue();

    // Wednesday 20:00 IST == 14:30 UTC
    final Instant wedNight = Instant.parse("2026-10-21T14:30:00Z");
    assertThat(MarketHours.isOpen(wedNight, IST)).isFalse();
  }
}
