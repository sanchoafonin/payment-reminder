package com.paymentreminder.notification.telegram;

/**
 * A delivery attempt that is known not to have reached Telegram. {@code retryable} says whether the
 * cause was transient (rate limiting, server error, unreachable host) or permanent.
 */
public class TelegramDeliveryException extends RuntimeException {

    private final boolean retryable;

    public TelegramDeliveryException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
