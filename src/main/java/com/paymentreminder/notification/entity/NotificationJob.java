package com.paymentreminder.notification.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.paymentreminder.payment.entity.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A persisted notification to deliver, together with its delivery state. */
@Entity
@Table(name = "notification_jobs")
public class NotificationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationKind kind;

    @Column(name = "days_before")
    private Integer daysBefore;

    @Column(name = "notification_date", nullable = false)
    private LocalDate notificationDate;

    @Column(name = "source_notification_id")
    private Long sourceNotificationId;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationJob() {
        // Required by JPA.
    }

    /** Takes ownership of the job for delivery: records the lease and its token. */
    public void markProcessing(UUID token, Instant leaseUntil, Instant now) {
        this.status = NotificationStatus.PROCESSING;
        this.claimToken = Objects.requireNonNull(token, "token is required");
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil is required");
        this.attemptCount++;
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public void markSent(long telegramMessageId, Instant now) {
        this.status = NotificationStatus.SENT;
        this.telegramMessageId = telegramMessageId;
        this.sentAt = Objects.requireNonNull(now, "now is required");
        this.lastError = null;
        release();
        this.updatedAt = now;
    }

    /** A known failure that may be retried; the job becomes pending again. */
    public void markRetry(String error, Instant availableAt, Instant now) {
        this.status = NotificationStatus.PENDING;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt is required");
        this.lastError = error;
        release();
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    /** A final failure: the message was definitely not delivered and must not be retried. */
    public void markFailed(String error, Instant now) {
        this.status = NotificationStatus.FAILED;
        this.lastError = error;
        release();
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    /** The delivery outcome is unknown, so the job is never retried automatically. */
    public void markUnknown(String error, Instant now) {
        this.status = NotificationStatus.UNKNOWN;
        this.lastError = error;
        release();
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    private void release() {
        this.claimToken = null;
        this.leaseUntil = null;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public UUID getPeriodId() {
        return periodId;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public NotificationKind getKind() {
        return kind;
    }

    public Integer getDaysBefore() {
        return daysBefore;
    }

    public LocalDate getNotificationDate() {
        return notificationDate;
    }

    public Long getSourceNotificationId() {
        return sourceNotificationId;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Long getTelegramMessageId() {
        return telegramMessageId;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
