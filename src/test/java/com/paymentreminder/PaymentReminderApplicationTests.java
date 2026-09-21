package com.paymentreminder;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;

import com.paymentreminder.common.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReminderApplicationTests extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationProperties properties;

    @Test
    void healthEndpointReportsUp() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void infoEndpointIdentifiesApplication() {
        var response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"payment-reminder\"");
    }

    @Test
    void environmentEndpointIsNotExposed() {
        var response = restTemplate.getForEntity("/actuator/env", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void clockUsesConfiguredBusinessTimeZone() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(properties.notificationTime()).isEqualTo(LocalTime.of(9, 0));
    }
}
