package com.paymentreminder.payment.dto;

import java.util.UUID;

/**
 * Optional body for {@code POST /api/payments/{id}/pay}. When {@code expectedPeriodId} is provided
 * and does not match the current period, the operation is rejected as stale unless that period was
 * already paid, in which case the previous result is returned. Without it a repeated request is not
 * idempotent and may close the next period.
 */
public record PaymentPayRequest(UUID expectedPeriodId) {
}
