-- Records the user deleted lately, kept for a day so the delete can be
-- undone (POST /api/transactions/restore). A restored record gets back its
-- id and what pointed at it. Deleting the account or the user drops these too.
CREATE TABLE deleted_transactions (
    id               BIGINT PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind             TEXT NOT NULL,
    amount           BIGINT NOT NULL,
    account_id       BIGINT NOT NULL REFERENCES wallet_accounts (id) ON DELETE CASCADE,
    to_account_id    BIGINT REFERENCES wallet_accounts (id) ON DELETE CASCADE,
    category_id      BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    payee            TEXT NOT NULL,
    note             TEXT NOT NULL,
    status           TEXT NOT NULL,
    source           TEXT NOT NULL,
    source_label     TEXT NOT NULL,
    duplicate_of_id  BIGINT,
    import_key       TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    -- What pointed at the record when it was deleted, to link again.
    email_ids        BIGINT[] NOT NULL DEFAULT '{}',
    notification_ids BIGINT[] NOT NULL DEFAULT '{}',
    duplicate_ids    BIGINT[] NOT NULL DEFAULT '{}',
    deleted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX deleted_transactions_user_idx ON deleted_transactions (user_id, deleted_at);
