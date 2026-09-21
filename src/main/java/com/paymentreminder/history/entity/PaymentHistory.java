package com.paymentreminder.history.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.paymentreminder.payment.entity.Currency;
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

/**
 * An immutable snapshot of a completed payment period. The amount and currency are copied so that
 * later edits to the payment never change history.
 */
@Entity
@Table(name = "payment_history")
public class PaymentHistory {

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

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    protected PaymentHistory() {
        // Required by JPA.
    }

    public static PaymentHistory record(Payment payment, UUID periodId, LocalDate scheduledDate, Instant paidAt) {
        PaymentHistory history = new PaymentHistory();
        history.payment = Objects.requireNonNull(payment, "payment is required");
        history.periodId = Objects.requireNonNull(periodId, "periodId is required");
        history.scheduledDate = Objects.requireNonNull(scheduledDate, "scheduledDate is required");
        history.paidAt = Objects.requireNonNull(paidAt, "paidAt is required");
        history.amount = payment.getAmount();
        history.currency = payment.getCurrency();
        return history;
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

    public Instant getPaidAt() {
        return paidAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }
}
