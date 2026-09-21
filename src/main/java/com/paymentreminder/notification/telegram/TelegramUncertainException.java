package com.paymentreminder.notification.telegram;

/**
 * A delivery attempt whose outcome cannot be established (for example a read timeout after the
 * request was sent). Such a notification is never retried automatically to avoid duplicates.
 */
public class TelegramUncertainException extends RuntimeException {

    public TelegramUncertainException(String message) {
        super(message);
    }
}
