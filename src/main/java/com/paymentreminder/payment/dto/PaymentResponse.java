package com.paymentreminder.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;

public record PaymentResponse(
        Long id,
        String name,
        BigDecimal amount,
        Currency currency,
        Recurrence recurrence,
        LocalDate nextPaymentDate,
        boolean active,
        UUID currentPeriodId,
        List<Integer> reminders,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentResponse from(Payment payment, List<Integer> reminders) {
        return new PaymentResponse(
                payment.getId(),
                payment.getName(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getRecurrence(),
                payment.getNextPaymentDate(),
                payment.isActive(),
                payment.getCurrentPeriodId(),
                List.copyOf(reminders),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
