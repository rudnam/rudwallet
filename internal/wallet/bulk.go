package wallet

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"slices"

	"github.com/lib/pq"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
)

// maxBulk is the most records one bulk change touches: a page of records.
const maxBulk = maxList

// BulkResult says how many of the chosen records changed. The others were
// not the user's, or didn't fit the change (a transfer has no category).
type BulkResult struct {
	Changed int `json:"changed"`
	Skipped int `json:"skipped"`
}

// BulkDelete deletes the user's records among ids; Restore can undo it.
func (s *Store) BulkDelete(ctx context.Context, userID int64, ids []int64) (int, error) {
	return s.trash(ctx, userID, ids, false)
}

// BulkCategorize gives the user's records among ids a category, or none
// when categoryID is nil. Only records of the category's kind change, and
// transfers never do.
func (s *Store) BulkCategorize(ctx context.Context, userID int64, ids []int64, categoryID *int64) (int, error) {
	kinds := []string{"expense", "income"}
	if categoryID != nil {
		c, err := s.category(ctx, userID, *categoryID)
		if errors.Is(err, db.ErrNotFound) {
			return 0, invalid("no such category")
		} else if err != nil {
			return 0, err
		}
		kinds = []string{c.Kind}
	}
	return affected(s.db.ExecContext(ctx,
		`UPDATE transactions SET category_id = $3
		 WHERE user_id = $1 AND id = ANY($2::bigint[]) AND kind = ANY($4::text[])`,
		userID, pq.Array(ids), categoryID, pq.Array(kinds)))
}

// BulkMove moves the user's records among ids to an account; for a
// transfer, that is the account it comes from. A transfer into that same
// account is left alone.
func (s *Store) BulkMove(ctx context.Context, userID int64, ids []int64, accountID int64) (int, error) {
	if _, err := s.account(ctx, userID, accountID); errors.Is(err, db.ErrNotFound) {
		return 0, invalid("no such account")
	} else if err != nil {
		return 0, err
	}
	return affected(s.db.ExecContext(ctx,
		`UPDATE transactions SET account_id = $3
		 WHERE user_id = $1 AND id = ANY($2::bigint[]) AND to_account_id IS DISTINCT FROM $3`,
		userID, pq.Array(ids), accountID))
}

// BulkConfirm confirms the user's records among ids that are waiting for
// review and ready: an expense or income with a category and an amount, not
// flagged as a possible duplicate. Shops and cards nothing was learned for
// yet are remembered; rules already learned stay as they are.
func (s *Store) BulkConfirm(ctx context.Context, userID int64, ids []int64) (int, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx,
		`UPDATE transactions SET status = 'confirmed'
		 WHERE user_id = $1 AND id = ANY($2::bigint[]) AND status = 'review' AND kind <> 'transfer'
		   AND category_id IS NOT NULL AND amount > 0 AND duplicate_of_id IS NULL
		 RETURNING `+txColumns, userID, pq.Array(ids))
	if err != nil {
		return 0, err
	}
	var confirmed []Transaction
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			rows.Close()
			return 0, err
		}
		confirmed = append(confirmed, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	for _, t := range confirmed {
		if t.Source != "email" && t.Source != "notification" {
			continue
		}
		if err := learnIfNew(ctx, tx, userID, t); err != nil {
			return 0, err
		}
	}
	return len(confirmed), tx.Commit()
}

func affected(res sql.Result, err error) (int, error) {
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	return int(n), err
}

type bulkInput struct {
	IDs []int64 `json:"ids"`
	// Action is "delete", "categorize" (to CategoryID, or none if null),
	// "move" (to AccountID), "confirm" (records to review that are ready),
	// "tag" or "untag" (add or take off Tag).
	Action     string `json:"action"`
	CategoryID *int64 `json:"category_id"`
	AccountID  int64  `json:"account_id"`
	Tag        string `json:"tag"`
}

func (h *Handler) bulkTransactions(w http.ResponseWriter, r *http.Request) error {
	var in bulkInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	slices.Sort(in.IDs)
	in.IDs = slices.Compact(in.IDs)
	if len(in.IDs) == 0 || len(in.IDs) > maxBulk {
		return httpx.Errorf(http.StatusBadRequest, "choose 1 to %d records", maxBulk)
	}
	ctx, uid := r.Context(), userID(r)
	var n int
	var err error
	switch in.Action {
	case "delete":
		n, err = h.store.BulkDelete(ctx, uid, in.IDs)
	case "categorize":
		n, err = h.store.BulkCategorize(ctx, uid, in.IDs, in.CategoryID)
	case "move":
		n, err = h.store.BulkMove(ctx, uid, in.IDs, in.AccountID)
	case "confirm":
		n, err = h.store.BulkConfirm(ctx, uid, in.IDs)
	case "tag", "untag":
		tags, terr := cleanTags([]string{in.Tag})
		if terr != nil {
			return terr
		}
		if len(tags) == 0 {
			return httpx.Errorf(http.StatusBadRequest, "enter a tag")
		}
		if in.Action == "tag" {
			n, err = h.store.BulkTag(ctx, uid, in.IDs, tags[0])
		} else {
			n, err = h.store.BulkUntag(ctx, uid, in.IDs, tags[0])
		}
	default:
		return httpx.Errorf(http.StatusBadRequest, "action must be delete, categorize, move, confirm, tag or untag")
	}
	if err := storeErr(err, "record"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, BulkResult{Changed: n, Skipped: len(in.IDs) - n})
	return nil
}
