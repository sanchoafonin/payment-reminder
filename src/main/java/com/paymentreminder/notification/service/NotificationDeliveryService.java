package com.paymentreminder.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.common.config.TelegramProperties;
import com.paymentreminder.notification.telegram.TelegramClient;
import com.paymentreminder.notification.telegram.TelegramDeliveryException;
import com.paymentreminder.notification.telegram.TelegramMessageFormatter;
import com.paymentreminder.notification.telegram.TelegramUncertainException;
import org.springframework.stereotype.Service;

/**
 * Delivers due notifications. Claiming happens in a short transaction, the Telegram call runs
 * outside any transaction, and the outcome is written back in another short transaction. Only
 * failures known not to have been delivered are retried; uncertain outcomes are parked as UNKNOWN.
 */
@Service
public class NotificationDeliveryService {

    private final NotificationJobService jobService;
    private final TelegramClient telegramClient;
    private final TelegramMessageFormatter formatter;
    private final TelegramProperties telegramProperties;
    private final ApplicationProperties applicationProperties;
    private final Clock clock;

    public NotificationDeliveryService(NotificationJobService jobService, TelegramClient telegramClient,
            TelegramMessageFormatter formatter, TelegramProperties telegramProperties,
            ApplicationProperties applicationProperties, Clock clock) {
        this.jobService = jobService;
        this.telegramClient = telegramClient;
        this.formatter = formatter;
        this.telegramProperties = telegramProperties;
        this.applicationProperties = applicationProperties;
        this.clock = clock;
    }

    /** Claims and delivers one batch of due notifications; returns how many were sent. */
    public int deliverDue() {
        if (!telegramProperties.isConfigured()) {
            return 0;
        }
        Instant now = clock.instant();
        List<ClaimedNotification> claimed = jobService.claimReady(
                applicationProperties.deliveryBatch(), now, applicationProperties.deliveryLease());
        int delivered = 0;
        for (ClaimedNotification job : claimed) {
            if (deliver(job)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliver(ClaimedNotification job) {
        try {
            long messageId = telegramClient.sendMessage(
                    formatter.format(job.message()), formatter.keyboard(job.message().notificationId()));
            return jobService.recordSent(job.id(), job.claimToken(), messageId, clock.instant());
        } catch (TelegramUncertainException exception) {
            jobService.recordUnknown(job.id(), job.claimToken(), describe(exception), clock.instant());
            return false;
        } catch (TelegramDeliveryException exception) {
            Instant moment = clock.instant();
            if (exception.isRetryable() && job.attemptCount() < telegramProperties.maxAttempts()) {
                jobService.recordRetry(job.id(), job.claimToken(), describe(exception),
                        moment.plus(telegramProperties.retryDelay()), moment);
            } else {
                jobService.recordFailed(job.id(), job.claimToken(), describe(exception), moment);
            }
            return false;
        }
    }

    private String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
