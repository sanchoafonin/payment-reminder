package com.paymentreminder.history.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.paymentreminder.history.entity.PaymentHistory;
import com.paymentreminder.payment.entity.Currency;

public record PaymentHistoryResponse(
        Long id,
        Long paymentId,
        UUID periodId,
        LocalDate scheduledDate,
        Instant paidAt,
        BigDecimal amount,
        Currency currency
) {

    public static PaymentHistoryResponse from(PaymentHistory history) {
        return new PaymentHistoryResponse(
                history.getId(),
                history.getPayment().getId(),
                history.getPeriodId(),
                history.getScheduledDate(),
                history.getPaidAt(),
                history.getAmount(),
                history.getCurrency());
    }
}
