package com.paymentreminder.notification.scheduler;

import com.paymentreminder.notification.service.NotificationDeliveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically delivers notifications whose {@code available_at} has passed. */
@Component
public class NotificationDeliveryScheduler {

    private final NotificationDeliveryService deliveryService;

    public NotificationDeliveryScheduler(NotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(cron = "${app.delivery-cron}", zone = "${app.time-zone}")
    public void deliver() {
        deliveryService.deliverDue();
    }
}
