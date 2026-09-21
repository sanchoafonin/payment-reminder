package com.paymentreminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationJobCancellationTests extends PostgresIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReminderPlanningService planningService;

    @Autowired
    private NotificationJobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void payingCancelsPendingJobsOfTheClosedPeriod() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5));
        planningService.plan(LocalDate.of(2026, 9, 20));
        assertThat(pendingCount(id)).isEqualTo(1);

        paymentService.pay(id, null);

        assertThat(pendingCount(id)).isZero();
        assertThat(cancelledCount(id)).isEqualTo(1);
    }

    @Test
    void deactivatingCancelsAllPendingJobs() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5, 3, 1));
        planningService.plan(LocalDate.of(2026, 9, 20));
        planningService.plan(LocalDate.of(2026, 9, 22));
        planningService.plan(LocalDate.of(2026, 9, 24));
        assertThat(pendingCount(id)).isEqualTo(3);

        paymentService.deactivate(id);

        assertThat(pendingCount(id)).isZero();
        assertThat(cancelledCount(id)).isEqualTo(3);
    }

    @Test
    void changingDateCancelsPendingJobsOfThePreviousPeriod() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5));
        planningService.plan(LocalDate.of(2026, 9, 20));
        assertThat(pendingCount(id)).isEqualTo(1);

        paymentService.update(id, new PaymentUpdateRequest("Ипотека", new BigDecimal("35300.00"), Currency.RUB,
                Recurrence.MONTHLY, LocalDate.of(2026, 10, 25), List.of(5), true));

        assertThat(pendingCount(id)).isZero();
        assertThat(cancelledCount(id)).isEqualTo(1);
    }

    @Test
    void removingRuleCancelsOnlyItsPendingJobs() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5, 3, 1));
        planningService.plan(LocalDate.of(2026, 9, 20));
        planningService.plan(LocalDate.of(2026, 9, 22));
        assertThat(pendingCount(id)).isEqualTo(2);

        paymentService.update(id, new PaymentUpdateRequest("Ипотека", new BigDecimal("35300.00"), Currency.RUB,
                Recurrence.MONTHLY, LocalDate.of(2026, 9, 25), List.of(3, 1), true));

        assertThat(pendingCount(id)).isEqualTo(1);
        assertThat(cancelledCount(id)).isEqualTo(1);
        List<NotificationJob> pending = jobRepository.findByPaymentIdAndStatus(id, NotificationStatus.PENDING);
        assertThat(pending).singleElement().extracting(NotificationJob::getDaysBefore).isEqualTo(3);
    }

    private long createPayment(LocalDate nextPaymentDate, List<Integer> reminders) {
        return paymentService.create(new PaymentCreateRequest("Ипотека", new BigDecimal("35300.00"),
                Currency.RUB, Recurrence.MONTHLY, nextPaymentDate, reminders)).id();
    }

    private long pendingCount(long paymentId) {
        return jobRepository.countByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING);
    }

    private long cancelledCount(long paymentId) {
        return jobRepository.countByPaymentIdAndStatus(paymentId, NotificationStatus.CANCELLED);
    }
}
