package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import com.paymentreminder.reminder.service.SnoozeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SnoozeServiceTests extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final LocalTime NOTIFICATION_TIME = LocalTime.of(9, 0);

    @Autowired
    private SnoozeService snoozeService;

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
    void createsSnoozeForNextCalendarDayAtNotificationTime() {
        NotificationJob source = createSourceJob();

        assertThat(snoozeService.snoozeUntilTomorrow(source)).isTrue();

        NotificationJob snooze = onlySnooze(source.getPayment().getId());
        assertThat(snooze.getKind()).isEqualTo(NotificationKind.SNOOZE);
        assertThat(snooze.getSourceNotificationId()).isEqualTo(source.getId());
        assertThat(snooze.getPeriodId()).isEqualTo(source.getPeriodId());
        assertThat(snooze.getScheduledDate()).isEqualTo(source.getScheduledDate());
        assertThat(snooze.getDaysBefore()).isNull();
        assertThat(snooze.getStatus()).isEqualTo(NotificationStatus.PENDING);

        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);
        assertThat(snooze.getNotificationDate()).isEqualTo(tomorrow);
        assertThat(snooze.getAvailableAt())
                .isEqualTo(tomorrow.atTime(NOTIFICATION_TIME).atZone(ZONE).toInstant());
        assertThat(snooze.getAvailableAt()).isAfter(Instant.now());
    }

    @Test
    void secondSnoozeOfTheSameMessageIsIgnored() {
        NotificationJob source = createSourceJob();

        assertThat(snoozeService.snoozeUntilTomorrow(source)).isTrue();
        assertThat(snoozeService.snoozeUntilTomorrow(source)).isFalse();

        assertThat(jobRepository.findByPaymentIdAndStatus(source.getPayment().getId(),
                NotificationStatus.PENDING)).filteredOn(job -> job.getKind() == NotificationKind.SNOOZE)
                .hasSize(1);
    }

    @Test
    void snoozingASnoozeCreatesTheNextOne() {
        NotificationJob source = createSourceJob();
        snoozeService.snoozeUntilTomorrow(source);
        NotificationJob snooze = onlySnooze(source.getPayment().getId());

        assertThat(snoozeService.snoozeUntilTomorrow(snooze)).isTrue();

        List<NotificationJob> snoozes = jobRepository.findByPaymentIdAndStatus(source.getPayment().getId(),
                NotificationStatus.PENDING).stream().filter(job -> job.getKind() == NotificationKind.SNOOZE)
                .toList();
        assertThat(snoozes).hasSize(2);
        assertThat(snoozes).extracting(NotificationJob::getSourceNotificationId)
                .containsExactlyInAnyOrder(source.getId(), snooze.getId());
    }

    private NotificationJob onlySnooze(long paymentId) {
        return jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING).stream()
                .filter(job -> job.getKind() == NotificationKind.SNOOZE)
                .findFirst()
                .orElseThrow();
    }

    private NotificationJob createSourceJob() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 9, 25), List.of(5))).id();
        planningService.plan(LocalDate.of(2026, 9, 20));
        return jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING).get(0);
    }
}
