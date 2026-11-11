package wallet

import (
	"context"
	"database/sql"
	"net/http"
	"time"

	"github.com/lib/pq"
	"github.com/rudnam/wallet-app/internal/httpx"
)

// SetAsideDetail is what a payment notification says once its record was
// set aside as a possible duplicate.
const SetAsideDetail = "Looks like a record you already have, so it was set aside with the duplicates."

// broughtBack is what it says once the user brought that record back.
const broughtBack = "Brought back from the duplicates."

// SetAside takes a record flagged as a possible duplicate out of the
// records to review, and keeps it, with what pointed at it, for 60 days:
// POST /api/transactions/restore brings it back if it was a payment of its
// own. Nothing happens if the record is gone or isn't flagged.
func (s *Store) SetAside(ctx context.Context, userID, id int64) (bool, error) {
	var flagged bool
	err := s.db.QueryRowContext(ctx,
		`SELECT duplicate_of_id IS NOT NULL AND status = 'review' FROM transactions WHERE user_id = $1 AND id = $2`,
		userID, id).Scan(&flagged)
	if err == sql.ErrNoRows || (err == nil && !flagged) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	n, err := s.trash(ctx, userID, []int64{id}, true)
	return n > 0, err
}

// SetAsideRecord is a possible duplicate set aside: the record as it would
// be, when it was set aside, and the record it looks like, if that one is
// still there.
type SetAsideRecord struct {
	Transaction
	SetAsideAt  time.Time    `json:"set_aside_at"`
	DuplicateOf *Transaction `json:"duplicate_of"`
}

// maxSetAside caps the list; 60 days of duplicates fit.
const maxSetAside = 500

// SetAsideRecords returns the user's possible duplicates set aside and
// still kept, the latest payment first.
func (s *Store) SetAsideRecords(ctx context.Context, userID int64) ([]SetAsideRecord, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+txColumns+`, deleted_at FROM deleted_transactions d
		 WHERE d.user_id = $1 AND d.set_aside AND `+kept+`
		 ORDER BY d.occurred_at DESC, d.id DESC LIMIT $2`, userID, maxSetAside)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []SetAsideRecord{}
	var originals []int64
	for rows.Next() {
		var r SetAsideRecord
		if r.Transaction, err = scanTx(withExtra{rows, []any{&r.SetAsideAt}}); err != nil {
			return nil, err
		}
		if r.DuplicateOfID != nil {
			originals = append(originals, *r.DuplicateOfID)
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(originals) == 0 {
		return out, nil
	}
	orig, err := s.db.QueryContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[])`, userID, pq.Array(originals))
	if err != nil {
		return nil, err
	}
	defer orig.Close()
	byID := map[int64]Transaction{}
	for orig.Next() {
		t, err := scanTx(orig)
		if err != nil {
			return nil, err
		}
		byID[t.ID] = t
	}
	if err := orig.Err(); err != nil {
		return nil, err
	}
	for i, r := range out {
		if r.DuplicateOfID == nil {
			continue
		}
		if t, ok := byID[*r.DuplicateOfID]; ok {
			out[i].DuplicateOf = &t
		}
	}
	return out, nil
}

// SetAsideCount is how many possible duplicates are set aside and kept.
func (s *Store) SetAsideCount(ctx context.Context, userID int64) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx,
		`SELECT count(*) FROM deleted_transactions d WHERE d.user_id = $1 AND d.set_aside AND `+kept, userID).Scan(&n)
	return n, err
}

// withExtra scans a row's extra columns after the ones scanTx reads.
type withExtra struct {
	row   interface{ Scan(...any) error }
	extra []any
}

func (w withExtra) Scan(dest ...any) error { return w.row.Scan(append(dest, w.extra...)...) }

type setAsideList struct {
	Duplicates []SetAsideRecord `json:"duplicates"`
}

func (h *Handler) listSetAside(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.SetAsideRecords(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, setAsideList{Duplicates: list})
	return nil
}
