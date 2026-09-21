package com.paymentreminder.notification.telegram;

import java.util.List;
import java.util.Objects;

/** Inline keyboard rendered as rows of buttons under a Telegram message. */
public record TelegramKeyboard(List<List<TelegramButton>> rows) {

    public TelegramKeyboard {
        Objects.requireNonNull(rows, "rows is required");
        rows = rows.stream().map(List::copyOf).toList();
    }
}
