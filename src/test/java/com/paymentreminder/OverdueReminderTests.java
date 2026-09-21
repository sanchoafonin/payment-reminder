package com.paymentreminder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

@TestPropertySource(properties = {"telegram.bot-token=test-token", "telegram.chat-id=999"})
class OverdueReminderTests extends PostgresIntegrationTest {

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
    private JdbcTemplate jdbc;

    @MockitoBean
    private TelegramClient telegramClient;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void deliversOverdueReminderWithOverdueText() {
        long paymentId = createOverduePayment();
        planningService.plan(LocalDate.of(2026, 9, 15));
        NotificationJob job = overdueJobs(paymentId).get(0);
        makeDue(job.getId());
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);

        assertThat(deliveryService.deliverDue()).isEqualTo(1);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(text.capture(), any());
        assertThat(text.getValue())
                .contains("🔴 Просроченный платеж")
                .contains("Просрочено: 5 дней")
                .contains("Дата платежа: 10 сентября");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void payingOverduePeriodCancelsRemainingOverdueJobs() {
        long paymentId = createOverduePayment();
        planningService.plan(LocalDate.of(2026, 9, 15));
        NotificationJob first = overdueJobs(paymentId).get(0);
        makeDue(first.getId());
        given(telegramClient.sendMessage(anyString(), any())).willReturn(1L);
        deliveryService.deliverDue();

        planningService.plan(LocalDate.of(2026, 9, 16));
        NotificationJob next = overdueJobs(paymentId).stream()
                .filter(job -> job.getStatus() == NotificationStatus.PENDING)
                .findFirst()
                .orElseThrow();

        callbackHandler.handle(new TelegramCallbackQuery("cb-1", "pay:" + first.getId(), 999L, 100));

        PaymentResponse payment = paymentService.get(paymentId);
        assertThat(payment.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(jobRepository.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.CANCELLED);
    }

    private long createOverduePayment() {
        return paymentService.create(new PaymentCreateRequest("Ипотека", new BigDecimal("35300.00"),
                Currency.RUB, Recurrence.MONTHLY, LocalDate.of(2026, 9, 10), List.of())).id();
    }

    private List<NotificationJob> overdueJobs(long paymentId) {
        return jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING).stream()
                .filter(job -> job.getKind() == NotificationKind.OVERDUE)
                .toList();
    }

    private void makeDue(long jobId) {
        jdbc.update("update notification_jobs set available_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), jobId);
    }
}
