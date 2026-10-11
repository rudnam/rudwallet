-- Sign in with Google, and confirming email addresses.

-- google_sub is Google's stable ID for the person; it stays NULL until they
-- first sign in with Google. People who only ever sign in with Google have
-- an empty password_hash, which no password matches.
ALTER TABLE users ADD COLUMN google_sub TEXT UNIQUE;

-- Set once the person has shown they receive mail at the address: by
-- opening the link we sent, or by signing in with Google.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Links sent by email. Only a SHA-256 hash of each token is stored.
CREATE TABLE email_tokens (
    token_hash BYTEA PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      TEXT NOT NULL, -- the address the link was sent to
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX email_tokens_user_id_idx ON email_tokens (user_id);
