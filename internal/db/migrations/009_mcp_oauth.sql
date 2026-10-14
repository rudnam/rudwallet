-- Sign-in for MCP clients (internal/mcp/oauth.go): Claude's connectors
-- register themselves, send the user here to approve, and get tokens
-- through OAuth 2.1 with PKCE instead of a token pasted by hand.

-- Clients that registered themselves (RFC 7591). They are public clients:
-- no secret, so PKCE is what proves a code was asked for by its client.
CREATE TABLE mcp_clients (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_id     TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    redirect_uris TEXT[] NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approvals waiting to be exchanged for tokens: single use, a few minutes.
CREATE TABLE mcp_auth_codes (
    code_hash      BYTEA PRIMARY KEY,
    client_id      TEXT NOT NULL REFERENCES mcp_clients (client_id) ON DELETE CASCADE,
    user_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    redirect_uri   TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL
);

-- A token from sign-in expires and is renewed with its refresh token, which
-- changes each time. Tokens made by hand in Settings have neither.
ALTER TABLE mcp_tokens
    ADD COLUMN client_id    TEXT REFERENCES mcp_clients (client_id) ON DELETE CASCADE,
    ADD COLUMN expires_at   TIMESTAMPTZ,
    ADD COLUMN refresh_hash BYTEA UNIQUE;
