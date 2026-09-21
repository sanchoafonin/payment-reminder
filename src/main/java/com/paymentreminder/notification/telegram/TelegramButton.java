package com.paymentreminder.notification.telegram;

import java.util.Objects;

/** Inline keyboard button carrying the callback data answered by the bot. */
public record TelegramButton(String text, String callbackData) {

    public TelegramButton {
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(callbackData, "callbackData is required");
    }
}
