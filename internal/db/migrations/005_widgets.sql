-- Home screen widgets on Android (internal/widget). A widget runs outside
-- the browser, so it can't use the session cookie: each connected phone gets
-- its own token, which can only read the widget's summary. Only SHA-256
-- hashes of tokens and codes are stored.
CREATE TABLE widget_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash   BYTEA NOT NULL UNIQUE,
    user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device       TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX widget_tokens_user_id_idx ON widget_tokens (user_id);

-- One-time codes, valid for minutes, that the signed-in web app hands to the
-- Android app. The Android app trades one for a token by also showing the
-- secret whose hash (challenge) it sent first, so a code alone is useless.
CREATE TABLE widget_codes (
    code_hash  BYTEA PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    challenge  BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX widget_codes_user_id_idx ON widget_codes (user_id);
