package com.paymentreminder.notification.telegram;

import java.util.Objects;

/** The callback query payload extracted from a Telegram update. */
public record TelegramCallbackQuery(String id, String data, Long chatId, Integer messageId) {

    public TelegramCallbackQuery {
        Objects.requireNonNull(id, "id is required");
    }
}
