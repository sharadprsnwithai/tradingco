package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.strategy.model.StrikeSelection;
import com.trading.bot.strategy.model.TargetIndex;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class InstrumentResolverTest {

  private final InstrumentResolver resolver = new InstrumentResolver();

  @ParameterizedTest
  @CsvSource({
    "24924.0, 24900",
    "24926.0, 24950",
    "24975.0, 25000",
    "25000.0, 25000",
    "25024.9, 25000",
    "25025.0, 25050"
  })
  void niftyStrikeRoundingRoundsToNearestFiftyWithMidpointCeiling(double spot, int expectedAtm) {
    assertThat(resolver.computeAtmStrike(TargetIndex.NIFTY, spot)).isEqualTo(expectedAtm);
  }

  @ParameterizedTest
  @CsvSource({
    "80149.0, 80100",
    "80150.0, 80200",
    "80200.0, 80200",
    "80249.9, 80200",
    "80250.0, 80300"
  })
  void sensexStrikeRoundingRoundsToNearestHundredWithMidpointCeiling(double spot, int expectedAtm) {
    assertThat(resolver.computeAtmStrike(TargetIndex.SENSEX, spot)).isEqualTo(expectedAtm);
  }

  @Test
  void niftyHedgeDistanceIsExactlyPlusMinusOneThousandPoints() {
    final StrikeSelection selection = resolver.selectStrikes(TargetIndex.NIFTY, 25000.0);

    assertThat(selection.atmStrike()).isEqualTo(25000);
    assertThat(selection.otmCeStrike()).isEqualTo(26000);
    assertThat(selection.otmPeStrike()).isEqualTo(24000);
  }

  @Test
  void sensexHedgeDistanceIsExactlyPlusMinusTwoThousandPoints() {
    final StrikeSelection selection = resolver.selectStrikes(TargetIndex.SENSEX, 80000.0);

    assertThat(selection.atmStrike()).isEqualTo(80000);
    assertThat(selection.otmCeStrike()).isEqualTo(82000);
    assertThat(selection.otmPeStrike()).isEqualTo(78000);
  }

  @Test
  void weeklyExpiryCalculatorFindsUpcomingThursdayForNiftyAndFridayForSensex() {
    final LocalDate monday = LocalDate.of(2026, 10, 26);
    final LocalDate niftyExpiry = ExpiryCalculator.getWeeklyExpiry(TargetIndex.NIFTY, monday);
    assertThat(niftyExpiry).isEqualTo(LocalDate.of(2026, 10, 29)); // Thursday 29-Oct-2026

    final LocalDate sensexExpiry = ExpiryCalculator.getWeeklyExpiry(TargetIndex.SENSEX, monday);
    assertThat(sensexExpiry).isEqualTo(LocalDate.of(2026, 10, 30)); // Friday 30-Oct-2026
  }

  @Test
  void formatOptionSymbolProducesKiteDerivativeSymbols() {
    final LocalDate monthlyExpiry = LocalDate.of(2026, 10, 29);
    final String niftyMonthlySymbol =
        resolver.formatOptionSymbol(TargetIndex.NIFTY, monthlyExpiry, 25000, "CE");
    assertThat(niftyMonthlySymbol).isEqualTo("NIFTY26OCT25000CE");

    final LocalDate weeklyExpiry = LocalDate.of(2026, 10, 15);
    final String niftyWeeklySymbol =
        resolver.formatOptionSymbol(TargetIndex.NIFTY, weeklyExpiry, 25000, "PE");
    assertThat(niftyWeeklySymbol).isEqualTo("NIFTY26O1525000PE");
  }
}
