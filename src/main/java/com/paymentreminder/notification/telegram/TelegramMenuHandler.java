package com.paymentreminder.notification.telegram;

import java.time.Clock;
import java.time.LocalDate;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.history.dto.PaymentHistoryResponse;
import com.paymentreminder.history.service.PaymentHistoryService;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.service.PaymentNotFoundException;
import com.paymentreminder.payment.service.PaymentQueryService;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Handles menu navigation ({@code m:}) and per-payment actions ({@code p:}). */
@Component
public class TelegramMenuHandler {

    private static final int HISTORY_LIMIT = 10;

    private final TelegramMenuFormatter formatter;
    private final TelegramWizardHandler wizardHandler;
    private final PaymentQueryService queryService;
    private final PaymentService paymentService;
    private final PaymentHistoryService historyService;
    private final ReminderService reminderService;
    private final ApplicationProperties applicationProperties;
    private final Clock clock;

    public TelegramMenuHandler(TelegramMenuFormatter formatter, TelegramWizardHandler wizardHandler,
            PaymentQueryService queryService, PaymentService paymentService, PaymentHistoryService historyService,
            ReminderService reminderService, ApplicationProperties applicationProperties, Clock clock) {
        this.formatter = formatter;
        this.wizardHandler = wizardHandler;
        this.queryService = queryService;
        this.paymentService = paymentService;
        this.historyService = historyService;
        this.reminderService = reminderService;
        this.applicationProperties = applicationProperties;
        this.clock = clock;
    }

    public TelegramCallbackResult handleCallback(TelegramCallbackQuery query) {
        String data = query.data() == null ? "" : query.data();
        try {
            if (data.equals(TelegramMenuFormatter.M_HOME)) {
                return TelegramCallbackResult.screen(formatter.home());
            }
            if (data.equals(TelegramMenuFormatter.M_UNPAID)) {
                return TelegramCallbackResult.screen(unpaidScreen());
            }
            if (data.equals(TelegramMenuFormatter.M_ALL)) {
                return TelegramCallbackResult.screen(formatter.all(queryService.allPayments()));
            }
            if (data.equals(TelegramMenuFormatter.M_HELP)) {
                return TelegramCallbackResult.screen(formatter.help());
            }
            if (data.startsWith(TelegramMenuFormatter.P_CARD)) {
                return TelegramCallbackResult.screen(card(id(data, TelegramMenuFormatter.P_CARD)));
            }
            if (data.startsWith(TelegramMenuFormatter.P_HIST)) {
                return TelegramCallbackResult.screen(history(id(data, TelegramMenuFormatter.P_HIST)));
            }
            if (data.startsWith(TelegramMenuFormatter.P_EDIT)) {
                Payment payment = require(id(data, TelegramMenuFormatter.P_EDIT));
                return TelegramCallbackResult.screen(wizardHandler.startEdit(query.chatId(), payment));
            }
            if (data.startsWith(TelegramMenuFormatter.P_OFF)) {
                paymentService.deactivate(id(data, TelegramMenuFormatter.P_OFF));
                return TelegramCallbackResult.screen(card(id(data, TelegramMenuFormatter.P_OFF)));
            }
            if (data.startsWith(TelegramMenuFormatter.P_ON)) {
                activate(id(data, TelegramMenuFormatter.P_ON));
                return TelegramCallbackResult.screen(card(id(data, TelegramMenuFormatter.P_ON)));
            }
            return TelegramCallbackResult.alert("Неизвестная команда");
        } catch (PaymentNotFoundException exception) {
            return TelegramCallbackResult.alert("Платёж не найден");
        }
    }

    public TelegramScreen unpaidScreen() {
        LocalDate today = LocalDate.now(clock.withZone(applicationProperties.timeZone()));
        return formatter.unpaid(queryService.unpaid(today), today);
    }

    private void activate(Long paymentId) {
        PaymentResponse payment = paymentService.get(paymentId);
        paymentService.update(paymentId, new PaymentUpdateRequest(payment.name(), payment.amount(), payment.currency(),
                payment.recurrence(), payment.nextPaymentDate(), payment.reminders(), true));
    }

    private TelegramScreen card(Long paymentId) {
        return formatter.card(require(paymentId), reminderService.daysForPayment(paymentId));
    }

    private TelegramScreen history(Long paymentId) {
        Payment payment = require(paymentId);
        var history = historyService.history(paymentId, PageRequest.of(0, HISTORY_LIMIT));
        return formatter.history(payment, history.getContent());
    }

    private Payment require(Long paymentId) {
        return queryService.find(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private Long id(String data, String prefix) {
        return Long.valueOf(data.substring(prefix.length()));
    }
}
