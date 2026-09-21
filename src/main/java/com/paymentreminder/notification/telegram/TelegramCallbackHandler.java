package com.paymentreminder.notification.telegram;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import com.paymentreminder.common.config.TelegramProperties;
import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.payment.dto.PaymentPayResponse;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.service.PaymentNotActiveException;
import com.paymentreminder.payment.service.PaymentNotFoundException;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.payment.service.StalePaymentPeriodException;
import com.paymentreminder.reminder.service.SnoozeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Telegram callback queries. It reuses the same payment service as the REST API, so the
 * "paid" button never duplicates business logic. Network calls are left to the caller.
 */
@Component
public class TelegramCallbackHandler {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy",
            Locale.forLanguageTag("ru"));

    private final TelegramProperties telegramProperties;
    private final NotificationJobRepository jobRepository;
    private final PaymentHistoryRepository historyRepository;
    private final PaymentService paymentService;
    private final SnoozeService snoozeService;

    public TelegramCallbackHandler(TelegramProperties telegramProperties,
            NotificationJobRepository jobRepository, PaymentHistoryRepository historyRepository,
            PaymentService paymentService, SnoozeService snoozeService) {
        this.telegramProperties = telegramProperties;
        this.jobRepository = jobRepository;
        this.historyRepository = historyRepository;
        this.paymentService = paymentService;
        this.snoozeService = snoozeService;
    }

    @Transactional
    public TelegramCallbackResult handle(TelegramCallbackQuery query) {
        if (!isAllowedChat(query.chatId())) {
            return TelegramCallbackResult.alert("Действие недоступно");
        }
        return TelegramCallbackData.parse(query.data())
                .map(this::dispatch)
                .orElseGet(() -> TelegramCallbackResult.alert("Неизвестная команда"));
    }

    private TelegramCallbackResult dispatch(TelegramCallbackData data) {
        return switch (data.action()) {
            case PAY -> handlePay(data.notificationId());
            case SNOOZE -> handleSnooze(data.notificationId());
        };
    }

    private TelegramCallbackResult handleSnooze(long notificationId) {
        NotificationJob source = jobRepository.findById(notificationId).orElse(null);
        if (source == null) {
            return TelegramCallbackResult.alert("Уведомление не найдено");
        }
        Payment payment = source.getPayment();
        if (!source.getPeriodId().equals(payment.getCurrentPeriodId())) {
            boolean alreadyPaid = historyRepository
                    .findByPaymentIdAndPeriodId(payment.getId(), source.getPeriodId()).isPresent();
            return TelegramCallbackResult.alert(
                    alreadyPaid ? "Этот период уже оплачен" : "Уведомление устарело");
        }
        if (!payment.isActive()) {
            return TelegramCallbackResult.alert("Платёж неактивен");
        }
        boolean created = snoozeService.snoozeUntilTomorrow(source);
        return TelegramCallbackResult.info(created ? "⏰ Напомню завтра" : "Перенос уже создан");
    }

    private TelegramCallbackResult handlePay(long notificationId) {
        NotificationJob job = jobRepository.findById(notificationId).orElse(null);
        if (job == null) {
            return TelegramCallbackResult.alert("Уведомление не найдено");
        }
        Payment payment = job.getPayment();
        UUID periodId = job.getPeriodId();

        if (!periodId.equals(payment.getCurrentPeriodId())) {
            boolean alreadyPaid = historyRepository.findByPaymentIdAndPeriodId(payment.getId(), periodId)
                    .isPresent();
            return TelegramCallbackResult.alert(
                    alreadyPaid ? "Этот период уже оплачен" : "Уведомление устарело");
        }
        if (!payment.isActive()) {
            return TelegramCallbackResult.alert("Платёж неактивен");
        }
        try {
            PaymentPayResponse response = paymentService.pay(payment.getId(), periodId);
            return TelegramCallbackResult.paid("✅ Оплачено: " + payment.getName()
                    + ". Следующая дата: " + response.payment().nextPaymentDate().format(DATE));
        } catch (PaymentNotActiveException exception) {
            return TelegramCallbackResult.alert("Платёж неактивен");
        } catch (StalePaymentPeriodException exception) {
            return TelegramCallbackResult.alert("Уведомление устарело");
        } catch (PaymentNotFoundException exception) {
            return TelegramCallbackResult.alert("Платёж не найден");
        }
    }

    private boolean isAllowedChat(Long chatId) {
        return chatId != null && telegramProperties.chatId() != null
                && telegramProperties.chatId().equals(Long.toString(chatId));
    }
}
