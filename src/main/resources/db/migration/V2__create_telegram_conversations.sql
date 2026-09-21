CREATE TABLE telegram_conversations (
    chat_id bigint PRIMARY KEY,
    step varchar(32) NOT NULL,
    draft text NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
