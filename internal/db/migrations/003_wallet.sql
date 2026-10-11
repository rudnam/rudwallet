-- The wallet (internal/wallet) and email automation (internal/inbox).
-- Amounts are whole minor units of the user's currency (yen for JPY, cents
-- for USD), so they add up exactly.

CREATE TABLE wallet_accounts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('cash', 'bank', 'credit_card', 'e_money', 'savings', 'other')),
    initial_balance BIGINT NOT NULL DEFAULT 0,
    color           TEXT NOT NULL DEFAULT '#2f5d50',
    archived        BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

CREATE TABLE categories (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('expense', 'income')),
    icon       TEXT NOT NULL DEFAULT '',
    color      TEXT NOT NULL DEFAULT '#66706b',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, name)
);

-- One row per user, created the first time they open the wallet; its
-- existence also marks that the default categories and account were made.
CREATE TABLE user_settings (
    user_id            BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    currency           TEXT NOT NULL DEFAULT 'JPY',
    -- Confirm emailed payments straight away when their category is known.
    auto_confirm       BOOLEAN NOT NULL DEFAULT false,
    -- Where emailed payments go until the user picks an account for a card.
    default_account_id BIGINT REFERENCES wallet_accounts (id) ON DELETE SET NULL
);

CREATE TABLE transactions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('expense', 'income', 'transfer')),
    amount          BIGINT NOT NULL CHECK (amount >= 0),
    account_id      BIGINT NOT NULL REFERENCES wallet_accounts (id) ON DELETE CASCADE,
    to_account_id   BIGINT REFERENCES wallet_accounts (id) ON DELETE CASCADE,
    category_id     BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    payee           TEXT NOT NULL DEFAULT '',
    note            TEXT NOT NULL DEFAULT '',
    -- 'review': created from an email and waiting for the user to confirm.
    status          TEXT NOT NULL DEFAULT 'confirmed' CHECK (status IN ('confirmed', 'review')),
    source          TEXT NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'email', 'import')),
    -- What paid, as the email names it (e.g. a card), so the account can be learned.
    source_label    TEXT NOT NULL DEFAULT '',
    duplicate_of_id BIGINT REFERENCES transactions (id) ON DELETE SET NULL,
    import_key      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'transfer') = (to_account_id IS NOT NULL))
);
CREATE INDEX transactions_user_time_idx ON transactions (user_id, occurred_at DESC);
CREATE INDEX transactions_user_status_idx ON transactions (user_id, status);
CREATE UNIQUE INDEX transactions_import_key_idx ON transactions (user_id, import_key) WHERE import_key IS NOT NULL;

-- Monthly spending limits; no category means all expenses.
CREATE TABLE budgets (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    category_id BIGINT REFERENCES categories (id) ON DELETE CASCADE,
    amount      BIGINT NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX budgets_user_id_idx ON budgets (user_id);

-- Learned when the user confirms an emailed payment: merchant -> category,
-- and card -> account.
CREATE TABLE payee_rules (
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    payee_key   TEXT NOT NULL,
    category_id BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, payee_key)
);
CREATE TABLE source_rules (
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source     TEXT NOT NULL,
    account_id BIGINT NOT NULL REFERENCES wallet_accounts (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, source)
);

-- Each user's private forwarding address is pay-<token>@INBOUND_DOMAIN.
CREATE TABLE inbox_addresses (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token      TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inbound_emails (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message_id     TEXT NOT NULL,
    from_addr      TEXT NOT NULL DEFAULT '',
    subject        TEXT NOT NULL DEFAULT '',
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- payment: made a transaction; ignored: known non-payment (statement,
    -- verification); unparsed: needs the user; forwarding: Gmail's
    -- forwarding confirmation.
    status         TEXT NOT NULL CHECK (status IN ('payment', 'ignored', 'unparsed', 'forwarding')),
    parser         TEXT NOT NULL DEFAULT '',
    detail         TEXT NOT NULL DEFAULT '',
    -- Kept only for unparsed and forwarding emails, so the user can act on them.
    body_text      TEXT NOT NULL DEFAULT '',
    confirm_url    TEXT NOT NULL DEFAULT '',
    transaction_id BIGINT REFERENCES transactions (id) ON DELETE SET NULL,
    dismissed      BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, message_id)
);
CREATE INDEX inbound_emails_user_idx ON inbound_emails (user_id, created_at DESC);
