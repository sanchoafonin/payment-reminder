package com.paymentreminder.notification.telegram;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.paymentreminder.history.dto.PaymentHistoryResponse;
import com.paymentreminder.notification.entity.WizardStep;
import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.entity.Recurrence;
import com.paymentreminder.payment.service.PaymentQueryService.UnpaidItem;
import com.paymentreminder.payment.service.PaymentQueryService.UnpaidSummary;
import org.springframework.stereotype.Component;

/** Renders the Telegram control menu: navigation, payment cards, history and wizard prompts. */
@Component
public class TelegramMenuFormatter {

    public static final String M_HOME = "m:home";
    public static final String M_UNPAID = "m:unpaid";
    public static final String M_ALL = "m:all";
    public static final String M_HELP = "m:help";
    public static final String P_CARD = "p:card:";
    public static final String P_HIST = "p:hist:";
    public static final String P_EDIT = "p:edit:";
    public static final String P_OFF = "p:off:";
    public static final String P_ON = "p:on:";
    public static final String W_ADD = "w:add";
    public static final String W_CANCEL = "w:cancel";
    public static final String W_SAVE = "w:save";
    public static final String W_CUR = "w:cur:";
    public static final String W_REC = "w:rec:";
    public static final String W_FIELD = "w:field:";

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE_WITH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RUSSIAN);
    private static final DateTimeFormatter DATE_WITHOUT_YEAR = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_BUTTON_TEXT = 40;
    private static final int MAX_LIST_ITEMS = 20;

    public TelegramScreen home() {
        return new TelegramScreen("""
                💳 Payment Reminder

                Выберите действие:""",
                rows(List.of(button("📋 Неоплаченные", M_UNPAID), button("➕ Добавить", W_ADD)),
                        List.of(button("📚 Все платежи", M_ALL), button("❓ Помощь", M_HELP))));
    }

    public TelegramScreen help() {
        return new TelegramScreen("""
                ❓ Помощь

                /start — главное меню
                /list — неоплаченные за месяц
                /add — добавить платёж
                /cancel — отменить ввод
                /help — эта справка""",
                rows(List.of(button("🏠 Меню", M_HOME))));
    }

    public TelegramScreen unpaid(UnpaidSummary summary, LocalDate today) {
        StringBuilder text = new StringBuilder("📋 Неоплаченные\n");
        text.append("\n🔴 Просрочено:\n");
        appendItems(text, summary.overdue(), today);
        text.append("\n🗓 Этот месяц:\n");
        appendItems(text, summary.thisMonth(), today);

        List<List<TelegramButton>> rows = new ArrayList<>();
        List<UnpaidItem> items = new ArrayList<>(summary.overdue());
        items.addAll(summary.thisMonth());
        rows.addAll(itemRows(items));
        rows.add(List.of(button("🏠 Меню", M_HOME)));
        return new TelegramScreen(text.toString(), new TelegramKeyboard(rows));
    }

    public TelegramScreen all(List<Payment> payments) {
        if (payments.isEmpty()) {
            return new TelegramScreen("📚 Платежей пока нет.", rows(List.of(button("🏠 Меню", M_HOME))));
        }
        StringBuilder text = new StringBuilder("📚 Все платежи\n");
        List<UnpaidItem> items = new ArrayList<>();
        for (Payment payment : payments) {
            String status = payment.isActive() ? "✅" : "⏸";
            text.append("\n").append(status).append(" ").append(payment.getName()).append(" — ")
                    .append(money(payment.getAmount(), payment.getCurrency())).append(" — ")
                    .append(payment.getNextPaymentDate().format(DATE_WITH_YEAR));
            if (!payment.isActive()) {
                text.append(" (неактивен)");
            }
            items.add(new UnpaidItem(payment.getId(), payment.getName(), payment.getAmount(),
                    payment.getCurrency(), payment.getNextPaymentDate()));
        }
        List<List<TelegramButton>> rows = new ArrayList<>(itemRows(items));
        rows.add(List.of(button("🏠 Меню", M_HOME)));
        return new TelegramScreen(text.toString(), new TelegramKeyboard(rows));
    }

    public TelegramScreen card(Payment payment, List<Integer> reminders) {
        String text = """
                💳 %s

                Сумма: %s
                Периодичность: %s
                Дата платежа: %s
                Напоминания: %s
                Статус: %s""".formatted(
                payment.getName(),
                money(payment.getAmount(), payment.getCurrency()),
                recurrence(payment.getRecurrence()),
                payment.getNextPaymentDate().format(DATE_WITH_YEAR),
                remindersText(reminders),
                payment.isActive() ? "активен" : "неактивен");
        String id = Long.toString(payment.getId());
        List<List<TelegramButton>> rows = new ArrayList<>();
        rows.add(List.of(button("📜 История", P_HIST + id), button("✏️ Изменить", P_EDIT + id)));
        rows.add(List.of(payment.isActive()
                ? button("⏸ Деактивировать", P_OFF + id)
                : button("▶️ Активировать", P_ON + id)));
        rows.add(List.of(button("🏠 Меню", M_HOME)));
        return new TelegramScreen(text, new TelegramKeyboard(rows));
    }

    public TelegramScreen history(Payment payment, List<PaymentHistoryResponse> history) {
        StringBuilder text = new StringBuilder("📜 История: " + payment.getName() + "\n");
        if (history.isEmpty()) {
            text.append("\nПока нет оплат.");
        } else {
            for (PaymentHistoryResponse item : history) {
                text.append("\n• ").append(item.scheduledDate().format(SHORT_DATE)).append(" — ")
                        .append(money(item.amount(), item.currency()));
            }
        }
        return new TelegramScreen(text.toString(), rows(
                List.of(button("⬅️ К карточке", P_CARD + payment.getId())),
                List.of(button("🏠 Меню", M_HOME))));
    }

    public TelegramScreen editField(Payment payment) {
        String id = Long.toString(payment.getId());
        return new TelegramScreen("✏️ Что изменить в «" + payment.getName() + "»?",
                rows(List.of(button("Название", W_FIELD + "name"), button("Сумма", W_FIELD + "amount")),
                        List.of(button("Валюта", W_FIELD + "currency"),
                                button("Периодичность", W_FIELD + "recurrence")),
                        List.of(button("Дата", W_FIELD + "date"), button("Напоминания", W_FIELD + "reminders")),
                        List.of(button("⬅️ К карточке", P_CARD + id))));
    }

    public TelegramScreen wizardStep(WizardStep step, Map<String, String> draft) {
        return switch (step) {
            case NAME -> prompt("➕ Новый платёж\n\nВведите название платежа:");
            case AMOUNT -> prompt("Введите сумму, например 700 или 700.50:");
            case CURRENCY -> choice("Выберите валюту:",
                    List.of(button("₽ Рубль", W_CUR + "RUB"), button("$ Доллар", W_CUR + "USD"),
                            button("€ Евро", W_CUR + "EUR")));
            case RECURRENCE -> choice("Выберите периодичность:",
                    List.of(button("Ежемесячно", W_REC + "MONTHLY"), button("Ежегодно", W_REC + "YEARLY")));
            case DATE -> prompt("Введите дату следующего платежа (ГГГГ-ММ-ДД или ДД.ММ.ГГГГ):");
            case REMINDERS -> prompt("""
                    За сколько дней напоминать?
                    Например: 5,3,1. Отправьте «нет», чтобы не напоминать.""");
            case CONFIRM -> confirm(draft);
            case EDIT_NAME -> prompt("Введите новое название:");
            case EDIT_AMOUNT -> prompt("Введите новую сумму:");
            case EDIT_DATE -> prompt("Введите новую дату платежа (ГГГГ-ММ-ДД или ДД.ММ.ГГГГ):");
            case EDIT_REMINDERS -> prompt("Введите дни напоминаний, например 5,3,1. Или «нет»:");
            case EDIT_FIELD -> prompt("Выберите поле для изменения:");
        };
    }

    public TelegramScreen editChoice(WizardStep step) {
        return switch (step) {
            case CURRENCY -> choice("Выберите валюту:",
                    List.of(button("₽ Рубль", W_CUR + "RUB"), button("$ Доллар", W_CUR + "USD"),
                            button("€ Евро", W_CUR + "EUR")));
            case RECURRENCE -> choice("Выберите периодичность:",
                    List.of(button("Ежемесячно", W_REC + "MONTHLY"), button("Ежегодно", W_REC + "YEARLY")));
            default -> wizardStep(step, Map.of());
        };
    }

    public TelegramScreen error(String message) {
        return new TelegramScreen("⚠️ " + message, rows(List.of(button("🏠 Меню", M_HOME))));
    }

    private TelegramScreen confirm(Map<String, String> draft) {
        List<Integer> reminders = TelegramDraft.reminders(draft.get(TelegramDraft.REMINDERS));
        LocalDate date = TelegramDraft.parseDate(draft.get(TelegramDraft.DATE));
        String text = """
                Проверьте платёж:

                Название: %s
                Сумма: %s
                Периодичность: %s
                Дата платежа: %s
                Напоминания: %s

                Создать?""".formatted(
                draft.get(TelegramDraft.NAME),
                money(new BigDecimal(draft.get(TelegramDraft.AMOUNT)),
                        Currency.valueOf(draft.get(TelegramDraft.CURRENCY))),
                recurrence(Recurrence.valueOf(draft.get(TelegramDraft.RECURRENCE))),
                date.format(DATE_WITH_YEAR),
                remindersText(reminders));
        return new TelegramScreen(text, rows(
                List.of(button("✅ Создать", W_SAVE), button("❌ Отмена", W_CANCEL))));
    }

    private TelegramScreen prompt(String text) {
        return new TelegramScreen(text, rows(List.of(button("❌ Отмена", W_CANCEL))));
    }

    private TelegramScreen choice(String text, List<TelegramButton> choices) {
        List<List<TelegramButton>> rows = new ArrayList<>();
        rows.add(choices);
        rows.add(List.of(button("❌ Отмена", W_CANCEL)));
        return new TelegramScreen(text, new TelegramKeyboard(rows));
    }

    private void appendItems(StringBuilder text, List<UnpaidItem> items, LocalDate today) {
        if (items.isEmpty()) {
            text.append("нет\n");
            return;
        }
        for (UnpaidItem item : items) {
            text.append("• ").append(item.name()).append(" — ")
                    .append(money(item.amount(), item.currency())).append(" — ")
                    .append(item.nextPaymentDate().format(DATE_WITHOUT_YEAR));
            if (item.nextPaymentDate().isBefore(today)) {
                long days = ChronoUnit.DAYS.between(item.nextPaymentDate(), today);
                text.append(" (просрочено на ").append(days).append(" ").append(plural(days, "день", "дня", "дней"))
                        .append(")");
            }
            text.append("\n");
        }
    }

    private List<List<TelegramButton>> itemRows(List<UnpaidItem> items) {
        List<List<TelegramButton>> rows = new ArrayList<>();
        for (UnpaidItem item : items.stream().limit(MAX_LIST_ITEMS).toList()) {
            rows.add(List.of(button(truncate(item.name()), P_CARD + item.id())));
        }
        return rows;
    }

    private String remindersText(List<Integer> reminders) {
        if (reminders == null || reminders.isEmpty()) {
            return "без напоминаний";
        }
        return "за " + reminders.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private String recurrence(Recurrence recurrence) {
        return switch (recurrence) {
            case MONTHLY -> "ежемесячно";
            case YEARLY -> "ежегодно";
        };
    }

    private String money(BigDecimal amount, Currency currency) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        return format.format(amount) + " " + switch (currency) {
            case RUB -> "₽";
            case USD -> "$";
            case EUR -> "€";
        };
    }

    private String plural(long value, String one, String few, String many) {
        long mod100 = Math.abs(value) % 100;
        long mod10 = Math.abs(value) % 10;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) {
            return few;
        }
        return many;
    }

    private String truncate(String value) {
        return value.length() <= MAX_BUTTON_TEXT ? value : value.substring(0, MAX_BUTTON_TEXT - 1) + "…";
    }

    private TelegramButton button(String text, String callbackData) {
        return new TelegramButton(text, callbackData);
    }

    @SafeVarargs
    private TelegramKeyboard rows(List<TelegramButton>... rows) {
        List<List<TelegramButton>> result = new ArrayList<>();
        for (List<TelegramButton> row : rows) {
            result.add(row);
        }
        return new TelegramKeyboard(result);
    }
}
