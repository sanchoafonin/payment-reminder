package com.paymentreminder.reminder.repository;

import java.util.Collection;
import java.util.List;

import com.paymentreminder.reminder.entity.PaymentReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReminderRepository extends JpaRepository<PaymentReminder, Long> {

    List<PaymentReminder> findByPaymentIdOrderByDaysBeforeAsc(Long paymentId);

    List<PaymentReminder> findByPaymentIdInOrderByDaysBeforeAsc(Collection<Long> paymentIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from PaymentReminder r where r.payment.id = :paymentId")
    void deleteByPaymentId(@Param("paymentId") Long paymentId);
}
