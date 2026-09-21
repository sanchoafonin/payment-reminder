package com.paymentreminder.payment.repository;

import java.util.List;
import java.util.Optional;

import com.paymentreminder.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findByActiveTrue(Pageable pageable);

    List<Payment> findByActiveTrue();

    /** Serializes the "mark as paid" flow per payment row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findLockedById(@Param("id") Long id);
}
