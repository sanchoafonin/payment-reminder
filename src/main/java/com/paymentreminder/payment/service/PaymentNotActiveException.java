package com.paymentreminder.payment.service;

public class PaymentNotActiveException extends RuntimeException {

    public PaymentNotActiveException(Long paymentId) {
        super("Payment " + paymentId + " is not active");
    }
}
