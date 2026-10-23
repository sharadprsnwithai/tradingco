package com.trading.bot.broker;

import com.trading.bot.broker.config.KiteProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Main Spring Boot application entry point for the Kite trading bot. */
@SpringBootApplication(scanBasePackages = {"com.trading.bot"})
@EnableConfigurationProperties(KiteProperties.class)
@EnableScheduling
public class TradingBotApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradingBotApplication.class, args);
  }
}
