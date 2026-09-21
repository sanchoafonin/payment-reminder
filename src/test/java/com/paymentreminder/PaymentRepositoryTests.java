package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class PaymentRepositoryTests extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanPayments() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void persistsAndReloadsAllMonthlyPaymentFields() {
        Payment payment = monthly(LocalDate.of(2026, 1, 31));
        repository.saveAndFlush(payment);
        entityManager.clear();

        Payment loaded = repository.findById(payment.getId()).orElseThrow();
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getName()).isEqualTo("Интернет");
        assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(loaded.getRecurrence()).isEqualTo(Recurrence.MONTHLY);
        assertThat(loaded.getNextPaymentDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getAnchorDay()).isEqualTo((short) 31);
        assertThat(loaded.getAnchorMonth()).isNull();
        assertThat(loaded.getCurrentPeriodId()).isEqualTo(payment.getCurrentPeriodId());
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void persistsYearlyLeapDayAndStringEnums() {
        Payment payment = Payment.create("ОСАГО", new BigDecimal("123.45"), Currency.EUR,
                Recurrence.YEARLY, LocalDate.of(2024, 2, 29), NOW);
        repository.saveAndFlush(payment);
        entityManager.clear();

        Payment loaded = repository.findById(payment.getId()).orElseThrow();
        assertThat(loaded.getAnchorMonth()).isEqualTo((short) 2);
        assertThat(loaded.getAnchorDay()).isEqualTo((short) 29);
        assertThat(loaded.getCurrency()).isEqualTo(Currency.EUR);
        assertThat(loaded.getRecurrence()).isEqualTo(Recurrence.YEARLY);
        assertThat(jdbc.queryForMap("SELECT currency, recurrence FROM payments WHERE id = ?", payment.getId()))
                .containsEntry("currency", "EUR").containsEntry("recurrence", "YEARLY");
    }

    @Test
    void findsOnlyActivePaymentsWithPaginationAndSorting() {
        Payment later = repository.save(monthly(LocalDate.of(2026, 10, 31)));
        Payment earlier = repository.save(monthly(LocalDate.of(2026, 9, 30)));
        Payment inactive = monthly(LocalDate.of(2026, 1, 31));
        inactive.deactivate(NOW);
        repository.saveAndFlush(inactive);
        entityManager.clear();

        var sort = Sort.by("nextPaymentDate", "id");
        var first = repository.findByActiveTrue(PageRequest.of(0, 1, sort));
        var second = repository.findByActiveTrue(PageRequest.of(1, 1, sort));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Payment::getId).containsExactly(earlier.getId());
        assertThat(second.getContent()).extracting(Payment::getId).containsExactly(later.getId());
    }

    @Test
    void deactivationUpdatesTimestampAndVersionButKeepsPeriod() {
        Payment payment = repository.saveAndFlush(monthly(LocalDate.of(2026, 1, 31)));
        Long id = payment.getId();
        var periodId = payment.getCurrentPeriodId();
        payment.deactivate(NOW.plusSeconds(60));
        repository.flush();
        entityManager.clear();

        Payment loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.isActive()).isFalse();
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getVersion()).isEqualTo(1L);
        assertThat(loaded.getCurrentPeriodId()).isEqualTo(periodId);
        loaded.deactivate(NOW.plusSeconds(120));
        repository.flush();
        assertThat(loaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsSavingStaleDetachedVersion() {
        Payment stale = repository.saveAndFlush(monthly(LocalDate.of(2026, 1, 31)));
        entityManager.clear();
        Payment current = repository.findById(stale.getId()).orElseThrow();
        current.deactivate(NOW.plusSeconds(60));
        repository.flush();
        entityManager.clear();

        stale.deactivate(NOW.plusSeconds(120));
        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private Payment monthly(LocalDate date) {
        return Payment.create("Интернет", new BigDecimal("100.00"), Currency.RUB, Recurrence.MONTHLY, date, NOW);
    }
}
