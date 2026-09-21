CREATE TABLE payments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(255) NOT NULL,
    amount numeric(19, 2) NOT NULL,
    currency varchar(3) NOT NULL,
    recurrence varchar(16) NOT NULL,
    next_payment_date date NOT NULL,
    active boolean NOT NULL DEFAULT true,
    current_period_id uuid NOT NULL,
    anchor_day smallint NOT NULL,
    anchor_month smallint,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payments_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_payments_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_payments_currency CHECK (currency IN ('RUB', 'USD', 'EUR')),
    CONSTRAINT ck_payments_recurrence CHECK (recurrence IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT ck_payments_anchor_day CHECK (anchor_day BETWEEN 1 AND 31),
    CONSTRAINT ck_payments_anchor_month CHECK (
        (recurrence = 'MONTHLY' AND anchor_month IS NULL)
        OR (recurrence = 'YEARLY' AND anchor_month IS NOT NULL AND anchor_month BETWEEN 1 AND 12)
    ),
    CONSTRAINT ck_payments_version CHECK (version >= 0)
);

CREATE INDEX ix_payments_active_next_date ON payments (next_payment_date) WHERE active = true;

CREATE TABLE payment_reminders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id bigint NOT NULL,
    days_before integer NOT NULL,
    CONSTRAINT fk_payment_reminders_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payment_reminders_days_before CHECK (days_before >= 0),
    CONSTRAINT uq_payment_reminders_payment_days UNIQUE (payment_id, days_before)
);

CREATE TABLE payment_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id bigint NOT NULL,
    period_id uuid NOT NULL,
    scheduled_date date NOT NULL,
    paid_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    amount numeric(19, 2) NOT NULL,
    currency varchar(3) NOT NULL,
    CONSTRAINT fk_payment_history_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_payment_history_payment_period UNIQUE (payment_id, period_id),
    CONSTRAINT ck_payment_history_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_payment_history_currency CHECK (currency IN ('RUB', 'USD', 'EUR'))
);

CREATE INDEX ix_payment_history_latest ON payment_history (payment_id, paid_at DESC, id DESC);

CREATE TABLE notification_jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id bigint NOT NULL,
    period_id uuid NOT NULL,
    scheduled_date date NOT NULL,
    kind varchar(16) NOT NULL,
    days_before integer,
    notification_date date NOT NULL,
    source_notification_id bigint,
    available_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    lease_until timestamptz,
    claim_token uuid,
    sent_at timestamptz,
    telegram_message_id bigint,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_jobs_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_notification_jobs_source FOREIGN KEY (source_notification_id) REFERENCES notification_jobs (id),
    CONSTRAINT ck_notification_jobs_kind CHECK (kind IN ('REGULAR', 'SNOOZE', 'OVERDUE')),
    CONSTRAINT ck_notification_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'UNKNOWN', 'CANCELLED')
    ),
    CONSTRAINT ck_notification_jobs_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_jobs_kind_fields CHECK (
        (kind = 'REGULAR' AND days_before IS NOT NULL AND days_before >= 0 AND source_notification_id IS NULL)
        OR (kind = 'OVERDUE' AND days_before IS NULL AND source_notification_id IS NULL)
        OR (kind = 'SNOOZE' AND days_before IS NULL AND source_notification_id IS NOT NULL)
    ),
    CONSTRAINT ck_notification_jobs_source_not_self CHECK (source_notification_id <> id)
);

CREATE UNIQUE INDEX uq_notification_jobs_regular
    ON notification_jobs (payment_id, period_id, days_before) WHERE kind = 'REGULAR';
CREATE UNIQUE INDEX uq_notification_jobs_overdue
    ON notification_jobs (payment_id, period_id, notification_date) WHERE kind = 'OVERDUE';
CREATE UNIQUE INDEX uq_notification_jobs_snooze
    ON notification_jobs (source_notification_id) WHERE kind = 'SNOOZE';
CREATE INDEX ix_notification_jobs_pending
    ON notification_jobs (available_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_notification_jobs_processing
    ON notification_jobs (lease_until, id) WHERE status = 'PROCESSING';
CREATE INDEX ix_notification_jobs_payment_period ON notification_jobs (payment_id, period_id);

CREATE TABLE telegram_polling_state (
    consumer_name varchar(100) PRIMARY KEY,
    next_update_id bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_telegram_polling_state_name CHECK (length(btrim(consumer_name)) > 0),
    CONSTRAINT ck_telegram_polling_state_update_id CHECK (next_update_id >= 0)
);
