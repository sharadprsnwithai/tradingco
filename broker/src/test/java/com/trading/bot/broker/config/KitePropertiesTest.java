package com.trading.bot.broker.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class KitePropertiesTest {

  private static final String CANARY_API_KEY = "canary-api-key-xyz-123";
  private static final String CANARY_USER_ID = "canary-user-id-abc-456";
  private static final String CANARY_PASSWORD = "canary-password-p@ssw0rd!";
  private static final String CANARY_TOTP = "JBSWY3DPEHPK3PXP";

  private ListAppender<ILoggingEvent> listAppender;
  private Logger rootLogger;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(KiteProperties.class)
  static class ConfigPropertiesHolder {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(ConfigPropertiesHolder.class);

  @BeforeEach
  void setUp() {
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(listAppender);
  }

  private ApplicationContextRunner withSecrets(
      String apiKey, String userId, String password, String totpSecret) {
    ApplicationContextRunner target = runner;
    if (apiKey != null) {
      target = target.withPropertyValues("kite.api-key=" + apiKey);
    }
    if (userId != null) {
      target = target.withPropertyValues("kite.user-id=" + userId);
    }
    if (password != null) {
      target = target.withPropertyValues("kite.password=" + password);
    }
    if (totpSecret != null) {
      target = target.withPropertyValues("kite.totp-secret=" + totpSecret);
    }
    return target;
  }

  @Test
  void missingApiKeyFailsStartupNamingKey() {
    withSecrets(null, CANARY_USER_ID, CANARY_PASSWORD, CANARY_TOTP)
        .run(
            context -> {
              assertThat(context).hasFailed();
              final Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              final StringBuilder sb = new StringBuilder();
              Throwable t = failure;
              while (t != null) {
                sb.append(t.getMessage()).append(' ');
                t = t.getCause();
              }
              final String msg = sb.toString();
              assertThat(msg).containsAnyOf("kite.api-key", "apiKey", "KITE_API_KEY");
            });
  }

  @Test
  void blankTotpSecretFailsStartup() {
    withSecrets(CANARY_API_KEY, CANARY_USER_ID, CANARY_PASSWORD, "   ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              final Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              final StringBuilder sb = new StringBuilder();
              Throwable t = failure;
              while (t != null) {
                sb.append(t.getMessage()).append(' ');
                t = t.getCause();
              }
              final String msg = sb.toString();
              assertThat(msg).containsAnyOf("kite.totp-secret", "totpSecret", "KITE_TOTP_SECRET");
            });
  }

  @Test
  void defaultsPresentWhenOnlySecretsProvided() {
    withSecrets(CANARY_API_KEY, CANARY_USER_ID, CANARY_PASSWORD, CANARY_TOTP)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final KiteProperties props = context.getBean(KiteProperties.class);

              assertThat(props.http().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(props.http().readTimeout()).isEqualTo(Duration.ofSeconds(10));
              assertThat(props.http().placeReadTimeout()).isEqualTo(Duration.ofSeconds(15));

              assertThat(props.auth().initialRetry()).isEqualTo(Duration.ofMinutes(1));
              assertThat(props.auth().maxRetry()).isEqualTo(Duration.ofMinutes(20));
              assertThat(props.auth().maxAttemptsPerHour()).isEqualTo(3);

              assertThat(props.orders().lostGrace()).isEqualTo(Duration.ofSeconds(30));
            });
  }

  @Test
  void secretsCanaryLogsContainNoneOfTheSecretValues() {
    withSecrets(CANARY_API_KEY, CANARY_USER_ID, CANARY_PASSWORD, CANARY_TOTP)
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              for (final ILoggingEvent event : listAppender.list) {
                final String msg = event.getFormattedMessage();
                assertThat(msg)
                    .doesNotContain(CANARY_API_KEY)
                    .doesNotContain(CANARY_USER_ID)
                    .doesNotContain(CANARY_PASSWORD)
                    .doesNotContain(CANARY_TOTP);
              }
            });
  }
}
