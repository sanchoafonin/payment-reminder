package com.paymentreminder.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.paymentreminder.history.dto.PaymentHistoryResponse;
import com.paymentreminder.history.entity.PaymentHistory;
import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.notification.service.NotificationJobService;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentPayResponse;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.repository.PaymentRepository;
import com.paymentreminder.reminder.service.ReminderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository historyRepository;
    private final ReminderService reminderService;
    private final NotificationJobService notificationJobService;
    private final RecurrenceCalculator recurrenceCalculator;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository, PaymentHistoryRepository historyRepository,
            ReminderService reminderService, NotificationJobService notificationJobService,
            RecurrenceCalculator recurrenceCalculator, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.historyRepository = historyRepository;
        this.reminderService = reminderService;
        this.notificationJobService = notificationJobService;
        this.recurrenceCalculator = recurrenceCalculator;
        this.clock = clock;
    }

    @Transactional
    public PaymentResponse create(PaymentCreateRequest request) {
        Payment payment = Payment.create(request.name(), request.amount(), request.currency(),
                request.recurrence(), request.nextPaymentDate(), clock.instant());
        paymentRepository.save(payment);
        List<Integer> reminders = reminderService.replaceForPayment(payment, request.reminders());
        return PaymentResponse.from(payment, reminders);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long id) {
        Payment payment = findPayment(id);
        return PaymentResponse.from(payment, reminderService.daysForPayment(payment.getId()));
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> list(boolean activeOnly, Pageable pageable) {
        Page<Payment> payments = activeOnly
                ? paymentRepository.findByActiveTrue(pageable)
                : paymentRepository.findAll(pageable);
        Map<Long, List<Integer>> reminders = reminderService.daysByPayment(
                payments.getContent().stream().map(Payment::getId).toList());
        return payments.map(payment -> PaymentResponse.from(payment,
                reminders.getOrDefault(payment.getId(), List.of())));
    }

    @Transactional
    public PaymentResponse update(Long id, PaymentUpdateRequest request) {
        Payment payment = findPayment(id);
        Instant now = clock.instant();
        UUID previousPeriodId = payment.getCurrentPeriodId();
        List<Integer> previousRules = reminderService.daysForPayment(id);

        payment.update(request.name(), request.amount(), request.currency(), request.recurrence(),
                request.nextPaymentDate(), request.active(), now);
        List<Integer> reminders = reminderService.replaceForPayment(payment, request.reminders());

        if (!payment.getCurrentPeriodId().equals(previousPeriodId)) {
            notificationJobService.cancelPendingForPeriod(id, previousPeriodId, now);
        } else {
            previousRules.stream()
                    .filter(daysBefore -> !reminders.contains(daysBefore))
                    .forEach(daysBefore -> notificationJobService.cancelPendingRegularRule(id, daysBefore, now));
        }
        return PaymentResponse.from(payment, reminders);
    }

    @Transactional
    public void deactivate(Long id) {
        Payment payment = findPayment(id);
        Instant now = clock.instant();
        payment.deactivate(now);
        notificationJobService.cancelPendingForPayment(id, now);
    }

    /**
     * Marks the current period as paid in a single transaction: snapshots the planned date, amount
     * and currency into history, then advances the planned date and issues a new period id. The row
     * is locked so concurrent payments of the same payment cannot both close a period.
     */
    @Transactional
    public PaymentPayResponse pay(Long id, UUID expectedPeriodId) {
        Payment payment = paymentRepository.findLockedById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));

        if (expectedPeriodId != null && !expectedPeriodId.equals(payment.getCurrentPeriodId())) {
            Optional<PaymentHistory> completed = historyRepository.findByPaymentIdAndPeriodId(id, expectedPeriodId);
            if (completed.isPresent()) {
                return response(payment, completed.get());
            }
            throw new StalePaymentPeriodException(id, expectedPeriodId);
        }

        if (!payment.isActive()) {
            throw new PaymentNotActiveException(id);
        }

        LocalDate nextPaymentDate = recurrenceCalculator.next(payment.getNextPaymentDate(),
                payment.getRecurrence(), payment.getAnchorDay(), payment.getAnchorMonth());
        UUID previousPeriodId = payment.getCurrentPeriodId();
        PaymentHistory history = PaymentHistory.record(payment, previousPeriodId,
                payment.getNextPaymentDate(), clock.instant());
        payment.completeCurrentPeriod(nextPaymentDate, clock.instant());
        historyRepository.save(history);
        notificationJobService.cancelPendingForPeriod(id, previousPeriodId, clock.instant());
        return response(payment, history);
    }

    private PaymentPayResponse response(Payment payment, PaymentHistory history) {
        return new PaymentPayResponse(
                PaymentResponse.from(payment, reminderService.daysForPayment(payment.getId())),
                PaymentHistoryResponse.from(history));
    }

    private Payment findPayment(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
