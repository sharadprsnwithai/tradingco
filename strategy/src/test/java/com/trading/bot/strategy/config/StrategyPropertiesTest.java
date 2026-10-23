package com.trading.bot.strategy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class StrategyPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(StrategyProperties.class)
  static class ConfigHolder {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(ConfigHolder.class);

  @Test
  void defaultsMatchSpecification() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          final StrategyProperties props = context.getBean(StrategyProperties.class);

          assertThat(props.executionMode()).isEqualTo(ExecutionMode.PAPER);
          assertThat(props.lots()).isEqualTo(2);
          assertThat(props.slPercentage()).isEqualTo(30.0);
          assertThat(props.trailToBreakeven()).isTrue();
          assertThat(props.hedgeTime()).isEqualTo(LocalTime.of(9, 45));
          assertThat(props.entryTime()).isEqualTo(LocalTime.of(10, 0));
          assertThat(props.exitTime()).isEqualTo(LocalTime.of(15, 0));
          assertThat(props.niftyHedgeDistance()).isEqualTo(1000);
          assertThat(props.sensexHedgeDistance()).isEqualTo(2000);
        });
  }

  @Test
  void customPropertiesOverrideDefaults() {
    runner
        .withPropertyValues(
            "strategy.execution-mode=LIVE",
            "strategy.lots=4",
            "strategy.sl-percentage=25.0",
            "strategy.trail-to-breakeven=false",
            "strategy.nifty-hedge-distance=1200",
            "strategy.sensex-hedge-distance=2500")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final StrategyProperties props = context.getBean(StrategyProperties.class);

              assertThat(props.executionMode()).isEqualTo(ExecutionMode.LIVE);
              assertThat(props.lots()).isEqualTo(4);
              assertThat(props.slPercentage()).isEqualTo(25.0);
              assertThat(props.trailToBreakeven()).isFalse();
              assertThat(props.niftyHedgeDistance()).isEqualTo(1200);
              assertThat(props.sensexHedgeDistance()).isEqualTo(2500);
            });
  }
}
