package com.paymentreminder.payment.service;

import java.util.UUID;

public class StalePaymentPeriodException extends RuntimeException {

    public StalePaymentPeriodException(Long paymentId, UUID expectedPeriodId) {
        super("Payment " + paymentId + " no longer has the period " + expectedPeriodId);
    }
}
