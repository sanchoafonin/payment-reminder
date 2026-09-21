package com.paymentreminder.history.service;

import com.paymentreminder.history.dto.PaymentHistoryResponse;
import com.paymentreminder.history.repository.PaymentHistoryRepository;
import com.paymentreminder.payment.repository.PaymentRepository;
import com.paymentreminder.payment.service.PaymentNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentHistoryService {

    private final PaymentHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;

    public PaymentHistoryService(PaymentHistoryRepository historyRepository, PaymentRepository paymentRepository) {
        this.historyRepository = historyRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public Page<PaymentHistoryResponse> history(Long paymentId, Pageable pageable) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return historyRepository.findByPaymentIdOrderByPaidAtDescIdDesc(paymentId, pageable)
                .map(PaymentHistoryResponse::from);
    }
}
