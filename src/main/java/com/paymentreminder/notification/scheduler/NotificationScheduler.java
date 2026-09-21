package com.paymentreminder.notification.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.reminder.service.ReminderPlanningService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs reminder planning once per day in the application time zone. */
@Component
public class NotificationScheduler {

    private final ReminderPlanningService planningService;
    private final ApplicationProperties properties;
    private final Clock clock;

    public NotificationScheduler(ReminderPlanningService planningService, ApplicationProperties properties,
            Clock clock) {
        this.planningService = planningService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.planning-cron}", zone = "${app.time-zone}")
    public void planNotifications() {
        planningService.plan(LocalDate.now(clock.withZone(properties.timeZone())));
    }
}
