package com.paymentreminder.notification.telegram;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import com.paymentreminder.notification.entity.NotificationKind;
import com.paymentreminder.notification.service.NotificationMessage;
import com.paymentreminder.payment.entity.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TelegramMessageFormatterTests {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    void formatsUpcomingPayment() {
        NotificationMessage message = message(NotificationKind.REGULAR, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 22), 3, new BigDecimal("35300.00"), Currency.RUB);

        assertThat(formatter.format(message)).isEqualTo("""
                💳 Предстоящий платеж

                Ипотека
                35 300 ₽

                Дата платежа: 25 сентября 2026
                Осталось: 3 дня""");
    }

    @Test
    void formatsOverduePaymentWithGenitiveMonthAndDays() {
        NotificationMessage message = message(NotificationKind.OVERDUE, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 26), null, new BigDecimal("35300.00"), Currency.RUB);

        assertThat(formatter.format(message)).isEqualTo("""
                🔴 Просроченный платеж

                Ипотека
                35 300 ₽

                Дата платежа: 25 сентября
                Просрочено: 1 день""");
    }

    @ParameterizedTest
    @MethodSource("dayForms")
    void pluralizesDays(int days, String expectedForm) {
        LocalDate scheduledDate = LocalDate.of(2026, 9, 25);
        NotificationMessage message = message(NotificationKind.REGULAR, scheduledDate,
                scheduledDate.minusDays(days), days, new BigDecimal("100.00"), Currency.RUB);

        assertThat(formatter.format(message)).contains("Осталось: " + days + " " + expectedForm);
    }

    @Test
    void snoozeAfterDueDateRendersAsOverdue() {
        NotificationMessage message = message(NotificationKind.SNOOZE, LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 21), null, new BigDecimal("100.00"), Currency.RUB);

        assertThat(formatter.format(message)).contains("🔴 Просроченный платеж").contains("Просрочено: 1 день");
    }

    @Test
    void snoozeBeforeDueDateRendersAsUpcoming() {
        NotificationMessage message = message(NotificationKind.SNOOZE, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 21), null, new BigDecimal("100.00"), Currency.RUB);

        assertThat(formatter.format(message)).contains("💳 Предстоящий платеж").contains("Осталось: 4 дня");
    }

    static Stream<Arguments> dayForms() {
        return Stream.of(
                arguments(1, "день"),
                arguments(2, "дня"),
                arguments(3, "дня"),
                arguments(4, "дня"),
                arguments(5, "дней"),
                arguments(11, "дней"),
                arguments(21, "день"),
                arguments(22, "дня"),
                arguments(25, "дней"));
    }

    @Test
    void formatsAmountsAndCurrencies() {
        NotificationMessage dollars = message(NotificationKind.REGULAR, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 24), 1, new BigDecimal("1234.50"), Currency.USD);
        NotificationMessage euros = message(NotificationKind.REGULAR, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 24), 1, new BigDecimal("999.99"), Currency.EUR);

        assertThat(formatter.format(dollars)).contains("1 234,5 $");
        assertThat(formatter.format(euros)).contains("999,99 €");
    }

    @Test
    void buildsPayAndSnoozeButtons() {
        TelegramKeyboard keyboard = formatter.keyboard(42);

        assertThat(keyboard.rows()).containsExactly(java.util.List.of(
                new TelegramButton("✅ Оплачено", "pay:42"),
                new TelegramButton("⏰ Напомнить завтра", "snooze:42")));
    }

    private NotificationMessage message(NotificationKind kind, LocalDate scheduledDate,
            LocalDate notificationDate, Integer daysBefore, BigDecimal amount, Currency currency) {
        return new NotificationMessage(1L, "Ипотека", amount, currency, scheduledDate, kind, daysBefore,
                notificationDate);
    }
}
