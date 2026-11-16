-- A payment notification that looks like a record the user already has (the
-- same amount within a day, from another source) is set aside rather than
-- left in records to review (wallet.Store.SetAside): it waits in
-- deleted_transactions with set_aside, kept for 60 days rather than one, and
-- POST /api/transactions/restore brings it back if it was a payment of its
-- own. The previous release doesn't read the column: it would treat these
-- like deleted records, kept for a day.
ALTER TABLE deleted_transactions ADD COLUMN set_aside BOOLEAN NOT NULL DEFAULT false;

-- The possible duplicates already waiting for review are set aside too, as
-- if they had arrived now, with what pointed at them.
UPDATE payment_notifications p
SET status = 'matched', detail = 'Looks like a record you already have, so it was set aside with the duplicates.'
FROM transactions t
WHERE p.transaction_id = t.id AND p.user_id = t.user_id
  AND t.status = 'review' AND t.source = 'notification' AND t.duplicate_of_id IS NOT NULL;

WITH gone AS (
    DELETE FROM transactions
    WHERE status = 'review' AND source = 'notification' AND duplicate_of_id IS NOT NULL
    RETURNING *
)
INSERT INTO deleted_transactions (id, user_id, kind, amount, account_id, to_account_id, category_id,
    occurred_at, payee, note, status, source, source_label, duplicate_of_id, import_key, created_at, tags,
    notification_ids, duplicate_ids, set_aside)
SELECT g.id, g.user_id, g.kind, g.amount, g.account_id, g.to_account_id, g.category_id,
    g.occurred_at, g.payee, g.note, g.status, g.source, g.source_label, g.duplicate_of_id, g.import_key, g.created_at, g.tags,
    ARRAY(SELECT p.id FROM payment_notifications p WHERE p.user_id = g.user_id AND p.transaction_id = g.id),
    ARRAY(SELECT d.id FROM transactions d WHERE d.user_id = g.user_id AND d.duplicate_of_id = g.id),
    true
FROM gone g
ON CONFLICT (id) DO NOTHING;
