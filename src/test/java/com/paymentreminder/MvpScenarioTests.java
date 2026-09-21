package com.paymentreminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.notification.service.NotificationDeliveryService;
import com.paymentreminder.notification.telegram.TelegramCallbackHandler;
import com.paymentreminder.notification.telegram.TelegramCallbackQuery;
import com.paymentreminder.notification.telegram.TelegramClient;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** End-to-end scenarios from the acceptance criteria of the specification. */
@TestPropertySource(properties = {"telegram.bot-token=test-token", "telegram.chat-id=999"})
class MvpScenarioTests extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReminderPlanningService planningService;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private TelegramCallbackHandler callbackHandler;

    @Autowired
    private NotificationJobRepository jobRepository;

    @Autowired
    private PaymentHistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TelegramClient telegramClient;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void paidButtonClosesPeriodAndOldNotificationIsNotResent() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 9, 25), List.of(5, 3, 1))).id();
        planningService.plan(LocalDate.of(2026, 9, 20));
        NotificationJob job = pendingJobs(paymentId).get(0);
        makeDue(job.getId());
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);
        deliveryService.deliverDue();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(text.capture(), any());
        assertThat(text.getValue()).contains("💳 Предстоящий платеж").contains("Осталось: 5 дней");

        callbackHandler.handle(new TelegramCallbackQuery("cb-1", "pay:" + job.getId(), 999L, 100));

        PaymentResponse payment = paymentService.get(paymentId);
        assertThat(payment.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(historyRepository.findByPaymentIdAndPeriodId(paymentId, job.getPeriodId())).isPresent();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.SENT);

        assertThat(planningService.plan(LocalDate.of(2026, 9, 20)).regularJobs()).isZero();
        assertThat(deliveryService.deliverDue()).isZero();
    }

    @Test
    void snoozeButtonPersistsReminderForNextDay() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 10, 25), List.of(5))).id();
        planningService.plan(LocalDate.of(2026, 10, 20));
        NotificationJob job = pendingJobs(paymentId).get(0);
        makeDue(job.getId());
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);
        deliveryService.deliverDue();

        callbackHandler.handle(new TelegramCallbackQuery("cb-1", "snooze:" + job.getId(), 999L, 100));

        NotificationJob snooze = pendingJobs(paymentId).stream()
                .filter(pending -> pending.getKind() == NotificationKind.SNOOZE)
                .findFirst()
                .orElseThrow();
        assertThat(snooze.getSourceNotificationId()).isEqualTo(job.getId());
        assertThat(snooze.getScheduledDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(snooze.getNotificationDate()).isEqualTo(LocalDate.now(ZONE).plusDays(1));

        PaymentResponse payment = paymentService.get(paymentId);
        assertThat(payment.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(payment.reminders()).containsExactly(5);
    }

    private List<NotificationJob> pendingJobs(long paymentId) {
        return jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING);
    }

    private void makeDue(long jobId) {
        jdbc.update("update notification_jobs set available_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), jobId);
    }
}
