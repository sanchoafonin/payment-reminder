package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderPlanningServiceTests extends PostgresIntegrationTest {

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
    void createsRegularJobOnlyOnMatchingDay() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5, 3, 1));

        ReminderPlanningService.PlanningResult result = planningService.plan(LocalDate.of(2026, 9, 20));

        assertThat(result.regularJobs()).isEqualTo(1);
        assertThat(result.overdueJobs()).isZero();
        List<NotificationJob> jobs = jobRepository.findByPaymentIdAndStatus(id, NotificationStatus.PENDING);
        assertThat(jobs).hasSize(1);
        NotificationJob job = jobs.get(0);
        assertThat(job.getKind()).isEqualTo(NotificationKind.REGULAR);
        assertThat(job.getDaysBefore()).isEqualTo(5);
        assertThat(job.getScheduledDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(job.getNotificationDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(job.getAvailableAt()).isEqualTo(Instant.parse("2026-09-20T06:00:00Z"));
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void doesNotCreateRegularJobOnNonMatchingDay() {
        createPayment(LocalDate.of(2026, 9, 25), List.of(5));

        assertThat(planningService.plan(LocalDate.of(2026, 9, 19)).regularJobs()).isZero();
        assertThat(planningService.plan(LocalDate.of(2026, 9, 21)).regularJobs()).isZero();
    }

    @Test
    void planningTwiceForTheSameDayIsIdempotent() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5));

        planningService.plan(LocalDate.of(2026, 9, 20));
        planningService.plan(LocalDate.of(2026, 9, 20));

        assertThat(jobRepository.countByPaymentIdAndStatus(id, NotificationStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void createsJobForEachRuleOnItsOwnDay() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5, 3, 1));

        planningService.plan(LocalDate.of(2026, 9, 20));
        planningService.plan(LocalDate.of(2026, 9, 22));
        planningService.plan(LocalDate.of(2026, 9, 24));

        assertThat(jobRepository.countByPaymentIdAndStatus(id, NotificationStatus.PENDING)).isEqualTo(3);
    }

    @Test
    void createsOverdueJobOncePerDayForOverduePayment() {
        long id = createPayment(LocalDate.of(2026, 9, 10), List.of());

        assertThat(planningService.plan(LocalDate.of(2026, 9, 15)).overdueJobs()).isEqualTo(1);
        assertThat(planningService.plan(LocalDate.of(2026, 9, 15)).overdueJobs()).isZero();
        assertThat(planningService.plan(LocalDate.of(2026, 9, 16)).overdueJobs()).isEqualTo(1);

        List<NotificationJob> jobs = jobRepository.findByPaymentIdAndStatus(id, NotificationStatus.PENDING);
        assertThat(jobs).hasSize(2).allSatisfy(job -> {
            assertThat(job.getKind()).isEqualTo(NotificationKind.OVERDUE);
            assertThat(job.getDaysBefore()).isNull();
            assertThat(job.getScheduledDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        });
    }

    @Test
    void doesNotCreateOverdueJobOnDueDate() {
        createPayment(LocalDate.of(2026, 9, 15), List.of());

        assertThat(planningService.plan(LocalDate.of(2026, 9, 15)).overdueJobs()).isZero();
    }

    @Test
    void skipsInactivePayments() {
        long id = createPayment(LocalDate.of(2026, 9, 25), List.of(5));
        paymentService.deactivate(id);

        assertThat(planningService.plan(LocalDate.of(2026, 9, 20)).regularJobs()).isZero();
        assertThat(planningService.plan(LocalDate.of(2026, 9, 26)).overdueJobs()).isZero();
    }

    private long createPayment(LocalDate nextPaymentDate, List<Integer> reminders) {
        return paymentService.create(new PaymentCreateRequest("Ипотека", new BigDecimal("35300.00"),
                Currency.RUB, Recurrence.MONTHLY, nextPaymentDate, reminders)).id();
    }
}
