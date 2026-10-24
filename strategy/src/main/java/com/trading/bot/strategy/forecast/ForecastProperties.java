package com.trading.bot.strategy.forecast;

import java.nio.file.Path;

public record ForecastProperties(String apiUrl, Path cacheFile, boolean enabled) {}
