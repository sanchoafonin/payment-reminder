package com.paymentreminder.notification.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationJobRepository extends JpaRepository<NotificationJob, Long> {

    /**
     * Creates a REGULAR job unless the same rule for the same period already exists. The partial
     * unique index makes this safe under concurrency, so no SELECT-then-INSERT is needed.
     */
    @Modifying
    @Query(value = """
            insert into notification_jobs
                (payment_id, period_id, scheduled_date, kind, days_before, notification_date, available_at, status)
            values
                (:paymentId, :periodId, :scheduledDate, 'REGULAR', :daysBefore, :notificationDate, :availableAt, 'PENDING')
            on conflict do nothing
            """, nativeQuery = true)
    int insertRegularIfAbsent(@Param("paymentId") Long paymentId, @Param("periodId") UUID periodId,
            @Param("scheduledDate") LocalDate scheduledDate, @Param("daysBefore") int daysBefore,
            @Param("notificationDate") LocalDate notificationDate, @Param("availableAt") Instant availableAt);

    /** Creates at most one OVERDUE job per payment, period and local day. */
    @Modifying
    @Query(value = """
            insert into notification_jobs
                (payment_id, period_id, scheduled_date, kind, days_before, notification_date, available_at, status)
            values
                (:paymentId, :periodId, :scheduledDate, 'OVERDUE', null, :notificationDate, :availableAt, 'PENDING')
            on conflict do nothing
            """, nativeQuery = true)
    int insertOverdueIfAbsent(@Param("paymentId") Long paymentId, @Param("periodId") UUID periodId,
            @Param("scheduledDate") LocalDate scheduledDate, @Param("notificationDate") LocalDate notificationDate,
            @Param("availableAt") Instant availableAt);

    /**
     * Creates the snooze for a source notification unless it already exists. The partial unique
     * index on {@code source_notification_id} guarantees one snooze per message.
     */
    @Modifying
    @Query(value = """
            insert into notification_jobs
                (payment_id, period_id, scheduled_date, kind, days_before, notification_date,
                 source_notification_id, available_at, status)
            values
                (:paymentId, :periodId, :scheduledDate, 'SNOOZE', null, :notificationDate,
                 :sourceNotificationId, :availableAt, 'PENDING')
            on conflict do nothing
            """, nativeQuery = true)
    int insertSnoozeIfAbsent(@Param("paymentId") Long paymentId, @Param("periodId") UUID periodId,
            @Param("scheduledDate") LocalDate scheduledDate, @Param("notificationDate") LocalDate notificationDate,
            @Param("sourceNotificationId") Long sourceNotificationId, @Param("availableAt") Instant availableAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update notification_jobs set status = 'CANCELLED', updated_at = :now
            where payment_id = :paymentId and period_id = :periodId and status = 'PENDING'
            """, nativeQuery = true)
    int cancelPendingByPaymentAndPeriod(@Param("paymentId") Long paymentId, @Param("periodId") UUID periodId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update notification_jobs set status = 'CANCELLED', updated_at = :now
            where payment_id = :paymentId and status = 'PENDING'
            """, nativeQuery = true)
    int cancelPendingByPayment(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update notification_jobs set status = 'CANCELLED', updated_at = :now
            where payment_id = :paymentId and kind = 'REGULAR' and days_before = :daysBefore and status = 'PENDING'
            """, nativeQuery = true)
    int cancelPendingRegularRule(@Param("paymentId") Long paymentId, @Param("daysBefore") int daysBefore,
            @Param("now") Instant now);

    List<NotificationJob> findByPaymentIdAndStatus(Long paymentId, NotificationStatus status);

    long countByPaymentIdAndStatus(Long paymentId, NotificationStatus status);

    /**
     * An interrupted PROCESSING attempt has an unknown outcome: whether the message reached
     * Telegram cannot be established, so it is parked as UNKNOWN and never retried automatically.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update notification_jobs
            set status = 'UNKNOWN', last_error = 'delivery lease expired', updated_at = :now
            where status = 'PROCESSING' and lease_until < :now
            """, nativeQuery = true)
    int markExpiredProcessingUnknown(@Param("now") Instant now);

    /**
     * Claims ready jobs for delivery, locking them so concurrent workers skip the same rows.
     */
    @Query(value = """
            select * from notification_jobs
            where status = 'PENDING' and available_at <= :now
            order by id
            limit :batch
            for update skip locked
            """, nativeQuery = true)
    List<NotificationJob> lockReady(@Param("now") Instant now, @Param("batch") int batch);
}
