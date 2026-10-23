package com.trading.bot.strategy.engine;

import com.trading.bot.strategy.model.DailyTradeRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Persists and restores the daily strategy trade state from disk for crash resilience. */
@Component
public final class StrategyStateRepository {

  private static final Logger LOG = LoggerFactory.getLogger(StrategyStateRepository.class);

  private final Path stateFilePath;
  private final ObjectMapper objectMapper;

  public StrategyStateRepository() {
    this(Path.of("data/strategy_state.json"));
  }

  public StrategyStateRepository(
      @Value("${strategy.state-file:data/strategy_state.json}") Path stateFilePath) {
    this.stateFilePath = Objects.requireNonNull(stateFilePath, "stateFilePath must not be null");
    this.objectMapper = JsonMapper.builder().build();
  }

  /**
   * Persists the given daily trade record to disk.
   *
   * @param record daily trade state record
   */
  public synchronized void save(DailyTradeRecord record) {
    Objects.requireNonNull(record, "record must not be null");
    try {
      final Path parent = stateFilePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      final Path fileName = stateFilePath.getFileName();
      final String name = fileName != null ? fileName.toString() : "strategy_state.json";
      final Path tempFile = stateFilePath.resolveSibling(name + ".tmp");
      final String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
      Files.writeString(tempFile, json);
      Files.move(
          tempFile,
          stateFilePath,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed persisting strategy state to {}: {}", stateFilePath, e.getMessage(), e);
      }
    }
  }

  /**
   * Loads today's trade record if present on disk and matches target date.
   *
   * @param date today's date
   * @return optional loaded record
   */
  public synchronized Optional<DailyTradeRecord> load(LocalDate date) {
    Objects.requireNonNull(date, "date must not be null");
    if (!Files.exists(stateFilePath)) {
      return Optional.empty();
    }
    try {
      final String json = Files.readString(stateFilePath);
      if (json.isBlank()) {
        return Optional.empty();
      }
      final DailyTradeRecord record = objectMapper.readValue(json, DailyTradeRecord.class);
      if (record != null && date.equals(record.date())) {
        return Optional.of(record);
      }
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Failed reading strategy state from {}: {}", stateFilePath, e.getMessage());
      }
    }
    return Optional.empty();
  }

  /** Clears the state file. */
  public synchronized void clear() {
    try {
      Files.deleteIfExists(stateFilePath);
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Failed deleting strategy state file {}: {}", stateFilePath, e.getMessage());
      }
    }
  }
}
