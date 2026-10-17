-- Email automation (internal/inbox) and users' own Claude API keys
-- (internal/ai) were removed: payments now come from the phone's
-- notifications, and Claude connects over MCP. The forwarding addresses and
-- saved keys go, and the stored emails are deleted. The previous release
-- used inbox_addresses and ai_keys only in those features, so it keeps
-- working while a deploy rolls out; it still reads inbound_emails (and
-- writes deleted_transactions.email_ids) whenever a record is deleted, so
-- those stay, empty, until the next release drops them. Records that came
-- from emails stay (source 'email').
DROP TABLE inbox_addresses;
DROP TABLE ai_keys;
DELETE FROM inbound_emails;
