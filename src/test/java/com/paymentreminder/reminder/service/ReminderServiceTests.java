package com.paymentreminder.reminder.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderServiceTests {

    @Test
    void treatsMissingRulesAsEmpty() {
        assertThat(ReminderService.normalize(null)).isEmpty();
        assertThat(ReminderService.normalize(List.of())).isEmpty();
    }

    @Test
    void sortsRulesAscending() {
        assertThat(ReminderService.normalize(List.of(5, 1, 3))).containsExactly(1, 3, 5);
    }

    @Test
    void rejectsDuplicateRules() {
        assertThatThrownBy(() -> ReminderService.normalize(List.of(3, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAndNullRules() {
        assertThatThrownBy(() -> ReminderService.normalize(List.of(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReminderService.normalize(java.util.Arrays.asList(1, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
