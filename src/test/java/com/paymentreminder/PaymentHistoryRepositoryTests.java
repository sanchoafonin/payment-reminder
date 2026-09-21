package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.paymentreminder.history.entity.PaymentHistory;
import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class PaymentHistoryRepositoryTests extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    @Autowired
    private PaymentHistoryRepository historyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanPayments() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void persistsSnapshotOfPlannedDateAmountAndCurrency() {
        Payment payment = savePayment();
        UUID periodId = payment.getCurrentPeriodId();
        PaymentHistory history = historyRepository.saveAndFlush(PaymentHistory.record(payment, periodId,
                LocalDate.of(2026, 9, 25), NOW));
        entityManager.clear();

        PaymentHistory loaded = historyRepository.findById(history.getId()).orElseThrow();
        assertThat(loaded.getPayment().getId()).isEqualTo(payment.getId());
        assertThat(loaded.getPeriodId()).isEqualTo(periodId);
        assertThat(loaded.getScheduledDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(loaded.getPaidAt()).isEqualTo(NOW);
        assertThat(loaded.getAmount()).isEqualByComparingTo("35300.00");
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
    }

    @Test
    void snapshotDoesNotFollowLaterPaymentEdits() {
        Payment payment = savePayment();
        PaymentHistory history = historyRepository.saveAndFlush(PaymentHistory.record(payment,
                payment.getCurrentPeriodId(), payment.getNextPaymentDate(), NOW));

        payment.update("Новое имя", new BigDecimal("1.00"), Currency.USD, Recurrence.MONTHLY,
                LocalDate.of(2026, 10, 25), true, NOW.plusSeconds(60));
        paymentRepository.saveAndFlush(payment);
        entityManager.clear();

        PaymentHistory loaded = historyRepository.findById(history.getId()).orElseThrow();
        assertThat(loaded.getAmount()).isEqualByComparingTo("35300.00");
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(loaded.getScheduledDate()).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void returnsHistoryNewestFirst() {
        Payment payment = savePayment();
        PaymentHistory older = historyRepository.save(PaymentHistory.record(payment, UUID.randomUUID(),
                LocalDate.of(2026, 9, 25), NOW));
        PaymentHistory newer = historyRepository.save(PaymentHistory.record(payment, UUID.randomUUID(),
                LocalDate.of(2026, 8, 25), NOW.plusSeconds(3600)));
        historyRepository.flush();

        Page<PaymentHistory> page = historyRepository.findByPaymentIdOrderByPaidAtDescIdDesc(
                payment.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(PaymentHistory::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void rejectsClosingTheSamePeriodTwice() {
        Payment payment = savePayment();
        UUID periodId = payment.getCurrentPeriodId();
        historyRepository.saveAndFlush(PaymentHistory.record(payment, periodId, LocalDate.of(2026, 9, 25), NOW));

        assertThatThrownBy(() -> historyRepository.saveAndFlush(PaymentHistory.record(payment, periodId,
                LocalDate.of(2026, 9, 25), NOW.plusSeconds(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsRecordByPaymentAndPeriod() {
        Payment payment = savePayment();
        UUID periodId = payment.getCurrentPeriodId();
        historyRepository.saveAndFlush(PaymentHistory.record(payment, periodId, LocalDate.of(2026, 9, 25), NOW));

        assertThat(historyRepository.findByPaymentIdAndPeriodId(payment.getId(), periodId)).isPresent();
        assertThat(historyRepository.findByPaymentIdAndPeriodId(payment.getId(), UUID.randomUUID())).isEmpty();
    }

    private Payment savePayment() {
        return paymentRepository.saveAndFlush(Payment.create("Ипотека", new BigDecimal("35300.00"), Currency.RUB,
                Recurrence.MONTHLY, LocalDate.of(2026, 9, 25), NOW));
    }
}
