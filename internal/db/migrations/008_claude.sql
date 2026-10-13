-- Claude features (internal/ai). Each user can add their own Anthropic API
-- key: Claude then suggests categories for their records, and reads their
-- payment emails when the server has no key of its own. The key is stored
-- encrypted (AES-256-GCM, under a key derived from the server's SECRETS_KEY,
-- bound to the user) and never sent back to the browser; key_hint is its
-- last four characters, to tell keys apart.
CREATE TABLE ai_keys (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    sealed_key BYTEA NOT NULL,
    key_hint   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tokens for MCP clients (Claude Desktop, Claude Code, ...) that connect to
-- /mcp to read and tidy the user's records (internal/mcp). Only SHA-256
-- hashes of tokens are stored.
CREATE TABLE mcp_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash   BYTEA NOT NULL UNIQUE,
    user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX mcp_tokens_user_id_idx ON mcp_tokens (user_id);
