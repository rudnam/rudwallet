package wallet

import (
	"context"
	"net/http"
	"slices"

	"github.com/lib/pq"
	"github.com/rudnam/wallet-app/internal/httpx"
)

// Deleted records are kept this long, so a delete can be undone.
const trashKeep = "1 day"

// Possible duplicates set aside (SetAside) are kept this long, so one that
// was a payment of its own can still be brought back.
const setAsideKeep = "60 days"

// kept is the condition on deleted_transactions d for a row still kept.
const kept = `d.deleted_at >= now() - CASE WHEN d.set_aside THEN interval '` + setAsideKeep + `' ELSE interval '` + trashKeep + `' END`

// trash deletes the user's records among ids, keeping each one, and what
// pointed at it, in deleted_transactions for Restore. It returns how many
// were deleted. setAside marks them as possible duplicates set aside, not
// deleted by the user.
func (s *Store) trash(ctx context.Context, userID int64, ids []int64, setAside bool) (int, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM deleted_transactions d WHERE d.user_id = $1 AND NOT (`+kept+`)`,
		userID); err != nil {
		return 0, err
	}
	// Every part of one statement sees the rows as they were before it, so
	// the links are read before the delete clears them.
	n, err := affected(tx.ExecContext(ctx,
		`WITH gone AS (
			DELETE FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[]) RETURNING `+txColumns+`, import_key
		)
		INSERT INTO deleted_transactions (id, user_id, kind, amount, account_id, to_account_id, category_id,
			occurred_at, payee, note, status, source, source_label, duplicate_of_id, import_key, created_at, tags,
			notification_ids, duplicate_ids, set_aside)
		SELECT g.id, $1, g.kind, g.amount, g.account_id, g.to_account_id, g.category_id,
			g.occurred_at, g.payee, g.note, g.status, g.source, g.source_label, g.duplicate_of_id, g.import_key, g.created_at, g.tags,
			ARRAY(SELECT p.id FROM payment_notifications p WHERE p.user_id = $1 AND p.transaction_id = g.id),
			ARRAY(SELECT d.id FROM transactions d WHERE d.user_id = $1 AND d.duplicate_of_id = g.id),
			$3
		FROM gone g`,
		userID, pq.Array(ids), setAside))
	if err != nil {
		return 0, err
	}
	return n, tx.Commit()
}

// Restore puts back the user's deleted records among ids, with their ids,
// and links them again to the notifications and duplicates that
// pointed at them. It returns how many came back: a record deleted too long
// ago, or with its account, is gone, and one imported again since stays out.
// A possible duplicate set aside comes back to review, no longer flagged:
// the user said it is a payment of its own.
func (s *Store) Restore(ctx context.Context, userID int64, ids []int64) (int, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx,
		`INSERT INTO transactions (id, user_id, kind, amount, account_id, to_account_id, category_id,
			occurred_at, payee, note, status, source, source_label, duplicate_of_id, import_key, created_at, tags)
		OVERRIDING SYSTEM VALUE
		SELECT d.id, d.user_id, d.kind, d.amount, d.account_id, d.to_account_id, d.category_id,
			d.occurred_at, d.payee, d.note, d.status, d.source, d.source_label,
			CASE WHEN NOT d.set_aside THEN (SELECT t.id FROM transactions t WHERE t.user_id = $1 AND t.id = d.duplicate_of_id) END,
			d.import_key, d.created_at, d.tags
		FROM deleted_transactions d
		WHERE d.user_id = $1 AND d.id = ANY($2::bigint[]) AND `+kept+`
		ON CONFLICT DO NOTHING
		RETURNING id`,
		userID, pq.Array(ids))
	if err != nil {
		return 0, err
	}
	var restored []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		restored = append(restored, id)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return 0, err
	}
	if err := rows.Close(); err != nil {
		return 0, err
	}
	// Link again only what nothing else has taken since.
	for _, q := range []string{
		`UPDATE payment_notifications p SET transaction_id = d.id,
			status = CASE WHEN d.set_aside THEN 'payment' ELSE p.status END,
			detail = CASE WHEN d.set_aside THEN '` + broughtBack + `' ELSE p.detail END
		 FROM deleted_transactions d
		 WHERE d.user_id = $1 AND d.id = ANY($2::bigint[]) AND p.user_id = $1 AND p.id = ANY(d.notification_ids) AND p.transaction_id IS NULL`,
		// A record still to review was flagged as a duplicate of this one.
		`UPDATE transactions t SET duplicate_of_id = d.id FROM deleted_transactions d
		 WHERE d.user_id = $1 AND d.id = ANY($2::bigint[]) AND t.user_id = $1 AND t.id = ANY(d.duplicate_ids)
		   AND t.status = 'review' AND t.duplicate_of_id IS NULL`,
	} {
		if _, err := tx.ExecContext(ctx, q, userID, pq.Array(restored)); err != nil {
			return 0, err
		}
	}
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM deleted_transactions WHERE user_id = $1 AND id = ANY($2::bigint[])`, userID, pq.Array(ids)); err != nil {
		return 0, err
	}
	return len(restored), tx.Commit()
}

type restoreInput struct {
	IDs []int64 `json:"ids"`
}

// RestoreResult says how many of the records came back.
type RestoreResult struct {
	Restored int `json:"restored"`
}

func (h *Handler) restoreTransactions(w http.ResponseWriter, r *http.Request) error {
	var in restoreInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	slices.Sort(in.IDs)
	in.IDs = slices.Compact(in.IDs)
	if len(in.IDs) == 0 || len(in.IDs) > maxBulk {
		return httpx.Errorf(http.StatusBadRequest, "choose 1 to %d records", maxBulk)
	}
	n, err := h.store.Restore(r.Context(), userID(r), in.IDs)
	if err != nil {
		return err
	}
	if n == 0 {
		return httpx.Errorf(http.StatusNotFound, "those records can no longer be restored")
	}
	httpx.WriteJSON(w, http.StatusOK, RestoreResult{Restored: n})
	return nil
}
