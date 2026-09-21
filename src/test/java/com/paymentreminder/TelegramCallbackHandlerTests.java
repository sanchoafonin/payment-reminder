package com.paymentreminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.notification.telegram.TelegramCallbackHandler;
import com.paymentreminder.notification.telegram.TelegramCallbackQuery;
import com.paymentreminder.notification.telegram.TelegramCallbackResult;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"telegram.bot-token=test-token", "telegram.chat-id=999"})
class TelegramCallbackHandlerTests extends PostgresIntegrationTest {

    @Autowired
    private TelegramCallbackHandler handler;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReminderPlanningService planningService;

    @Autowired
    private NotificationJobRepository jobRepository;

    @Autowired
    private PaymentHistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void paysPeriodAndRemovesButtons() {
        JobFixture fixture = createPlannedJob();

        TelegramCallbackResult result = handler.handle(query("pay:" + fixture.jobId()));

        assertThat(result.alert()).isFalse();
        assertThat(result.clearKeyboard()).isTrue();
        assertThat(result.message()).contains("Оплачено").contains("Ипотека");
        PaymentResponse payment = paymentService.get(fixture.paymentId());
        assertThat(payment.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(historyRepository.findByPaymentIdAndPeriodId(fixture.paymentId(), fixture.periodId()))
                .isPresent();
    }

    @Test
    void repeatedTapReportsAlreadyPaid() {
        JobFixture fixture = createPlannedJob();

        handler.handle(query("pay:" + fixture.jobId()));
        TelegramCallbackResult second = handler.handle(query("pay:" + fixture.jobId()));

        assertThat(second.alert()).isTrue();
        assertThat(second.message()).isEqualTo("Этот период уже оплачен");
    }

    @Test
    void staleNotificationAfterPeriodChangeIsRejected() {
        JobFixture fixture = createPlannedJob();
        paymentService.update(fixture.paymentId(), new PaymentUpdateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 10, 20), List.of(5), true));

        TelegramCallbackResult result = handler.handle(query("pay:" + fixture.jobId()));

        assertThat(result.alert()).isTrue();
        assertThat(result.message()).isEqualTo("Уведомление устарело");
    }

    @Test
    void inactivePaymentIsRejected() {
        JobFixture fixture = createPlannedJob();
        paymentService.deactivate(fixture.paymentId());

        TelegramCallbackResult result = handler.handle(query("pay:" + fixture.jobId()));

        assertThat(result.alert()).isTrue();
        assertThat(result.message()).isEqualTo("Платёж неактивен");
    }

    @Test
    void unknownNotificationIsRejected() {
        TelegramCallbackResult result = handler.handle(query("pay:999999"));

        assertThat(result.alert()).isTrue();
        assertThat(result.message()).isEqualTo("Уведомление не найдено");
    }

    @Test
    void foreignChatIsRejected() {
        JobFixture fixture = createPlannedJob();

        TelegramCallbackResult result = handler.handle(
                new TelegramCallbackQuery("cb-1", "pay:" + fixture.jobId(), 111L, 100));

        assertThat(result.alert()).isTrue();
        assertThat(result.message()).isEqualTo("Действие недоступно");
    }

    @Test
    void snoozeCreatesNextDayJob() {
        JobFixture fixture = createPlannedJob();

        TelegramCallbackResult result = handler.handle(query("snooze:" + fixture.jobId()));

        assertThat(result.alert()).isFalse();
        assertThat(result.message()).isEqualTo("⏰ Напомню завтра");
        assertThat(snoozes(fixture.paymentId())).hasSize(1);
    }

    @Test
    void repeatedSnoozeIsIdempotent() {
        JobFixture fixture = createPlannedJob();

        handler.handle(query("snooze:" + fixture.jobId()));
        TelegramCallbackResult second = handler.handle(query("snooze:" + fixture.jobId()));

        assertThat(second.alert()).isFalse();
        assertThat(second.message()).isEqualTo("Перенос уже создан");
        assertThat(snoozes(fixture.paymentId())).hasSize(1);
    }

    @Test
    void snoozeOnStalePeriodIsRejected() {
        JobFixture fixture = createPlannedJob();
        paymentService.update(fixture.paymentId(), new PaymentUpdateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 10, 20), List.of(5), true));

        TelegramCallbackResult result = handler.handle(query("snooze:" + fixture.jobId()));

        assertThat(result.alert()).isTrue();
        assertThat(result.message()).isEqualTo("Уведомление устарело");
    }

    private List<NotificationJob> snoozes(long paymentId) {
        return jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING).stream()
                .filter(job -> job.getKind() == NotificationKind.SNOOZE)
                .toList();
    }

    private TelegramCallbackQuery query(String data) {
        return new TelegramCallbackQuery("cb-1", data, 999L, 100);
    }

    private JobFixture createPlannedJob() {
        long paymentId = paymentService.create(new PaymentCreateRequest("Ипотека",
                new BigDecimal("35300.00"), Currency.RUB, Recurrence.MONTHLY,
                LocalDate.of(2026, 9, 25), List.of(5))).id();
        planningService.plan(LocalDate.of(2026, 9, 20));
        NotificationJob job = jobRepository.findByPaymentIdAndStatus(paymentId, NotificationStatus.PENDING)
                .get(0);
        return new JobFixture(paymentId, job.getId(), job.getPeriodId());
    }

    private record JobFixture(long paymentId, long jobId, UUID periodId) {
    }
}
