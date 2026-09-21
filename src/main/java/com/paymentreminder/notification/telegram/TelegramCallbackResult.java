package com.paymentreminder.notification.telegram;

/**
 * The outcome of a callback query. {@code alert} forces a modal popup; {@code clearKeyboard} requests
 * best-effort removal of the message buttons; {@code screen} optionally replaces the message in place
 * (used for menu navigation).
 */
public record TelegramCallbackResult(String message, boolean alert, boolean clearKeyboard, TelegramScreen screen) {

    public TelegramCallbackResult(String message, boolean alert, boolean clearKeyboard) {
        this(message, alert, clearKeyboard, null);
    }

    public static TelegramCallbackResult info(String message) {
        return new TelegramCallbackResult(message, false, false);
    }

    public static TelegramCallbackResult alert(String message) {
        return new TelegramCallbackResult(message, true, false);
    }

    public static TelegramCallbackResult paid(String message) {
        return new TelegramCallbackResult(message, false, true);
    }

    public static TelegramCallbackResult screen(TelegramScreen screen) {
        return new TelegramCallbackResult("", false, false, screen);
    }
}
