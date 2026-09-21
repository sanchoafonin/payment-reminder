package com.paymentreminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class DatabaseSchemaTests extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migrationCreatesExpectedTablesAndIsNotAppliedTwice() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class)).containsExactlyInAnyOrder(
                "payments", "payment_reminders", "payment_history", "notification_jobs",
                "telegram_polling_state", "telegram_conversations", "flyway_schema_history");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(3L);
    }

    @Test
    void allowsOverduePaymentWithoutReminderRulesAndKeepsCalendarAnchors() {
        long payment = payment();
        assertThat(jdbc.queryForObject("SELECT active FROM payments WHERE id = ?", Boolean.class, payment)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reminders WHERE payment_id = ?",
                Long.class, payment)).isZero();
        jdbc.update("UPDATE payments SET next_payment_date = DATE '2025-02-28', anchor_day = 31 WHERE id = ?", payment);
        assertThat(jdbc.queryForObject("SELECT anchor_day FROM payments WHERE id = ?", Integer.class, payment))
                .isEqualTo(31);
        jdbc.update("""
                UPDATE payments SET recurrence = 'YEARLY', anchor_month = 2, anchor_day = 29 WHERE id = ?
                """, payment);
        assertThat(jdbc.queryForObject("SELECT anchor_day FROM payments WHERE id = ?", Integer.class, payment))
                .isEqualTo(29);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amount = 0", "amount = -1", "amount = 'NaN'", "amount = NULL",
            "name = '   '", "currency = 'GBP'", "recurrence = 'WEEKLY'",
            "anchor_day = 0", "anchor_day = 32", "anchor_month = 2",
            "recurrence = 'YEARLY', anchor_month = NULL",
            "recurrence = 'YEARLY', anchor_month = 13"
    })
    void rejectsInvalidPaymentValues(String assignment) {
        long payment = payment();
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET " + assignment + " WHERE id = ?", payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateReminderButAllowsDifferentOffsetsAndPayments() {
        long first = payment();
        long second = payment();
        reminder(first, 0);
        reminder(first, 3);
        reminder(second, 3);
        assertThatThrownBy(() -> reminder(first, 3)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeReminderOffset() {
        long payment = payment();
        assertThatThrownBy(() -> reminder(payment, -1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historyRetainsMoneyAndDateSnapshotsAfterPaymentChanges() {
        long payment = payment();
        history(payment);
        jdbc.update("""
                UPDATE payments SET amount = 900, currency = 'USD', active = false,
                    next_payment_date = DATE '2026-02-28', current_period_id = ? WHERE id = ?
                """, UUID.randomUUID(), payment);

        var snapshot = jdbc.queryForMap("SELECT amount, currency, scheduled_date FROM payment_history WHERE payment_id = ?", payment);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("100.00");
        assertThat(snapshot.get("currency")).isEqualTo("RUB");
        assertThat(((java.sql.Date) snapshot.get("scheduled_date")).toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        history(payment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_history WHERE payment_id = ?", Long.class, payment))
                .isEqualTo(2L);
    }

    @Test
    void rejectsSecondHistoryEntryForSamePeriod() {
        long payment = payment();
        history(payment);
        assertThatThrownBy(() -> history(payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount = 0", "amount = -1", "amount = 'NaN'", "currency = 'GBP'"})
    void rejectsInvalidHistoryValues(String assignment) {
        long payment = payment();
        history(payment);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_history SET " + assignment + " WHERE payment_id = ?", payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentCannotBeDeletedWithHistory() {
        long payment = payment();
        history(payment);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE id = ?", payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment_reminders", "payment_history", "notification_jobs"})
    void rejectsMissingPaymentReference(String table) {
        long payment = payment();
        reminder(payment, 3);
        history(payment);
        job(payment, "REGULAR", 3, "2026-01-28", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET payment_id = -1 WHERE payment_id = ?", payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void regularUniquenessSurvivesReminderDeletionAndRecreation() {
        long payment = payment();
        reminder(payment, 3);
        job(payment, "REGULAR", 3, "2026-01-28", null);
        job(payment, "REGULAR", 1, "2026-01-30", null);
        jdbc.update("DELETE FROM payment_reminders WHERE payment_id = ?", payment);
        reminder(payment, 3);
        assertThatThrownBy(() -> job(payment, "REGULAR", 3, "2026-01-28", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void regularIsAllowedForNewPeriodEvenWhenScheduledDateIsReused() {
        long payment = payment();
        job(payment, "REGULAR", 3, "2026-01-28", null);
        jdbc.update("UPDATE payments SET current_period_id = ? WHERE id = ?", UUID.randomUUID(), payment);
        job(payment, "REGULAR", 3, "2026-01-28", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE payment_id = ?", Long.class, payment))
                .isEqualTo(2L);
    }

    @Test
    void overdueIsUniquePerPeriodAndDay() {
        long payment = payment();
        job(payment, "OVERDUE", null, "2026-02-01", null);
        job(payment, "OVERDUE", null, "2026-02-02", null);
        assertThatThrownBy(() -> job(payment, "OVERDUE", null, "2026-02-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void snoozeAndOverdueMayCoexistButSourceCanOnlyBeSnoozedOnce() {
        long payment = payment();
        long source = job(payment, "OVERDUE", null, "2026-02-01", null);
        long snooze = job(payment, "SNOOZE", null, "2026-02-02", source);
        job(payment, "OVERDUE", null, "2026-02-02", null);
        job(payment, "SNOOZE", null, "2026-02-03", snooze);
        assertThatThrownBy(() -> job(payment, "SNOOZE", null, "2026-02-03", source))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "days_before = NULL", "days_before = -1", "kind = 'SNOOZE'",
            "kind = 'OVERDUE'", "kind = 'OTHER'", "status = 'OTHER'", "attempt_count = -1"
    })
    void rejectsInvalidNotificationValues(String assignment) {
        long payment = payment();
        long notification = job(payment, "REGULAR", 3, "2026-01-28", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE notification_jobs SET " + assignment + " WHERE id = ?", notification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingSnoozeSource() {
        long payment = payment();
        assertThatThrownBy(() -> job(payment, "SNOOZE", null, "2026-02-01", -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pollingPositionIsPersisted() {
        jdbc.update("INSERT INTO telegram_polling_state (consumer_name) VALUES ('telegram')");
        jdbc.update("UPDATE telegram_polling_state SET next_update_id = 123 WHERE consumer_name = 'telegram'");
        assertThat(jdbc.queryForObject("SELECT next_update_id FROM telegram_polling_state WHERE consumer_name = 'telegram'", Long.class))
                .isEqualTo(123L);
        assertThatThrownBy(() -> jdbc.update("UPDATE telegram_polling_state SET next_update_id = -1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long payment() {
        return jdbc.queryForObject("""
                INSERT INTO payments (name, amount, currency, recurrence, next_payment_date, current_period_id, anchor_day)
                VALUES ('Интернет', 100.00, 'RUB', 'MONTHLY', DATE '2026-01-31', ?, 31)
                RETURNING id
                """, Long.class, UUID.randomUUID());
    }

    private void reminder(long payment, int daysBefore) {
        jdbc.update("INSERT INTO payment_reminders (payment_id, days_before) VALUES (?, ?)", payment, daysBefore);
    }

    private void history(long payment) {
        jdbc.update("""
                INSERT INTO payment_history (payment_id, period_id, scheduled_date, amount, currency)
                SELECT id, current_period_id, next_payment_date, amount, currency FROM payments WHERE id = ?
                """, payment);
    }

    private long job(long payment, String kind, Integer daysBefore, String notificationDate, Long source) {
        return jdbc.queryForObject("""
                INSERT INTO notification_jobs (payment_id, period_id, scheduled_date, kind, days_before,
                    notification_date, source_notification_id, available_at)
                SELECT id, current_period_id, next_payment_date, ?, ?, CAST(? AS date), ?,
                    (CAST(? AS date) + TIME '09:00') AT TIME ZONE 'Europe/Moscow'
                FROM payments WHERE id = ? RETURNING id
                """, Long.class, kind, daysBefore, notificationDate, source, notificationDate, payment);
    }
}
