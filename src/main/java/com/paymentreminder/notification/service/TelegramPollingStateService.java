package com.paymentreminder.notification.service;

import java.time.Clock;

import com.paymentreminder.notification.repository.TelegramPollingStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and advances the durable Telegram long-polling position. */
@Service
public class TelegramPollingStateService {

    public static final String CONSUMER_NAME = "payment-reminder";

    private final TelegramPollingStateRepository repository;
    private final Clock clock;

    public TelegramPollingStateService(TelegramPollingStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long nextUpdateId() {
        return repository.findById(CONSUMER_NAME)
                .map(state -> state.getNextUpdateId())
                .orElse(0L);
    }

    @Transactional
    public void saveNextUpdateId(long offset) {
        repository.saveOffset(CONSUMER_NAME, offset, clock.instant());
    }
}
