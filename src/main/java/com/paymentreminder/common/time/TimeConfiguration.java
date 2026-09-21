package com.paymentreminder.common.time;

import java.time.Clock;

import com.paymentreminder.common.config.ApplicationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    Clock clock(ApplicationProperties properties) {
        return Clock.system(properties.timeZone());
    }
}
