package com.paymentreminder.payment.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Recurrence recurrence;

    @Column(name = "next_payment_date", nullable = false)
    private LocalDate nextPaymentDate;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "current_period_id", nullable = false)
    private UUID currentPeriodId;

    @Column(name = "anchor_day", nullable = false)
    private short anchorDay;

    @Column(name = "anchor_month")
    private Short anchorMonth;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // Required by JPA.
    }

    /** The caller supplies time from the application's Clock. Past payment dates are allowed. */
    public static Payment create(String name, BigDecimal amount, Currency currency,
            Recurrence recurrence, LocalDate nextPaymentDate, Instant now) {
        Payment payment = new Payment();
        payment.name = validatedName(name);
        payment.amount = validatedAmount(amount);
        payment.currency = Objects.requireNonNull(currency, "currency is required");
        payment.recurrence = Objects.requireNonNull(recurrence, "recurrence is required");
        payment.nextPaymentDate = Objects.requireNonNull(nextPaymentDate, "nextPaymentDate is required");
        payment.active = true;
        payment.currentPeriodId = UUID.randomUUID();
        payment.anchorDay = (short) nextPaymentDate.getDayOfMonth();
        payment.anchorMonth = recurrence == Recurrence.YEARLY ? (short) nextPaymentDate.getMonthValue() : null;
        payment.createdAt = Objects.requireNonNull(now, "now is required");
        payment.updatedAt = now;
        return payment;
    }

    public void deactivate(Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (active) {
            active = false;
            updatedAt = now;
        }
    }

    /**
     * Full replacement of the editable fields. Changing the date or the recurrence starts a new
     * period and rebinds the calendar anchors; changing only name, amount, currency or active
     * keeps the current period untouched.
     */
    public void update(String name, BigDecimal amount, Currency currency, Recurrence recurrence,
            LocalDate nextPaymentDate, boolean active, Instant now) {
        Objects.requireNonNull(now, "now is required");
        Currency newCurrency = Objects.requireNonNull(currency, "currency is required");
        Recurrence newRecurrence = Objects.requireNonNull(recurrence, "recurrence is required");
        LocalDate newDate = Objects.requireNonNull(nextPaymentDate, "nextPaymentDate is required");

        boolean periodChanged = newRecurrence != this.recurrence || !newDate.equals(this.nextPaymentDate);

        this.name = validatedName(name);
        this.amount = validatedAmount(amount);
        this.currency = newCurrency;
        this.recurrence = newRecurrence;
        this.nextPaymentDate = newDate;
        this.active = active;
        if (periodChanged) {
            this.currentPeriodId = UUID.randomUUID();
            this.anchorDay = (short) newDate.getDayOfMonth();
            this.anchorMonth = newRecurrence == Recurrence.YEARLY ? (short) newDate.getMonthValue() : null;
        }
        this.updatedAt = now;
    }

    /**
     * Closes the current payment period: stores the already computed next planned date and issues a
     * new period id so that stale buttons cannot pay the following period.
     */
    public void completeCurrentPeriod(LocalDate nextPaymentDate, Instant now) {
        this.nextPaymentDate = Objects.requireNonNull(nextPaymentDate, "nextPaymentDate is required");
        this.currentPeriodId = UUID.randomUUID();
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    private static String validatedName(String name) {
        Objects.requireNonNull(name, "name is required");
        String normalized = name.strip();
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 255) {
            throw new IllegalArgumentException("name must contain between 1 and 255 characters");
        }
        return normalized;
    }

    private static BigDecimal validatedAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        BigDecimal normalized;
        try {
            normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two fractional digits", exception);
        }
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException("amount must have at most 17 integer digits");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public LocalDate getNextPaymentDate() {
        return nextPaymentDate;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getCurrentPeriodId() {
        return currentPeriodId;
    }

    public short getAnchorDay() {
        return anchorDay;
    }

    public Short getAnchorMonth() {
        return anchorMonth;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
