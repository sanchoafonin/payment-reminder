package com.paymentreminder.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PaymentCreateRequest(
        @NotBlank String name,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull Currency currency,
        @NotNull Recurrence recurrence,
        @NotNull LocalDate nextPaymentDate,
        List<@NotNull @PositiveOrZero Integer> reminders
) {
}
