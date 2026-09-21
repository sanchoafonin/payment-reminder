package com.paymentreminder.common.error;

import java.net.URI;

import com.paymentreminder.payment.service.PaymentNotActiveException;
import com.paymentreminder.payment.service.PaymentNotFoundException;
import com.paymentreminder.payment.service.StalePaymentPeriodException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final URI VALIDATION_TYPE = URI.create("urn:payment-reminder:validation-error");
    private static final URI NOT_FOUND_TYPE = URI.create("urn:payment-reminder:not-found");
    private static final URI CONFLICT_TYPE = URI.create("urn:payment-reminder:conflict");
    private static final URI BAD_REQUEST_TYPE = URI.create("urn:payment-reminder:bad-request");

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handlePaymentNotFound(PaymentNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Payment not found");
        problem.setType(NOT_FOUND_TYPE);
        return problem;
    }

    @ExceptionHandler(PaymentNotActiveException.class)
    ProblemDetail handlePaymentNotActive(PaymentNotActiveException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Payment is not active");
        problem.setType(CONFLICT_TYPE);
        return problem;
    }

    @ExceptionHandler(StalePaymentPeriodException.class)
    ProblemDetail handleStalePeriod(StalePaymentPeriodException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Stale payment period");
        problem.setType(CONFLICT_TYPE);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation failed");
        problem.setType(VALIDATION_TYPE);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::toFieldError)
                .toList());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is malformed or contains an unknown value");
        problem.setTitle("Bad request");
        problem.setType(BAD_REQUEST_TYPE);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Bad request");
        problem.setType(BAD_REQUEST_TYPE);
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Payment was modified concurrently, reload it and retry");
        problem.setTitle("Concurrent modification");
        problem.setType(CONFLICT_TYPE);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Request conflicts with the current state of the data");
        problem.setTitle("Conflict");
        problem.setType(CONFLICT_TYPE);
        return problem;
    }

    private static FieldErrorView toFieldError(FieldError error) {
        return new FieldErrorView(error.getField(), error.getDefaultMessage());
    }

    record FieldErrorView(String field, String message) {
    }
}
