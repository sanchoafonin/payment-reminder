package com.paymentreminder.notification.telegram;

/** A single update returned by Telegram, carrying a callback query and/or a text message. */
public record TelegramUpdate(long updateId, TelegramCallbackQuery callbackQuery, TelegramMessage message) {

    public TelegramUpdate(long updateId, TelegramCallbackQuery callbackQuery) {
        this(updateId, callbackQuery, null);
    }
}
