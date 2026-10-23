package com.trading.bot.notifications.internal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Telegram notification delivery.
 *
 * @param botToken Telegram bot API token (must not be blank)
 * @param chatId target chat identifier (must not be blank)
 */
@ConfigurationProperties("telegram")
@Validated
public record TelegramProperties(
    @NotBlank(message = "TELEGRAM_BOT_TOKEN must not be blank") String botToken,
    @NotBlank(message = "TELEGRAM_CHAT_ID must not be blank") String chatId) {

  /** Compact constructor validating required non-blank values. */
  public TelegramProperties {
    if (botToken == null || botToken.isBlank()) {
      throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN must not be blank");
    }
    if (chatId == null || chatId.isBlank()) {
      throw new IllegalArgumentException("TELEGRAM_CHAT_ID must not be blank");
    }
  }
}
