package com.paymentreminder.reminder.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans notification jobs for a single local day. It only creates jobs for that exact day: missed
 * regular reminders are not backfilled after downtime. Re-running for the same day is safe because
 * inserts rely on the unique indexes (ON CONFLICT DO NOTHING).
 */
@Service
public class ReminderPlanningService {

    private final PaymentRepository paymentRepository;
    private final ReminderService reminderService;
    private final NotificationJobRepository jobRepository;
    private final ApplicationProperties properties;

    public ReminderPlanningService(PaymentRepository paymentRepository, ReminderService reminderService,
            NotificationJobRepository jobRepository, ApplicationProperties properties) {
        this.paymentRepository = paymentRepository;
        this.reminderService = reminderService;
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    @Transactional
    public PlanningResult plan(LocalDate today) {
        Instant availableAt = today.atTime(properties.notificationTime())
                .atZone(properties.timeZone())
                .toInstant();

        List<Payment> payments = paymentRepository.findByActiveTrue();
        Map<Long, List<Integer>> rules = reminderService.daysByPayment(
                payments.stream().map(Payment::getId).toList());

        int regular = 0;
        int overdue = 0;
        for (Payment payment : payments) {
            for (Integer daysBefore : rules.getOrDefault(payment.getId(), List.of())) {
                if (payment.getNextPaymentDate().minusDays(daysBefore).equals(today)) {
                    regular += jobRepository.insertRegularIfAbsent(payment.getId(), payment.getCurrentPeriodId(),
                            payment.getNextPaymentDate(), daysBefore, today, availableAt);
                }
            }
            if (payment.getNextPaymentDate().isBefore(today)) {
                overdue += jobRepository.insertOverdueIfAbsent(payment.getId(), payment.getCurrentPeriodId(),
                        payment.getNextPaymentDate(), today, availableAt);
            }
        }
        return new PlanningResult(regular, overdue);
    }

    public record PlanningResult(int regularJobs, int overdueJobs) {
    }
}
