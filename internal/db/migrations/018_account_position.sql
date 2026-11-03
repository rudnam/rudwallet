-- Where an account sits among the user's accounts: Wallet's card stack, the
-- account pickers, and which is first (where payments go when no account is
-- set). It starts at the id, the order until now, so nothing moves until the
-- user rearranges the cards. Rows the previous release adds have none, and
-- sort by their id, as before.
ALTER TABLE wallet_accounts ADD COLUMN position BIGINT;
UPDATE wallet_accounts SET position = id;
