package com.paymentreminder.payment.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.paymentreminder.payment.entity.Currency;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.repository.PaymentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views used by the Telegram bot: unpaid summary and the full payment list. */
@Service
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;

    public PaymentQueryService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Active payments due on or before the end of the given local month, split into overdue ones and
     * those still due later in the month.
     */
    @Transactional(readOnly = true)
    public UnpaidSummary unpaid(LocalDate today) {
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        List<Payment> due = paymentRepository.findByActiveTrue().stream()
                .filter(payment -> !payment.getNextPaymentDate().isAfter(monthEnd))
                .sorted(Comparator.comparing(Payment::getNextPaymentDate))
                .toList();
        List<UnpaidItem> overdue = due.stream()
                .filter(payment -> payment.getNextPaymentDate().isBefore(today))
                .map(UnpaidItem::from)
                .toList();
        List<UnpaidItem> thisMonth = due.stream()
                .filter(payment -> !payment.getNextPaymentDate().isBefore(today))
                .map(UnpaidItem::from)
                .toList();
        return new UnpaidSummary(overdue, thisMonth);
    }

    @Transactional(readOnly = true)
    public List<Payment> allPayments() {
        return paymentRepository.findAll(Sort.by(Sort.Direction.DESC, "active").and(Sort.by("nextPaymentDate")));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> find(Long id) {
        return paymentRepository.findById(id);
    }

    public record UnpaidItem(Long id, String name, BigDecimal amount, Currency currency, LocalDate nextPaymentDate) {

        static UnpaidItem from(Payment payment) {
            return new UnpaidItem(payment.getId(), payment.getName(), payment.getAmount(), payment.getCurrency(),
                    payment.getNextPaymentDate());
        }
    }

    public record UnpaidSummary(List<UnpaidItem> overdue, List<UnpaidItem> thisMonth) {
    }
}
