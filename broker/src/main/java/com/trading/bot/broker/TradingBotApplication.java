package com.trading.bot.broker;

import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.internal.PipelineProperties;
import com.trading.bot.notifications.internal.TelegramProperties;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/** Main Spring Boot application entry point for the Kite trading bot. */
@SpringBootApplication(scanBasePackages = {"com.trading.bot"})
@EnableConfigurationProperties({
  KiteProperties.class,
  PipelineProperties.class,
  TelegramProperties.class
})
@EnableScheduling
public class TradingBotApplication {

  @Bean
  public Clock clock() {
    return Clock.system(ZoneId.of("Asia/Kolkata"));
  }

  @Bean
  public RestClient.Builder restClientBuilder() {
    return RestClient.builder();
  }

  public static void main(String[] args) {
    SpringApplication.run(TradingBotApplication.class, args);
  }
}
