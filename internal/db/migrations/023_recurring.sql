-- Recurring payments: subscriptions, bills and income that come back every
-- month or few months (internal/recurring). The records themselves stay
-- ordinary records: a recurring payment names the texts its records carry
-- (matches, compared with each record's payee, or its note when it has no
-- payee), and its payments, last payment and next due date are worked out
-- from them when asked. The previous release doesn't read either table.
CREATE TABLE recurring_payments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('subscription', 'bill', 'income')),
    matches     TEXT[] NOT NULL DEFAULT '{}',
    amount      BIGINT NOT NULL CHECK (amount > 0),
    months      INT NOT NULL DEFAULT 1 CHECK (months IN (1, 2, 3, 6, 12)),
    -- When it's due if it has no payments yet; else the last one sets it.
    start_date  DATE NOT NULL,
    category_id BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    account_id  BIGINT REFERENCES wallet_accounts (id) ON DELETE SET NULL,
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX recurring_payments_user_idx ON recurring_payments (user_id);

-- Suggestions the user said no to, so they aren't offered again.
CREATE TABLE recurring_dismissed (
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key        TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);
