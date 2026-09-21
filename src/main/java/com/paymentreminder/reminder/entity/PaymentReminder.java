package com.paymentreminder.reminder.entity;

import java.util.Objects;

import com.paymentreminder.payment.entity.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A reminder rule: send a notification {@code daysBefore} days ahead of the payment date. */
@Entity
@Table(name = "payment_reminders")
public class PaymentReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "days_before", nullable = false)
    private int daysBefore;

    protected PaymentReminder() {
        // Required by JPA.
    }

    public static PaymentReminder create(Payment payment, int daysBefore) {
        PaymentReminder reminder = new PaymentReminder();
        reminder.payment = Objects.requireNonNull(payment, "payment is required");
        if (daysBefore < 0) {
            throw new IllegalArgumentException("daysBefore must be greater than or equal to zero");
        }
        reminder.daysBefore = daysBefore;
        return reminder;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public int getDaysBefore() {
        return daysBefore;
    }
}
