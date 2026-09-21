package com.paymentreminder.notification.telegram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.paymentreminder.notification.entity.WizardStep;
import com.paymentreminder.notification.service.TelegramConversationService;
import com.paymentreminder.notification.service.TelegramConversationService.ActiveConversation;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentQueryService;
import com.paymentreminder.payment.service.PaymentService;
import com.paymentreminder.reminder.service.ReminderService;
import org.springframework.stereotype.Component;

/** Drives the add/edit wizard: button choices and typed answers, persisting state between steps. */
@Component
public class TelegramWizardHandler {

    private final TelegramConversationService conversations;
    private final TelegramMenuFormatter formatter;
    private final PaymentService paymentService;
    private final PaymentQueryService queryService;
    private final ReminderService reminderService;

    public TelegramWizardHandler(TelegramConversationService conversations, TelegramMenuFormatter formatter,
            PaymentService paymentService, PaymentQueryService queryService, ReminderService reminderService) {
        this.conversations = conversations;
        this.formatter = formatter;
        this.paymentService = paymentService;
        this.queryService = queryService;
        this.reminderService = reminderService;
    }

    public TelegramCallbackResult handleCallback(TelegramCallbackQuery query) {
        String data = query.data() == null ? "" : query.data();
        if (data.equals(TelegramMenuFormatter.W_ADD)) {
            return startAdd(query.chatId());
        }
        if (data.equals(TelegramMenuFormatter.W_CANCEL)) {
            conversations.clear(query.chatId());
            return TelegramCallbackResult.screen(formatter.home());
        }
        if (data.equals(TelegramMenuFormatter.W_SAVE)) {
            return saveNew(query.chatId());
        }
        if (data.startsWith(TelegramMenuFormatter.W_CUR)) {
            return chooseCurrency(query.chatId(), data.substring(TelegramMenuFormatter.W_CUR.length()));
        }
        if (data.startsWith(TelegramMenuFormatter.W_REC)) {
            return chooseRecurrence(query.chatId(), data.substring(TelegramMenuFormatter.W_REC.length()));
        }
        if (data.startsWith(TelegramMenuFormatter.W_FIELD)) {
            return chooseField(query.chatId(), data.substring(TelegramMenuFormatter.W_FIELD.length()));
        }
        return TelegramCallbackResult.alert("Неизвестная команда");
    }

    /** Handles a typed answer for the active wizard of the chat; returns the next screen to send. */
    public TelegramScreen handleText(ActiveConversation conversation, TelegramMessage message) {
        String text = message.text() == null ? "" : message.text().strip();
        Map<String, String> draft = conversation.draft();
        try {
            return switch (conversation.step()) {
                case NAME -> next(conversation.chatId(), WizardStep.AMOUNT, draft, TelegramDraft.NAME, requireName(text));
                case AMOUNT -> next(conversation.chatId(), WizardStep.CURRENCY, draft, TelegramDraft.AMOUNT,
                        requireAmount(text).toPlainString());
                case DATE -> next(conversation.chatId(), WizardStep.REMINDERS, draft, TelegramDraft.DATE,
                        requireDate(text).toString());
                case REMINDERS -> next(conversation.chatId(), WizardStep.CONFIRM, draft, TelegramDraft.REMINDERS,
                        parseReminders(text));
                case EDIT_NAME -> applyEdit(conversation.chatId(), draft, TelegramDraft.NAME, requireName(text));
                case EDIT_AMOUNT -> applyEdit(conversation.chatId(), draft, TelegramDraft.AMOUNT,
                        requireAmount(text).toPlainString());
                case EDIT_DATE -> applyEdit(conversation.chatId(), draft, TelegramDraft.DATE,
                        requireDate(text).toString());
                case EDIT_REMINDERS -> applyEdit(conversation.chatId(), draft, TelegramDraft.REMINDERS,
                        parseReminders(text));
                default -> formatter.home();
            };
        } catch (RuntimeException exception) {
            return retry(conversation.step(), draft, exception.getMessage());
        }
    }

    public TelegramScreen startEdit(long chatId, Payment payment) {
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put(TelegramDraft.NAME, payment.getName());
        draft.put(TelegramDraft.AMOUNT, payment.getAmount().toPlainString());
        draft.put(TelegramDraft.CURRENCY, payment.getCurrency().name());
        draft.put(TelegramDraft.RECURRENCE, payment.getRecurrence().name());
        draft.put(TelegramDraft.DATE, payment.getNextPaymentDate().toString());
        draft.put(TelegramDraft.REMINDERS,
                TelegramDraft.remindersToString(reminderService.daysForPayment(payment.getId())));
        draft.put(TelegramDraft.PAYMENT_ID, Long.toString(payment.getId()));
        draft.put(TelegramDraft.ACTIVE, Boolean.toString(payment.isActive()));
        draft.put(TelegramDraft.EDIT, "true");
        conversations.save(chatId, WizardStep.EDIT_FIELD, draft);
        return formatter.editField(payment);
    }

    private TelegramCallbackResult startAdd(long chatId) {
        conversations.save(chatId, WizardStep.NAME, new LinkedHashMap<>());
        return TelegramCallbackResult.screen(formatter.wizardStep(WizardStep.NAME, Map.of()));
    }

    private TelegramCallbackResult chooseCurrency(long chatId, String value) {
        Optional<ActiveConversation> active = conversations.find(chatId);
        if (active.isEmpty()) {
            return TelegramCallbackResult.screen(formatter.home());
        }
        Map<String, String> draft = active.get().draft();
        draft.put(TelegramDraft.CURRENCY, value);
        if (isEdit(draft)) {
            return edit(chatId, draft);
        }
        conversations.save(chatId, WizardStep.RECURRENCE, draft);
        return TelegramCallbackResult.screen(formatter.wizardStep(WizardStep.RECURRENCE, draft));
    }

    private TelegramCallbackResult chooseRecurrence(long chatId, String value) {
        Optional<ActiveConversation> active = conversations.find(chatId);
        if (active.isEmpty()) {
            return TelegramCallbackResult.screen(formatter.home());
        }
        Map<String, String> draft = active.get().draft();
        draft.put(TelegramDraft.RECURRENCE, value);
        if (isEdit(draft)) {
            return edit(chatId, draft);
        }
        conversations.save(chatId, WizardStep.DATE, draft);
        return TelegramCallbackResult.screen(formatter.wizardStep(WizardStep.DATE, draft));
    }

    private TelegramCallbackResult chooseField(long chatId, String field) {
        Optional<ActiveConversation> active = conversations.find(chatId);
        if (active.isEmpty()) {
            return TelegramCallbackResult.screen(formatter.home());
        }
        Map<String, String> draft = active.get().draft();
        WizardStep step = switch (field) {
            case "name" -> WizardStep.EDIT_NAME;
            case "amount" -> WizardStep.EDIT_AMOUNT;
            case "date" -> WizardStep.EDIT_DATE;
            case "reminders" -> WizardStep.EDIT_REMINDERS;
            case "currency", "recurrence" -> WizardStep.EDIT_FIELD;
            default -> null;
        };
        if (step == null) {
            return TelegramCallbackResult.alert("Неизвестное поле");
        }
        conversations.save(chatId, step, draft);
        if (step == WizardStep.EDIT_FIELD) {
            WizardStep choice = "currency".equals(field) ? WizardStep.CURRENCY : WizardStep.RECURRENCE;
            return TelegramCallbackResult.screen(formatter.editChoice(choice));
        }
        return TelegramCallbackResult.screen(formatter.wizardStep(step, draft));
    }

    private TelegramCallbackResult saveNew(long chatId) {
        Optional<ActiveConversation> active = conversations.find(chatId);
        if (active.isEmpty()) {
            return TelegramCallbackResult.screen(formatter.home());
        }
        Map<String, String> draft = active.get().draft();
        try {
            PaymentCreateRequest request = new PaymentCreateRequest(
                    draft.get(TelegramDraft.NAME),
                    new BigDecimal(draft.get(TelegramDraft.AMOUNT)),
                    Currency.valueOf(draft.get(TelegramDraft.CURRENCY)),
                    Recurrence.valueOf(draft.get(TelegramDraft.RECURRENCE)),
                    LocalDate.parse(draft.get(TelegramDraft.DATE)),
                    TelegramDraft.reminders(draft.get(TelegramDraft.REMINDERS)));
            PaymentResponse created = paymentService.create(request);
            conversations.clear(chatId);
            return TelegramCallbackResult.screen(card(created.id()));
        } catch (RuntimeException exception) {
            return TelegramCallbackResult.alert("Не удалось создать: " + exception.getMessage());
        }
    }

    private TelegramScreen next(long chatId, WizardStep step, Map<String, String> draft, String key, String value) {
        draft.put(key, value);
        conversations.save(chatId, step, draft);
        return formatter.wizardStep(step, draft);
    }

    private TelegramScreen applyEdit(long chatId, Map<String, String> draft, String key, String value) {
        draft.put(key, value);
        TelegramCallbackResult result = edit(chatId, draft);
        return result.screen() != null ? result.screen() : formatter.error(result.message());
    }

    private TelegramCallbackResult edit(long chatId, Map<String, String> draft) {
        try {
            Long paymentId = Long.valueOf(draft.get(TelegramDraft.PAYMENT_ID));
            PaymentUpdateRequest request = new PaymentUpdateRequest(
                    draft.get(TelegramDraft.NAME),
                    new BigDecimal(draft.get(TelegramDraft.AMOUNT)),
                    Currency.valueOf(draft.get(TelegramDraft.CURRENCY)),
                    Recurrence.valueOf(draft.get(TelegramDraft.RECURRENCE)),
                    LocalDate.parse(draft.get(TelegramDraft.DATE)),
                    TelegramDraft.reminders(draft.get(TelegramDraft.REMINDERS)),
                    Boolean.parseBoolean(draft.get(TelegramDraft.ACTIVE)));
            paymentService.update(paymentId, request);
            conversations.clear(chatId);
            return TelegramCallbackResult.screen(card(paymentId));
        } catch (RuntimeException exception) {
            return TelegramCallbackResult.alert("Не удалось сохранить: " + exception.getMessage());
        }
    }

    private TelegramScreen card(Long paymentId) {
        Payment payment = queryService.find(paymentId).orElseThrow();
        return formatter.card(payment, reminderService.daysForPayment(paymentId));
    }

    private boolean isEdit(Map<String, String> draft) {
        return "true".equals(draft.get(TelegramDraft.EDIT));
    }

    private TelegramScreen retry(WizardStep step, Map<String, String> draft, String reason) {
        TelegramScreen base = formatter.wizardStep(step, draft);
        String message = reason == null || reason.isBlank() ? "Неверный ввод" : reason;
        return new TelegramScreen("⚠️ " + message + "\n\n" + base.text(), base.keyboard());
    }

    private String requireName(String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("Название не может быть пустым");
        }
        return text;
    }

    private BigDecimal requireAmount(String text) {
        try {
            BigDecimal amount = new BigDecimal(text.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Сумма должна быть больше нуля");
            }
            return amount;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Введите сумму, например 700 или 700.50");
        }
    }

    private LocalDate requireDate(String text) {
        try {
            return TelegramDraft.parseDate(text);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Введите дату в формате ГГГГ-ММ-ДД или ДД.ММ.ГГГГ");
        }
    }

    private String parseReminders(String text) {
        if (text.equalsIgnoreCase("нет") || text.equalsIgnoreCase("no") || text.equals("-")) {
            return "";
        }
        List<Integer> reminders = TelegramDraft.reminders(text);
        return TelegramDraft.remindersToString(reminders);
    }
}
