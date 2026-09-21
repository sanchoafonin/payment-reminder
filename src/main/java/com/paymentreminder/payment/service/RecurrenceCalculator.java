package com.paymentreminder.payment.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

import com.paymentreminder.payment.entity.Recurrence;
import org.springframework.stereotype.Component;

/**
 * Computes the next planned date of a recurring payment. The calculation always starts from the
 * previous planned date (never from "now") and keeps the original calendar anchor:
 * January 31 -> February 28 -> March 31, and February 29 is restored in the next leap year.
 *
 * <p>A single step advances exactly one period, so an overdue payment stays overdue until it is
 * marked as paid enough times; no periods are skipped automatically.
 */
@Component
public class RecurrenceCalculator {

    public LocalDate next(LocalDate plannedDate, Recurrence recurrence, int anchorDay, Short anchorMonth) {
        Objects.requireNonNull(plannedDate, "plannedDate is required");
        Objects.requireNonNull(recurrence, "recurrence is required");
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("anchorDay must be between 1 and 31");
        }
        return switch (recurrence) {
            case MONTHLY -> {
                YearMonth target = YearMonth.from(plannedDate).plusMonths(1);
                yield target.atDay(Math.min(anchorDay, target.lengthOfMonth()));
            }
            case YEARLY -> {
                if (anchorMonth == null || anchorMonth < 1 || anchorMonth > 12) {
                    throw new IllegalArgumentException("anchorMonth must be between 1 and 12 for YEARLY");
                }
                YearMonth target = YearMonth.of(plannedDate.getYear() + 1, anchorMonth);
                yield target.atDay(Math.min(anchorDay, target.lengthOfMonth()));
            }
        };
    }
}
