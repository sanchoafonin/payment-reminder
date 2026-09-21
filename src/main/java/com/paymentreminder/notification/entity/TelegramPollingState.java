package com.paymentreminder.notification.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable long-polling position so updates are not lost across restarts. */
@Entity
@Table(name = "telegram_polling_state")
public class TelegramPollingState {

    @Id
    @Column(name = "consumer_name", length = 100)
    private String consumerName;

    @Column(name = "next_update_id", nullable = false)
    private long nextUpdateId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TelegramPollingState() {
        // Required by JPA.
    }

    public void advanceTo(long offset, Instant now) {
        if (offset > nextUpdateId) {
            this.nextUpdateId = offset;
        }
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public String getConsumerName() {
        return consumerName;
    }

    public long getNextUpdateId() {
        return nextUpdateId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
