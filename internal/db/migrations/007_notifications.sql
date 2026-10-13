-- Payment notifications from the Android app (internal/notify): the app
-- listens for notifications from payment apps such as Google Wallet and
-- sends them here, and each payment becomes a record to review.
ALTER TABLE transactions DROP CONSTRAINT transactions_source_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_source_check
    CHECK (source IN ('manual', 'email', 'import', 'notification'));

CREATE TABLE payment_notifications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The app that posted it and the time it carries, which stay the same
    -- when the phone sends it again or the app updates it.
    dedup_key      TEXT NOT NULL,
    package        TEXT NOT NULL,
    title          TEXT NOT NULL DEFAULT '',
    body           TEXT NOT NULL DEFAULT '',
    posted_at      TIMESTAMPTZ NOT NULL,
    -- payment: made a record; matched: the same payment was already recorded
    -- (e.g. from the card's email), so no record was made; ignored: not a
    -- payment; unparsed: looked like one but couldn't be read.
    status         TEXT NOT NULL CHECK (status IN ('payment', 'matched', 'ignored', 'unparsed')),
    detail         TEXT NOT NULL DEFAULT '',
    transaction_id BIGINT REFERENCES transactions (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, dedup_key)
);
CREATE INDEX payment_notifications_user_idx ON payment_notifications (user_id, created_at DESC);
CREATE INDEX payment_notifications_tx_idx ON payment_notifications (transaction_id);
CREATE INDEX inbound_emails_tx_idx ON inbound_emails (transaction_id);
