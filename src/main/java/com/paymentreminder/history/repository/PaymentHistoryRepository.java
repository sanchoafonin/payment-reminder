package com.paymentreminder.history.repository;

import java.util.Optional;
import java.util.UUID;

import com.paymentreminder.history.entity.PaymentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    Page<PaymentHistory> findByPaymentIdOrderByPaidAtDescIdDesc(Long paymentId, Pageable pageable);

    Optional<PaymentHistory> findByPaymentIdAndPeriodId(Long paymentId, UUID periodId);
}
