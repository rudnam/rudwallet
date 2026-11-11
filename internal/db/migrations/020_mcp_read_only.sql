-- Read-only connections: an MCP client that asks for the "read" scope (e.g.
-- RudDash, which only shows spending) gets tokens that can call read tools
-- only. Existing connections keep full access.
ALTER TABLE mcp_auth_codes ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE mcp_tokens ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT false;
