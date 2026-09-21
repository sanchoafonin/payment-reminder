package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.repository.PaymentRepository;
import com.paymentreminder.reminder.entity.PaymentReminder;
import com.paymentreminder.reminder.repository.PaymentReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class PaymentReminderRepositoryTests extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    @Autowired
    private PaymentReminderRepository reminderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanPayments() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void storesRulesSortedByDaysBefore() {
        Payment payment = savePayment("Ипотека");
        reminderRepository.save(PaymentReminder.create(payment, 5));
        reminderRepository.save(PaymentReminder.create(payment, 1));
        reminderRepository.saveAndFlush(PaymentReminder.create(payment, 3));

        List<PaymentReminder> rules = reminderRepository.findByPaymentIdOrderByDaysBeforeAsc(payment.getId());

        assertThat(rules).extracting(PaymentReminder::getDaysBefore).containsExactly(1, 3, 5);
        assertThat(rules).allSatisfy(rule -> assertThat(rule.getPayment().getId()).isEqualTo(payment.getId()));
    }

    @Test
    void rejectsTheSameDayTwiceForOnePayment() {
        Payment payment = savePayment("Ипотека");
        reminderRepository.saveAndFlush(PaymentReminder.create(payment, 3));

        assertThatThrownBy(() -> reminderRepository.saveAndFlush(PaymentReminder.create(payment, 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameDayForDifferentPayments() {
        Payment first = savePayment("Ипотека");
        Payment second = savePayment("Интернет");
        reminderRepository.save(PaymentReminder.create(first, 3));
        reminderRepository.saveAndFlush(PaymentReminder.create(second, 3));

        assertThat(reminderRepository.findByPaymentIdOrderByDaysBeforeAsc(second.getId())).hasSize(1);
    }

    @Test
    void deleteByPaymentIdRemovesOnlyThatPaymentRules() {
        Payment first = savePayment("Ипотека");
        Payment second = savePayment("Интернет");
        reminderRepository.save(PaymentReminder.create(first, 1));
        reminderRepository.save(PaymentReminder.create(first, 3));
        reminderRepository.saveAndFlush(PaymentReminder.create(second, 2));

        reminderRepository.deleteByPaymentId(first.getId());
        reminderRepository.flush();

        assertThat(reminderRepository.findByPaymentIdOrderByDaysBeforeAsc(first.getId())).isEmpty();
        assertThat(reminderRepository.findByPaymentIdOrderByDaysBeforeAsc(second.getId())).hasSize(1);
    }

    @Test
    void findsRulesOfSeveralPaymentsInOrder() {
        Payment first = savePayment("Ипотека");
        Payment second = savePayment("Интернет");
        reminderRepository.save(PaymentReminder.create(first, 5));
        reminderRepository.save(PaymentReminder.create(first, 1));
        reminderRepository.saveAndFlush(PaymentReminder.create(second, 7));

        List<PaymentReminder> rules = reminderRepository.findByPaymentIdInOrderByDaysBeforeAsc(
                List.of(first.getId(), second.getId()));

        assertThat(rules).extracting(PaymentReminder::getDaysBefore).containsExactly(1, 5, 7);
    }

    @Test
    void entityRejectsNegativeDaysBefore() {
        Payment payment = savePayment("Ипотека");

        assertThatThrownBy(() -> PaymentReminder.create(payment, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Payment savePayment(String name) {
        return paymentRepository.saveAndFlush(Payment.create(name, new BigDecimal("100.00"), Currency.RUB,
                Recurrence.MONTHLY, LocalDate.of(2026, 9, 25), NOW));
    }
}
