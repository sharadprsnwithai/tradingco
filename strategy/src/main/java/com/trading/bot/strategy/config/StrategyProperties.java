package com.trading.bot.strategy.config;

import jakarta.validation.constraints.Positive;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration properties for the intraday option selling strategy.
 *
 * @param executionMode PAPER or LIVE mode
 * @param lots number of lots per trade leg
 * @param slPercentage stop-loss percentage on entry premium
 * @param trailToBreakeven whether to trail the surviving leg's SL to entry price
 * @param maxVixThreshold maximum India VIX threshold to permit trading (default 24.0)
 * @param maxSpreadPercentage maximum allowable bid-ask spread percentage on entry (default 1.5)
 * @param profitTargetPercentage percentage decay target to book early profit (default 85.0)
 * @param hedgeTime time to execute long hedge purchases (default 09:45)
 * @param entryTime time to execute short straddle sell (default 10:00)
 * @param exitTime time to square-off all active legs (default 15:00)
 * @param niftyHedgeDistance points offset for Nifty OTM hedges (default 1000)
 * @param sensexHedgeDistance points offset for Sensex OTM hedges (default 2000)
 */
@ConfigurationProperties("strategy")
@Validated
public record StrategyProperties(
    ExecutionMode executionMode,
    @Positive(message = "strategy.lots must be positive") int lots,
    @Positive(message = "strategy.sl-percentage must be positive") double slPercentage,
    Boolean trailToBreakeven,
    double maxVixThreshold,
    double maxSpreadPercentage,
    double profitTargetPercentage,
    LocalTime hedgeTime,
    LocalTime entryTime,
    LocalTime exitTime,
    @Positive(message = "strategy.nifty-hedge-distance must be positive") int niftyHedgeDistance,
    @Positive(message = "strategy.sensex-hedge-distance must be positive")
        int sensexHedgeDistance) {

  private static final ExecutionMode DEFAULT_EXECUTION_MODE = ExecutionMode.PAPER;
  private static final int DEFAULT_LOTS = 2;
  private static final double DEFAULT_SL_PERCENTAGE = 30.0;
  private static final boolean DEFAULT_TRAIL_BREAKEVEN = true;
  private static final double DEFAULT_MAX_VIX = 24.0;
  private static final double DEFAULT_MAX_SPREAD = 1.5;
  private static final double DEFAULT_PROFIT_TARGET = 85.0;
  private static final LocalTime DEFAULT_HEDGE_TIME = LocalTime.of(9, 45);
  private static final LocalTime DEFAULT_ENTRY_TIME = LocalTime.of(10, 0);
  private static final LocalTime DEFAULT_EXIT_TIME = LocalTime.of(15, 0);
  private static final int DEFAULT_NIFTY_HEDGE_DISTANCE = 1000;
  private static final int DEFAULT_SENSEX_HEDGE_DISTANCE = 2000;

  @ConstructorBinding
  public StrategyProperties {
    if (executionMode == null) {
      executionMode = DEFAULT_EXECUTION_MODE;
    }
    if (lots <= 0) {
      lots = DEFAULT_LOTS;
    }
    if (slPercentage <= 0) {
      slPercentage = DEFAULT_SL_PERCENTAGE;
    }
    if (trailToBreakeven == null) {
      trailToBreakeven = DEFAULT_TRAIL_BREAKEVEN;
    }
    if (maxVixThreshold <= 0) {
      maxVixThreshold = DEFAULT_MAX_VIX;
    }
    if (maxSpreadPercentage <= 0) {
      maxSpreadPercentage = DEFAULT_MAX_SPREAD;
    }
    if (profitTargetPercentage <= 0) {
      profitTargetPercentage = DEFAULT_PROFIT_TARGET;
    }
    if (hedgeTime == null) {
      hedgeTime = DEFAULT_HEDGE_TIME;
    }
    if (entryTime == null) {
      entryTime = DEFAULT_ENTRY_TIME;
    }
    if (exitTime == null) {
      exitTime = DEFAULT_EXIT_TIME;
    }
    if (niftyHedgeDistance <= 0) {
      niftyHedgeDistance = DEFAULT_NIFTY_HEDGE_DISTANCE;
    }
    if (sensexHedgeDistance <= 0) {
      sensexHedgeDistance = DEFAULT_SENSEX_HEDGE_DISTANCE;
    }
  }

  public StrategyProperties(
      ExecutionMode executionMode,
      int lots,
      double slPercentage,
      boolean trailToBreakeven,
      LocalTime hedgeTime,
      LocalTime entryTime,
      LocalTime exitTime,
      int niftyHedgeDistance,
      int sensexHedgeDistance) {
    this(
        executionMode,
        lots,
        slPercentage,
        trailToBreakeven,
        DEFAULT_MAX_VIX,
        DEFAULT_MAX_SPREAD,
        DEFAULT_PROFIT_TARGET,
        hedgeTime,
        entryTime,
        exitTime,
        niftyHedgeDistance,
        sensexHedgeDistance);
  }
}
