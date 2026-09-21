package com.paymentreminder.notification.telegram;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.notification.service.NotificationMessage;
import com.paymentreminder.payment.entity.Currency;
import org.springframework.stereotype.Component;

/** Renders notifications and their inline keyboard exactly as described in the specification. */
@Component
public class TelegramMessageFormatter {

    public static final String PAY_PREFIX = "pay:";
    public static final String SNOOZE_PREFIX = "snooze:";

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE_WITH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RUSSIAN);
    private static final DateTimeFormatter DATE_WITHOUT_YEAR = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN);

    public String format(NotificationMessage message) {
        return switch (message.kind()) {
            case REGULAR, SNOOZE -> formatUpcoming(message);
            case OVERDUE -> formatOverdue(message);
        };
    }

    public TelegramKeyboard keyboard(long notificationId) {
        String id = Long.toString(notificationId);
        return new TelegramKeyboard(List.of(List.of(
                new TelegramButton("✅ Оплачено", PAY_PREFIX + id),
                new TelegramButton("⏰ Напомнить завтра", SNOOZE_PREFIX + id))));
    }

    private String formatUpcoming(NotificationMessage message) {
        long remaining = ChronoUnit.DAYS.between(message.notificationDate(), message.scheduledDate());
        if (remaining < 0) {
            return formatOverdue(message);
        }
        return """
                💳 Предстоящий платеж

                %s
                %s

                Дата платежа: %s
                Осталось: %d %s""".formatted(
                message.paymentName(),
                money(message.amount(), message.currency()),
                message.scheduledDate().format(DATE_WITH_YEAR),
                remaining,
                plural(remaining, "день", "дня", "дней"));
    }

    private String formatOverdue(NotificationMessage message) {
        long daysOverdue = Math.max(0,
                ChronoUnit.DAYS.between(message.scheduledDate(), message.notificationDate()));
        return """
                🔴 Просроченный платеж

                %s
                %s

                Дата платежа: %s
                Просрочено: %d %s""".formatted(
                message.paymentName(),
                money(message.amount(), message.currency()),
                message.scheduledDate().format(DATE_WITHOUT_YEAR),
                daysOverdue,
                plural(daysOverdue, "день", "дня", "дней"));
    }

    private String money(BigDecimal amount, Currency currency) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        return format.format(amount) + " " + symbol(currency);
    }

    private String symbol(Currency currency) {
        return switch (currency) {
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
}
