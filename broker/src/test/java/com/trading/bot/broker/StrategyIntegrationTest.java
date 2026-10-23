package com.trading.bot.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.engine.IntradayOptionStrategy;
import com.trading.bot.strategy.gateway.PaperStrategyGateway;
import com.trading.bot.strategy.gateway.StrategyExecutionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class StrategyIntegrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TradingBotApplication.class);

  @Test
  void applicationLoadsStrategyPropertiesAndWiresPaperGatewayByDefault() {
    runner
        .withPropertyValues(
            "kite.api-key=test-key",
            "kite.user-id=USR123",
            "kite.password=secret-pass",
            "kite.totp-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            "telegram.bot-token=123:ABC",
            "telegram.chat-id=456")
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              final StrategyProperties props = context.getBean(StrategyProperties.class);
              assertThat(props.executionMode()).isEqualTo(ExecutionMode.PAPER);
              assertThat(props.lots()).isEqualTo(2);

              final StrategyExecutionGateway gateway =
                  context.getBean(StrategyExecutionGateway.class);
              assertThat(gateway).isInstanceOf(PaperStrategyGateway.class);

              final IntradayOptionStrategy strategy = context.getBean(IntradayOptionStrategy.class);
              assertThat(strategy).isNotNull();
            });
  }
}
