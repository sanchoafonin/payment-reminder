package com.paymentreminder.notification.entity;

/** Current step of the Telegram add/edit wizard. Stored as a string in telegram_conversations. */
public enum WizardStep {
    NAME,
    AMOUNT,
    CURRENCY,
    RECURRENCE,
    DATE,
    REMINDERS,
    CONFIRM,
    EDIT_FIELD,
    EDIT_NAME,
    EDIT_AMOUNT,
    EDIT_DATE,
    EDIT_REMINDERS;

    public boolean isEdit() {
        return name().startsWith("EDIT_");
    }
}
