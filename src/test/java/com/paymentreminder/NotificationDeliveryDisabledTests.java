package com.paymentreminder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.notification.service.NotificationDeliveryService;
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

/** With no Telegram credentials configured, nothing is delivered and jobs stay pending. */
class NotificationDeliveryDisabledTests extends PostgresIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReminderPlanningService planningService;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationJobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void deliveryIsSkippedWhenTelegramIsNotConfigured() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 9, 25), List.of(5))).id();
        planningService.plan(LocalDate.of(2026, 9, 20));
        Long jobId = jdbc.queryForObject(
                "select id from notification_jobs where payment_id = ?", Long.class, paymentId);
        jdbc.update("update notification_jobs set available_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), jobId);

        assertThat(deliveryService.deliverDue()).isZero();

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.PENDING);
    }
}
