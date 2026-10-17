-- Resetting a forgotten password (internal/account/reset.go): a link sent by
-- email, single use, for an hour. Only a SHA-256 hash of each token is
-- stored, and a link stops working if the account's email changes.
CREATE TABLE password_tokens (
    token_hash BYTEA PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      TEXT NOT NULL, -- the address the link was sent to
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX password_tokens_user_id_idx ON password_tokens (user_id);
