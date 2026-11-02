-- A record added on a phone without a connection is sent later, maybe more
-- than once (an answer lost on a bad network looks like a failure). The app
-- names each new record with client_id, so sending it again finds the one
-- already saved instead of adding another.
ALTER TABLE transactions ADD COLUMN client_id TEXT;
CREATE UNIQUE INDEX transactions_client_id ON transactions (user_id, client_id) WHERE client_id IS NOT NULL;
