package com.paymentreminder.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram Bot API credentials and delivery tuning. The token and chat id come from the environment
 * and must never be logged. When they are empty, notifications are simply not delivered.
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        String botToken,
        String chatId,
        int maxAttempts,
        Duration retryDelay,
        Duration connectTimeout,
        Duration readTimeout,
        Duration pollingTimeout
) {

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }
}
