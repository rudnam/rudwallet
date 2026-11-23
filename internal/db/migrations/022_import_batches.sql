-- Each import that adds records is kept as a batch, so the import page can
-- list what each one did and undo one in a single step (POST
-- /api/imports/{id}/undo moves its records to the trash, and puts back a
-- starting balance it changed). Records imported before this have no batch.
-- The previous release doesn't read any of it, and its inserts name their
-- columns, so the new nullable column is left NULL by it.
CREATE TABLE import_batches (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source              TEXT NOT NULL,          -- paypay, mufg, iccard, budgetbakers
    label               TEXT NOT NULL DEFAULT '', -- e.g. the card's last digits
    period_from         TIMESTAMPTZ,
    period_to           TIMESTAMPTZ,
    imported            INT NOT NULL DEFAULT 0,
    duplicates          INT NOT NULL DEFAULT 0,
    matched             INT NOT NULL DEFAULT 0,
    skipped             INT NOT NULL DEFAULT 0,
    -- A starting balance the import changed (match_balance), to put back on undo.
    adjusted_account_id BIGINT REFERENCES wallet_accounts (id) ON DELETE SET NULL,
    adjusted_amount     BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    undone_at           TIMESTAMPTZ
);
CREATE INDEX import_batches_user_idx ON import_batches (user_id, created_at DESC);

ALTER TABLE transactions ADD COLUMN import_batch_id BIGINT REFERENCES import_batches (id) ON DELETE SET NULL;
CREATE INDEX transactions_import_batch_idx ON transactions (import_batch_id) WHERE import_batch_id IS NOT NULL;
ALTER TABLE deleted_transactions ADD COLUMN import_batch_id BIGINT;
