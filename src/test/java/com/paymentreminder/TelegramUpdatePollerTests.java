package com.paymentreminder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.notification.service.TelegramConversationService;
import com.paymentreminder.notification.service.TelegramPollingStateService;
import com.paymentreminder.notification.telegram.TelegramCallbackQuery;
import com.paymentreminder.notification.telegram.TelegramClient;
import com.paymentreminder.notification.telegram.TelegramMessage;
import com.paymentreminder.notification.telegram.TelegramUpdate;
import com.paymentreminder.notification.telegram.TelegramUpdatePoller;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {
        "app.polling-enabled=true",
        "app.polling-delay-ms=3600000",
        "telegram.bot-token=test-token",
        "telegram.chat-id=999"})
class TelegramUpdatePollerTests extends PostgresIntegrationTest {

    @Autowired
    private TelegramUpdatePoller poller;

    @Autowired
    private TelegramPollingStateService pollingStateService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReminderPlanningService planningService;

    @Autowired
    private NotificationJobRepository jobRepository;

    @Autowired
    private TelegramConversationService conversations;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TelegramClient telegramClient;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE telegram_polling_state");
        jdbc.execute("TRUNCATE telegram_conversations");
    }

    @Test
    void processesCallbackAndPersistsOffset() {
        long paymentId = createPlannedJob();
        long jobId = jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING)
                .get(0).getId();
        given(telegramClient.getUpdates(eq(0L), any(Duration.class))).willReturn(List.of(
                new TelegramUpdate(10L, new TelegramCallbackQuery("cb-1", "pay:" + jobId, 999L, 100))));
        given(telegramClient.getUpdates(eq(11L), any(Duration.class))).willReturn(List.of());

        poller.poll();

        assertThat(pollingStateService.nextUpdateId()).isEqualTo(11);
        verify(telegramClient).answerCallbackQuery(eq("cb-1"), contains("Оплачено"), eq(false));
        verify(telegramClient).clearInlineKeyboard(999L, 100);
        assertThat(paymentService.get(paymentId).nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));

        poller.poll();

        verify(telegramClient).getUpdates(eq(11L), any(Duration.class));
        assertThat(pollingStateService.nextUpdateId()).isEqualTo(11);
    }

    @Test
    void ignoresUpdatesWithoutCallbackQuery() {
        given(telegramClient.getUpdates(eq(0L), any(Duration.class)))
                .willReturn(List.of(new TelegramUpdate(5L, null)));

        poller.poll();

        assertThat(pollingStateService.nextUpdateId()).isEqualTo(6);
        verify(telegramClient, never()).answerCallbackQuery(any(), any(), eq(false));
    }

    @Test
    void wizardTypedAnswersEditThePromptMessageInPlace() {
        given(telegramClient.sendMessage(anyString(), any())).willReturn(55L);
        given(telegramClient.getUpdates(eq(0L), any(Duration.class))).willReturn(List.of(
                new TelegramUpdate(1L, null, new TelegramMessage(999L, 1, 999L, "/add")),
                new TelegramUpdate(2L, null, new TelegramMessage(999L, 2, 999L, "Ипотека")),
                new TelegramUpdate(3L, null, new TelegramMessage(999L, 3, 999L, "35300"))));

        poller.poll();

        verify(telegramClient, times(1)).sendMessage(anyString(), any());
        verify(telegramClient, times(2)).editMessageText(eq(999L), eq(55), anyString(), any());
        assertThat(conversations.find(999L).orElseThrow().promptMessageId()).isEqualTo(55);
    }

    @Test
    void callbackStartedWizardTracksItsPromptMessage() {
        given(telegramClient.getUpdates(eq(0L), any(Duration.class))).willReturn(List.of(
                new TelegramUpdate(1L, new TelegramCallbackQuery("cb-1", "w:add", 999L, 100)),
                new TelegramUpdate(2L, null, new TelegramMessage(999L, 2, 999L, "Ипотека"))));

        poller.poll();

        verify(telegramClient, never()).sendMessage(anyString(), any());
        verify(telegramClient).editMessageText(eq(999L), eq(100), contains("название"), any());
        verify(telegramClient).editMessageText(eq(999L), eq(100), contains("сумму"), any());
        assertThat(conversations.find(999L).orElseThrow().promptMessageId()).isEqualTo(100);
    }

    @Test
    void repointsPromptWhenEditingTheMessageFails() {
        given(telegramClient.sendMessage(anyString(), any())).willReturn(55L, 66L);
        doThrow(new RuntimeException("edit failed")).when(telegramClient)
                .editMessageText(eq(999L), anyInt(), anyString(), any());
        given(telegramClient.getUpdates(eq(0L), any(Duration.class))).willReturn(List.of(
                new TelegramUpdate(1L, null, new TelegramMessage(999L, 1, 999L, "/add")),
                new TelegramUpdate(2L, null, new TelegramMessage(999L, 2, 999L, "Ипотека"))));

        poller.poll();

        verify(telegramClient, times(2)).sendMessage(anyString(), any());
        assertThat(conversations.find(999L).orElseThrow().promptMessageId()).isEqualTo(66);
    }

    private long createPlannedJob() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 9, 25), List.of(5))).id();
        planningService.plan(LocalDate.of(2026, 9, 20));
        return paymentId;
    }
}
