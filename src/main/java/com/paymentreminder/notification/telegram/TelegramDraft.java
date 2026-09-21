package com.paymentreminder.notification.telegram;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Field keys and small helpers for the JSON draft stored in telegram_conversations. */
public final class TelegramDraft {

    public static final String NAME = "name";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String RECURRENCE = "recurrence";
    public static final String DATE = "date";
    public static final String REMINDERS = "reminders";
    public static final String PAYMENT_ID = "paymentId";
    public static final String EDIT = "edit";
    public static final String ACTIVE = "active";

    private TelegramDraft() {
    }

    public static List<Integer> reminders(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<Integer> days = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                days.add(Integer.parseInt(trimmed));
            }
        }
        List<Integer> sorted = new ArrayList<>(days);
        sorted.sort(Integer::compareTo);
        return sorted;
    }

    public static String remindersToString(List<Integer> reminders) {
        return reminders == null ? "" : reminders.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
            String[] parts = trimmed.split("\\.");
            return LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        return LocalDate.parse(trimmed);
    }
}
