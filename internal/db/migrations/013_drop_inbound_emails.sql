-- The second half of 012: the previous release no longer reads
-- inbound_emails or writes deleted_transactions.email_ids, so they go.
-- Dropping the table drops its indexes (inbound_emails_user_idx,
-- inbound_emails_tx_idx).
DROP TABLE inbound_emails;
ALTER TABLE deleted_transactions DROP COLUMN email_ids;
