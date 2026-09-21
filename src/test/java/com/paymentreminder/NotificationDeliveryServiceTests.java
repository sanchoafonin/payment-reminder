package com.paymentreminder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.notification.service.NotificationDeliveryService;
import com.paymentreminder.notification.telegram.TelegramClient;
import com.paymentreminder.notification.telegram.TelegramDeliveryException;
import com.paymentreminder.notification.telegram.TelegramKeyboard;
import com.paymentreminder.notification.telegram.TelegramUncertainException;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@TestPropertySource(properties = {
        "telegram.bot-token=test-token",
        "telegram.chat-id=12345",
        "telegram.retry-delay=PT0S"})
class NotificationDeliveryServiceTests extends PostgresIntegrationTest {

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

    @MockitoBean
    private TelegramClient telegramClient;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void deliversDueNotificationAndRecordsMessageId() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any())).willReturn(777L);

        int sent = deliveryService.deliverDue();

        assertThat(sent).isEqualTo(1);
        NotificationJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(job.getTelegramMessageId()).isEqualTo(777L);
        assertThat(job.getSentAt()).isNotNull();
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getClaimToken()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
    }

    @Test
    void sendsFormattedMessageWithButtons() {
        createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);

        deliveryService.deliverDue();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TelegramKeyboard> keyboard = ArgumentCaptor.forClass(TelegramKeyboard.class);
        verify(telegramClient).sendMessage(text.capture(), keyboard.capture());
        assertThat(text.getValue()).contains("💳 Предстоящий платеж").contains("Ипотека");
        assertThat(keyboard.getValue().rows().get(0)).extracting(button -> button.callbackData())
                .containsExactly("pay:1", "snooze:1");
    }

    @Test
    void doesNotResendAlreadySentNotification() {
        createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);

        deliveryService.deliverDue();
        int secondRun = deliveryService.deliverDue();

        assertThat(secondRun).isZero();
        verify(telegramClient).sendMessage(anyString(), any());
    }

    @Test
    void requeuesRetryableFailureUntilAttemptsAreExhausted() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any()))
                .willThrow(new TelegramDeliveryException("Telegram is unreachable", true));

        assertThat(deliveryService.deliverDue()).isZero();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.PENDING);

        assertThat(deliveryService.deliverDue()).isZero();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.PENDING);

        assertThat(deliveryService.deliverDue()).isZero();
        NotificationJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getLastError()).isNotBlank();

        assertThat(deliveryService.deliverDue()).isZero();
    }

    @Test
    void failsImmediatelyOnPermanentError() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any()))
                .willThrow(new TelegramDeliveryException("Telegram API rejected the message", false));

        deliveryService.deliverDue();

        NotificationJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void parksUncertainOutcomeAsUnknownWithoutRetrying() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        given(telegramClient.sendMessage(anyString(), any()))
                .willThrow(new TelegramUncertainException("Telegram delivery outcome is unknown"));

        deliveryService.deliverDue();
        assertThat(deliveryService.deliverDue()).isZero();

        NotificationJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verify(telegramClient).sendMessage(anyString(), any());
    }

    @Test
    void parksInterruptedProcessingJobAsUnknown() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        jdbc.update("""
                update notification_jobs
                set status = 'PROCESSING', claim_token = gen_random_uuid(),
                    lease_until = ?, attempt_count = 1
                where id = ?
                """, Timestamp.from(Instant.now().minusSeconds(60)), jobId);

        deliveryService.deliverDue();

        NotificationJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verifyNoInteractions(telegramClient);
    }

    @Test
    void ignoresJobThatBecomesDueOnlyInTheFuture() {
        long jobId = createDueJob(LocalDate.of(2026, 9, 25), 5);
        jdbc.update("update notification_jobs set available_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(3600)), jobId);

        assertThat(deliveryService.deliverDue()).isZero();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.PENDING);
        verify(telegramClient, never()).sendMessage(anyString(), any());
    }

    private long createDueJob(LocalDate scheduledDate, int daysBefore) {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY, scheduledDate,
                List.of(daysBefore))).id();
        planningService.plan(scheduledDate.minusDays(daysBefore));
        Long jobId = jdbc.queryForObject(
                "select id from notification_jobs where payment_id = ?", Long.class, paymentId);
        jdbc.update("update notification_jobs set available_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), jobId);
        return jobId;
    }
}
