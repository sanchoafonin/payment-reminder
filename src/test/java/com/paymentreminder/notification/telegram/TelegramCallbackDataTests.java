package com.paymentreminder.notification.telegram;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramCallbackDataTests {

    @Test
    void parsesPayCommand() {
        assertThat(TelegramCallbackData.parse("pay:42"))
                .contains(new TelegramCallbackData(TelegramCallbackData.Action.PAY, 42L));
    }

    @Test
    void parsesSnoozeCommand() {
        assertThat(TelegramCallbackData.parse("snooze:7"))
                .contains(new TelegramCallbackData(TelegramCallbackData.Action.SNOOZE, 7L));
    }

    @ParameterizedTest
    @MethodSource("invalidData")
    void rejectsMalformedData(String data) {
        assertThat(TelegramCallbackData.parse(data)).isEqualTo(Optional.empty());
    }

    static Stream<String> invalidData() {
        return Stream.of(null, "", "   ", "pay", "pay:", ":42", "unknown:1", "pay:abc", "pay:1:2");
    }
}
