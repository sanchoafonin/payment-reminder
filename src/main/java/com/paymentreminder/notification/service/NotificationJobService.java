package com.paymentreminder.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.entity.NotificationStatus;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import com.paymentreminder.payment.entity.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the notification job lifecycle: cancellation of no-longer-relevant jobs, claiming of ready
 * jobs for delivery, and recording of delivery outcomes.
 */
@Service
public class NotificationJobService {

    private final NotificationJobRepository jobRepository;

    public NotificationJobService(NotificationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Reclaims nothing: interrupted PROCESSING jobs are parked as UNKNOWN, then a batch of ready
     * PENDING jobs is locked and marked PROCESSING. Must run in a short transaction, before any
     * network call.
     */
    @Transactional
    public List<ClaimedNotification> claimReady(int batch, Instant now, Duration lease) {
        jobRepository.markExpiredProcessingUnknown(now);
        List<NotificationJob> ready = jobRepository.lockReady(now, batch);
        List<ClaimedNotification> claimed = new ArrayList<>(ready.size());
        for (NotificationJob job : ready) {
            UUID token = UUID.randomUUID();
            job.markProcessing(token, now.plus(lease), now);
            Payment payment = job.getPayment();
            NotificationMessage message = new NotificationMessage(job.getId(), payment.getName(),
                    payment.getAmount(), payment.getCurrency(), job.getScheduledDate(), job.getKind(),
                    job.getDaysBefore(), job.getNotificationDate());
            claimed.add(new ClaimedNotification(job.getId(), token, job.getAttemptCount(), message));
        }
        jobRepository.flush();
        return claimed;
    }

    @Transactional
    public boolean recordSent(Long id, UUID claimToken, long telegramMessageId, Instant now) {
        NotificationJob job = claimedJob(id, claimToken);
        if (job == null) {
            return false;
        }
        job.markSent(telegramMessageId, now);
        return true;
    }

    @Transactional
    public boolean recordRetry(Long id, UUID claimToken, String error, Instant availableAt, Instant now) {
        NotificationJob job = claimedJob(id, claimToken);
        if (job == null) {
            return false;
        }
        job.markRetry(error, availableAt, now);
        return true;
    }

    @Transactional
    public boolean recordFailed(Long id, UUID claimToken, String error, Instant now) {
        NotificationJob job = claimedJob(id, claimToken);
        if (job == null) {
            return false;
        }
        job.markFailed(error, now);
        return true;
    }

    @Transactional
    public boolean recordUnknown(Long id, UUID claimToken, String error, Instant now) {
        NotificationJob job = claimedJob(id, claimToken);
        if (job == null) {
            return false;
        }
        job.markUnknown(error, now);
        return true;
    }

    /** Returns the job only when the claim token is still the current one; otherwise null. */
    private NotificationJob claimedJob(Long id, UUID claimToken) {
        NotificationJob job = jobRepository.findById(id).orElse(null);
        if (job == null || job.getStatus() != NotificationStatus.PROCESSING
                || !claimToken.equals(job.getClaimToken())) {
            return null;
        }
        return job;
    }

    @Transactional
    public int cancelPendingForPeriod(Long paymentId, UUID periodId, Instant now) {
        return jobRepository.cancelPendingByPaymentAndPeriod(paymentId, periodId, now);
    }

    @Transactional
    public int cancelPendingForPayment(Long paymentId, Instant now) {
        return jobRepository.cancelPendingByPayment(paymentId, now);
    }

    @Transactional
    public int cancelPendingRegularRule(Long paymentId, int daysBefore, Instant now) {
        return jobRepository.cancelPendingRegularRule(paymentId, daysBefore, now);
    }
}
