package com.paymentreminder.reminder.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.reminder.entity.PaymentReminder;
import com.paymentreminder.reminder.repository.PaymentReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists reminder rules for payments. A payment always stores the full normalized set of its
 * rules: days are unique and sorted ascending, and a write replaces the whole set. Removing a rule
 * does not touch already recorded delivery data (that belongs to notification jobs).
 */
@Service
public class ReminderService {

    private final PaymentReminderRepository reminderRepository;

    public ReminderService(PaymentReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    /** Replaces all rules of the payment and returns the normalized days in stored order. */
    @Transactional
    public List<Integer> replaceForPayment(Payment payment, List<Integer> reminders) {
        List<Integer> normalized = normalize(reminders);
        reminderRepository.deleteByPaymentId(payment.getId());
        normalized.forEach(daysBefore -> reminderRepository.save(PaymentReminder.create(payment, daysBefore)));
        return normalized;
    }

    @Transactional(readOnly = true)
    public List<Integer> daysForPayment(Long paymentId) {
        return reminderRepository.findByPaymentIdOrderByDaysBeforeAsc(paymentId).stream()
                .map(PaymentReminder::getDaysBefore)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Integer>> daysByPayment(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        return reminderRepository.findByPaymentIdInOrderByDaysBeforeAsc(paymentIds).stream()
                .collect(Collectors.groupingBy(reminder -> reminder.getPayment().getId(),
                        Collectors.mapping(PaymentReminder::getDaysBefore, Collectors.toList())));
    }

    static List<Integer> normalize(List<Integer> reminders) {
        if (reminders == null || reminders.isEmpty()) {
            return List.of();
        }
        Set<Integer> unique = new TreeSet<>();
        for (Integer reminder : reminders) {
            if (reminder == null || reminder < 0) {
                throw new IllegalArgumentException("reminder days must be non-negative");
            }
            if (!unique.add(reminder)) {
                throw new IllegalArgumentException("reminder days must be unique");
            }
        }
        return List.copyOf(unique);
    }
}
