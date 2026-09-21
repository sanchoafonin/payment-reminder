package com.paymentreminder.notification.telegram;

import java.util.Optional;

/** Parsed {@code pay:<id>} / {@code snooze:<id>} callback data. */
public record TelegramCallbackData(Action action, long notificationId) {

    public enum Action {
        PAY("pay"),
        SNOOZE("snooze");

        private final String prefix;

        Action(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    public static Optional<TelegramCallbackData> parse(String data) {
        if (data == null || data.isBlank()) {
            return Optional.empty();
        }
        int separator = data.indexOf(':');
        if (separator <= 0 || separator == data.length() - 1) {
            return Optional.empty();
        }
        String prefix = data.substring(0, separator);
        Action action = switch (prefix) {
            case "pay" -> Action.PAY;
            case "snooze" -> Action.SNOOZE;
            default -> null;
        };
        if (action == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TelegramCallbackData(action, Long.parseLong(data.substring(separator + 1))));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
