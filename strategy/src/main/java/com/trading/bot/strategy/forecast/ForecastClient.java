package com.trading.bot.strategy.forecast;

import com.trading.bot.strategy.model.ForecastRange;
import java.util.Optional;

/** Forecast port — separate from intraday engine; optional enhancement. */
public interface ForecastClient {
  Optional<ForecastRange> getRange(String symbol);
  boolean refresh();
  boolean sendTelegramAlert(); // uses existing NotificationPublisher
}
