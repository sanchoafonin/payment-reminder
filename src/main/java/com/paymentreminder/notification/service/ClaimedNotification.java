package com.paymentreminder.notification.service;

import java.util.Objects;
import java.util.UUID;

/**
 * A notification job reserved for delivery. The {@code claimToken} must be presented when the
 * outcome is recorded so a stale worker cannot overwrite a newer attempt.
 */
public record ClaimedNotification(Long id, UUID claimToken, int attemptCount, NotificationMessage message) {

    public ClaimedNotification {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(claimToken, "claimToken is required");
        Objects.requireNonNull(message, "message is required");
    }
}
