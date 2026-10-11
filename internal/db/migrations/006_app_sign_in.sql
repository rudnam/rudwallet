-- Signing in to the Android app with Google (internal/account/app.go). The
-- sign-in happens in the phone's browser, which hands the app a one-time
-- code; the app trades it, with the secret whose hash (challenge) it sent
-- first, for a session token. Only SHA-256 hashes of codes are stored.
CREATE TABLE app_codes (
    code_hash  BYTEA PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    challenge  BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX app_codes_user_id_idx ON app_codes (user_id);

-- 005's widget tables are no longer used: the widgets now sign in with the
-- app's session. Drop them in a later release, once no server uses them.
