package com.paymentreminder.payment.dto;

import com.paymentreminder.history.dto.PaymentHistoryResponse;

public record PaymentPayResponse(PaymentResponse payment, PaymentHistoryResponse history) {
}
