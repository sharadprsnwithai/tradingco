package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.strategy.model.StrikeSelection;
import com.trading.bot.strategy.model.TargetIndex;
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
}
