package com.paymentreminder.payment.entity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T06:00:00Z"), ZoneId.of("Europe/Moscow"));
    private static final LocalDate DATE = LocalDate.of(2026, 1, 31);

    @Test
    void createsActiveMonthlyPaymentWithPastDateAndOriginalDay() {
        Payment payment = create("  Интернет  ", "100.000");

        assertThat(payment.getName()).isEqualTo("Интернет");
        assertThat(payment.getAmount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(payment.isActive()).isTrue();
        assertThat(payment.getNextPaymentDate()).isEqualTo(DATE);
        assertThat(payment.getAnchorDay()).isEqualTo((short) 31);
        assertThat(payment.getAnchorMonth()).isNull();
        assertThat(payment.getCurrentPeriodId()).isNotNull();
        assertThat(payment.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(payment.getUpdatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void yearlyLeapDayRetainsMonthAndDayAnchors() {
        Payment payment = Payment.create("ОСАГО", BigDecimal.TEN, Currency.RUB, Recurrence.YEARLY,
                LocalDate.of(2024, 2, 29), CLOCK.instant());

        assertThat(payment.getAnchorDay()).isEqualTo((short) 29);
        assertThat(payment.getAnchorMonth()).isEqualTo((short) 2);
    }

    @Test
    void separatePaymentsHaveDifferentPeriodIds() {
        assertThat(create("Интернет", "100").getCurrentPeriodId())
                .isNotEqualTo(create("Интернет", "100").getCurrentPeriodId());
    }

    @Test
    void deactivationKeepsPeriodAndDoesNotChangeTimeOnRepeat() {
        Payment payment = create("Интернет", "100");
        var periodId = payment.getCurrentPeriodId();
        Instant deactivatedAt = CLOCK.instant().plusSeconds(60);
        payment.deactivate(deactivatedAt);
        payment.deactivate(deactivatedAt.plusSeconds(60));

        assertThat(payment.isActive()).isFalse();
        assertThat(payment.getCurrentPeriodId()).isEqualTo(periodId);
        assertThat(payment.getNextPaymentDate()).isEqualTo(DATE);
        assertThat(payment.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(payment.getUpdatedAt()).isEqualTo(deactivatedAt);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankName(String name) {
        assertThatThrownBy(() -> create(name, "100")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateKeepsPeriodAndAnchorsWhenOnlyValueFieldsChange() {
        Payment payment = create("Интернет", "100");
        UUID periodId = payment.getCurrentPeriodId();
        Instant updatedAt = CLOCK.instant().plusSeconds(60);

        payment.update("Мобильная связь", new BigDecimal("250.00"), Currency.USD, Recurrence.MONTHLY,
                DATE, false, updatedAt);

        assertThat(payment.getName()).isEqualTo("Мобильная связь");
        assertThat(payment.getAmount()).isEqualByComparingTo("250.00");
        assertThat(payment.getCurrency()).isEqualTo(Currency.USD);
        assertThat(payment.isActive()).isFalse();
        assertThat(payment.getCurrentPeriodId()).isEqualTo(periodId);
        assertThat(payment.getAnchorDay()).isEqualTo((short) 31);
        assertThat(payment.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(payment.getCreatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void updateOfDateStartsNewPeriodAndRebindsAnchor() {
        Payment payment = create("Ипотека", "35300");
        UUID periodId = payment.getCurrentPeriodId();

        payment.update("Ипотека", new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2027, 2, 28), true, CLOCK.instant().plusSeconds(60));

        assertThat(payment.getCurrentPeriodId()).isNotEqualTo(periodId);
        assertThat(payment.getAnchorDay()).isEqualTo((short) 28);
        assertThat(payment.getAnchorMonth()).isNull();
        assertThat(payment.getNextPaymentDate()).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void updateOfRecurrenceStartsNewPeriodAndSetsYearlyAnchor() {
        Payment payment = create("ОСАГО", "5000");
        UUID periodId = payment.getCurrentPeriodId();

        payment.update("ОСАГО", new BigDecimal("5000.00"), Currency.RUB, Recurrence.YEARLY,
                LocalDate.of(2026, 3, 15), true, CLOCK.instant().plusSeconds(60));

        assertThat(payment.getCurrentPeriodId()).isNotEqualTo(periodId);
        assertThat(payment.getAnchorMonth()).isEqualTo((short) 3);
        assertThat(payment.getAnchorDay()).isEqualTo((short) 15);
    }

    @Test
    void updateBackToMonthlyClearsYearlyAnchor() {
        Payment payment = Payment.create("ОСАГО", BigDecimal.TEN, Currency.RUB, Recurrence.YEARLY,
                LocalDate.of(2026, 3, 15), CLOCK.instant());

        payment.update("ОСАГО", BigDecimal.TEN, Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 3, 15), true, CLOCK.instant().plusSeconds(60));

        assertThat(payment.getAnchorMonth()).isNull();
        assertThat(payment.getAnchorDay()).isEqualTo((short) 15);
    }

    @Test
    void enforcesNameLengthInCharacters() {
        assertThat(create("💳".repeat(255), "100").getName()).isEqualTo("💳".repeat(255));
        assertThatThrownBy(() -> create("я".repeat(256), "100")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "1.001", "100000000000000000.00"})
    void rejectsInvalidAmountWithoutRounding(String amount) {
        assertThatThrownBy(() -> create("Интернет", amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "99999999999999999.99", "1E+2"})
    void acceptsAmountsRepresentableByDatabase(String amount) {
        assertThat(create("Интернет", amount).getAmount()).isEqualByComparingTo(amount);
    }

    @Test
    void requiresAllCreationFields() {
        Instant now = CLOCK.instant();
        assertThatNullPointerException().isThrownBy(() -> Payment.create(null, BigDecimal.TEN, Currency.RUB, Recurrence.MONTHLY, DATE, now));
        assertThatNullPointerException().isThrownBy(() -> Payment.create("Платёж", null, Currency.RUB, Recurrence.MONTHLY, DATE, now));
        assertThatNullPointerException().isThrownBy(() -> Payment.create("Платёж", BigDecimal.TEN, null, Recurrence.MONTHLY, DATE, now));
        assertThatNullPointerException().isThrownBy(() -> Payment.create("Платёж", BigDecimal.TEN, Currency.RUB, null, DATE, now));
        assertThatNullPointerException().isThrownBy(() -> Payment.create("Платёж", BigDecimal.TEN, Currency.RUB, Recurrence.MONTHLY, null, now));
        assertThatNullPointerException().isThrownBy(() -> Payment.create("Платёж", BigDecimal.TEN, Currency.RUB, Recurrence.MONTHLY, DATE, null));
    }

    @Test
    void completingPeriodRotatesPeriodIdAnchorsAndTimestamp() {
        Payment payment = create("Интернет", "100");
        UUID periodId = payment.getCurrentPeriodId();
        Instant now = CLOCK.instant().plusSeconds(60);

        payment.completeCurrentPeriod(LocalDate.of(2026, 2, 28), now);

        assertThat(payment.getNextPaymentDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(payment.getCurrentPeriodId()).isNotEqualTo(periodId);
        assertThat(payment.getAnchorDay()).isEqualTo((short) 31);
        assertThat(payment.getAnchorMonth()).isNull();
        assertThat(payment.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(payment.getUpdatedAt()).isEqualTo(now);
    }

    private Payment create(String name, String amount) {
        return Payment.create(name, new BigDecimal(amount), Currency.RUB, Recurrence.MONTHLY, DATE, CLOCK.instant());
    }
}
