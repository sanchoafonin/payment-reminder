package com.paymentreminder.payment.service;

import java.time.LocalDate;
import java.util.stream.Stream;

import com.paymentreminder.payment.entity.Recurrence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RecurrenceCalculatorTests {

    private final RecurrenceCalculator calculator = new RecurrenceCalculator();

    @ParameterizedTest
    @MethodSource("monthlyCases")
    void advancesMonthlyKeepingAnchorDay(LocalDate plannedDate, int anchorDay, LocalDate expected) {
        assertThat(calculator.next(plannedDate, Recurrence.MONTHLY, anchorDay, null)).isEqualTo(expected);
    }

    static Stream<Arguments> monthlyCases() {
        return Stream.of(
                arguments(LocalDate.of(2026, 1, 31), 31, LocalDate.of(2026, 2, 28)),
                arguments(LocalDate.of(2026, 2, 28), 31, LocalDate.of(2026, 3, 31)),
                arguments(LocalDate.of(2025, 1, 31), 31, LocalDate.of(2025, 2, 28)),
                arguments(LocalDate.of(2024, 1, 31), 31, LocalDate.of(2024, 2, 29)),
                arguments(LocalDate.of(2026, 3, 31), 31, LocalDate.of(2026, 4, 30)),
                arguments(LocalDate.of(2026, 5, 31), 31, LocalDate.of(2026, 6, 30)),
                arguments(LocalDate.of(2026, 8, 31), 31, LocalDate.of(2026, 9, 30)),
                arguments(LocalDate.of(2026, 1, 30), 30, LocalDate.of(2026, 2, 28)),
                arguments(LocalDate.of(2026, 12, 15), 15, LocalDate.of(2027, 1, 15)),
                arguments(LocalDate.of(2026, 9, 25), 25, LocalDate.of(2026, 10, 25)));
    }

    @ParameterizedTest
    @MethodSource("yearlyCases")
    void advancesYearlyKeepingAnchorMonthAndDay(LocalDate plannedDate, int anchorDay, Short anchorMonth,
            LocalDate expected) {
        assertThat(calculator.next(plannedDate, Recurrence.YEARLY, anchorDay, anchorMonth)).isEqualTo(expected);
    }

    static Stream<Arguments> yearlyCases() {
        return Stream.of(
                arguments(LocalDate.of(2024, 2, 29), 29, (short) 2, LocalDate.of(2025, 2, 28)),
                arguments(LocalDate.of(2025, 2, 28), 29, (short) 2, LocalDate.of(2026, 2, 28)),
                arguments(LocalDate.of(2026, 2, 28), 29, (short) 2, LocalDate.of(2027, 2, 28)),
                arguments(LocalDate.of(2027, 2, 28), 29, (short) 2, LocalDate.of(2028, 2, 29)),
                arguments(LocalDate.of(2026, 3, 15), 15, (short) 3, LocalDate.of(2027, 3, 15)),
                arguments(LocalDate.of(2026, 12, 31), 31, (short) 12, LocalDate.of(2027, 12, 31)));
    }

    @Test
    void yearlySequenceRestoresLeapDayAcrossCycles() {
        LocalDate date = LocalDate.of(2024, 2, 29);
        LocalDate[] expected = {
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2029, 2, 28),
                LocalDate.of(2030, 2, 28),
                LocalDate.of(2031, 2, 28),
                LocalDate.of(2032, 2, 29)
        };

        for (LocalDate next : expected) {
            date = calculator.next(date, Recurrence.YEARLY, 29, (short) 2);
            assertThat(date).isEqualTo(next);
        }
    }

    @Test
    void overduePaymentAdvancesExactlyOnePeriodPerCall() {
        LocalDate planned = LocalDate.of(2025, 1, 31);

        LocalDate once = calculator.next(planned, Recurrence.MONTHLY, 31, null);
        LocalDate twice = calculator.next(once, Recurrence.MONTHLY, 31, null);

        assertThat(once).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(twice).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    @Test
    void monthlyIgnoresAnchorMonth() {
        assertThat(calculator.next(LocalDate.of(2026, 1, 31), Recurrence.MONTHLY, 31, (short) 5))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void rejectsInvalidAnchors() {
        assertThatThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), Recurrence.MONTHLY, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), Recurrence.MONTHLY, 32, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), Recurrence.YEARLY, 15, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), Recurrence.YEARLY, 15, (short) 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), Recurrence.YEARLY, 15, (short) 13))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresPlannedDateAndRecurrence() {
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.next(null, Recurrence.MONTHLY, 1, null));
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.next(LocalDate.of(2026, 1, 1), null, 1, null));
    }
}
