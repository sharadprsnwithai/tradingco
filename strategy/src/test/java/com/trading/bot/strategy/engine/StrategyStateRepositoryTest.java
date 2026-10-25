package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.TargetIndex;
import com.trading.bot.strategy.model.TradeLeg;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class StrategyStateRepositoryTest {

  @TempDir Path tempDir;
  private Path stateFile;
  private StrategyStateRepository repository;

  @BeforeEach
  void setUp() {
    stateFile = tempDir.resolve("strategy_state.json");
    repository = new StrategyStateRepository(stateFile);
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.deleteIfExists(stateFile);
  }

  @Test
  void saveAndLoadPreservesFullTradeRecord() {
    final LocalDate today = LocalDate.of(2026, 10, 26);
    final TradeLeg hCe =
        new TradeLeg(
            "NIFTY26OCT26000CE",
            new InstrumentToken(101L),
            130,
            BigDecimal.valueOf(3.5),
            BigDecimal.ZERO,
            true);
    final TradeLeg hPe =
        new TradeLeg(
            "NIFTY26OCT24000PE",
            new InstrumentToken(102L),
            130,
            BigDecimal.valueOf(4.2),
            BigDecimal.ZERO,
            true);
    final TradeLeg sCe =
        new TradeLeg(
            "NIFTY26OCT25000CE",
            new InstrumentToken(201L),
            130,
            BigDecimal.valueOf(120.0),
            BigDecimal.valueOf(156.0),
            true);
    final TradeLeg sPe =
        new TradeLeg(
            "NIFTY26OCT25000PE",
            new InstrumentToken(202L),
            130,
            BigDecimal.valueOf(110.0),
            BigDecimal.valueOf(143.0),
            true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            today,
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            hCe,
            hPe,
            sCe,
            sPe,
            BigDecimal.ZERO);

    repository.save(record);

    final Optional<DailyTradeRecord> loaded = repository.load(today);
    assertThat(loaded).isPresent();
    final DailyTradeRecord actual = loaded.get();

    assertThat(actual.date()).isEqualTo(today);
    assertThat(actual.index()).isEqualTo(TargetIndex.NIFTY);
    assertThat(actual.state()).isEqualTo(StrategyState.STRADDLE_ACTIVE);

    assertThat(actual.shortCe().symbol()).isEqualTo("NIFTY26OCT25000CE");
    assertThat(actual.shortCe().entryPrice()).isEqualByComparingTo(BigDecimal.valueOf(120.0));
    assertThat(actual.shortCe().slPrice()).isEqualByComparingTo(BigDecimal.valueOf(156.0));

    assertThat(actual.shortPe().symbol()).isEqualTo("NIFTY26OCT25000PE");
    assertThat(actual.shortPe().entryPrice()).isEqualByComparingTo(BigDecimal.valueOf(110.0));
  }

  @Test
  void loadReturnsEmptyWhenDateDoesNotMatchToday() {
    final LocalDate yesterday = LocalDate.of(2026, 10, 25);
    final LocalDate today = LocalDate.of(2026, 10, 26);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            yesterday,
            TargetIndex.NIFTY,
            StrategyState.COMPLETED,
            null,
            null,
            null,
            null,
            BigDecimal.valueOf(1500));

    repository.save(record);

    final Optional<DailyTradeRecord> loaded = repository.load(today);
    assertThat(loaded).isEmpty();
  }

  @Test
  void loadThrowsExceptionWhenFileIsCorrupted() throws IOException {
    Files.writeString(stateFile, "{invalid-json}");
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> repository.load(LocalDate.of(2026, 10, 26)));
  }
}
