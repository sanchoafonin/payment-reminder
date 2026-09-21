package com.paymentreminder.notification.telegram;

import java.util.Objects;

/**
 * A rendered screen: text plus an optional inline keyboard. Used both when sending a new message and
 * when editing the text of an existing one.
 */
public record TelegramScreen(String text, TelegramKeyboard keyboard) {

    public TelegramScreen {
        Objects.requireNonNull(text, "text is required");
    }

    public TelegramScreen(String text) {
        this(text, null);
    }
}
