package com.paymentreminder.notification.entity;

/** Delivery lifecycle of a notification job. */
public enum NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    UNKNOWN,
    CANCELLED
}
