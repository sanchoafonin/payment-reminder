package com.paymentreminder.notification.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.payment.entity.Currency;

/** Everything needed to render a notification, decoupled from persistence. */
public record NotificationMessage(
        long notificationId,
        String paymentName,
        BigDecimal amount,
        Currency currency,
        LocalDate scheduledDate,
        NotificationKind kind,
        Integer daysBefore,
        LocalDate notificationDate
) {

    public NotificationMessage {
        Objects.requireNonNull(paymentName, "paymentName is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(scheduledDate, "scheduledDate is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(notificationDate, "notificationDate is required");
    }
}
