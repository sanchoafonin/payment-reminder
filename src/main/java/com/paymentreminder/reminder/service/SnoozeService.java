package com.paymentreminder.reminder.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.notification.entity.NotificationJob;
import com.paymentreminder.notification.repository.NotificationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the persisted "remind me tomorrow" job for a delivered notification. The payment date and
 * the regular rules are left untouched; only one snooze per source message is allowed.
 */
@Service
public class SnoozeService {

    private final NotificationJobRepository jobRepository;
    private final ApplicationProperties properties;
    private final Clock clock;

    public SnoozeService(NotificationJobRepository jobRepository, ApplicationProperties properties, Clock clock) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return {@code true} when a new snooze was created, {@code false} when this message was already
     *         snoozed
     */
    @Transactional
    public boolean snoozeUntilTomorrow(NotificationJob source) {
        LocalDate today = LocalDate.now(clock.withZone(properties.timeZone()));
        LocalDate notificationDate = today.plusDays(1);
        Instant availableAt = notificationDate.atTime(properties.notificationTime())
                .atZone(properties.timeZone())
                .toInstant();
        int inserted = jobRepository.insertSnoozeIfAbsent(source.getPayment().getId(),
                source.getPeriodId(), source.getScheduledDate(), notificationDate, source.getId(),
                availableAt);
        return inserted > 0;
    }
}
