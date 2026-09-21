package com.paymentreminder.common.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        @NotNull ZoneId timeZone,
        @NotNull LocalTime notificationTime,
        @NotBlank String planningCron,
        @NotBlank String deliveryCron,
        @NotNull Duration deliveryLease,
        @Min(1) int deliveryBatch,
        boolean pollingEnabled,
        @Min(1) long pollingDelayMs
) {
}
