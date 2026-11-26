package wallet

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"time"

	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
)

// Import sources, as import_batches.source has them.
const (
	SourcePayPay       = "paypay"
	SourceMUFG         = "mufg"
	SourceICCard       = "iccard"
	SourceBudgetBakers = "budgetbakers"
	SourceVpass        = "vpass"
)

// ImportRow is one row of a PayPay or BudgetBakers file as an import
// records it, or would: the preview is the import run and rolled back, so
// what it shows is what importing does.
type ImportRow struct {
	OccurredAt time.Time `json:"occurred_at"`
	Kind       string    `json:"kind"`
	Amount     int64     `json:"amount"`
	Payee      string    `json:"payee"`
	Note       string    `json:"note"`
	// Account and ToAccount are names: in a preview the account may be one
	// the import would create.
	Account   string `json:"account"`
	ToAccount string `json:"to_account,omitempty"`
	// Status: new (to record), imported (by an earlier import), matched
	// (already recorded another way), or skipped (a way of paying the user
	// left out).
	Status string `json:"status"`
	// MatchedID is the record an imported or matched row is.
	MatchedID *int64 `json:"matched_id,omitempty"`
	// AllDay: the file gives the row's day but not its time (a Vpass
	// statement), so OccurredAt's time of day means nothing to show.
	AllDay bool `json:"all_day,omitempty"`
}

// importRun is an import being recorded: its duplicate checks, and the
// batch its records go in, made with the first one. dry runs record into a
// transaction that is rolled back.
type importRun struct {
	*importDedup
	source   string
	label    string
	from, to *time.Time
	batch    int64
	dry      bool
}

func newImportRun(ctx context.Context, tx *sql.Tx, userID int64, source, prefix string, keys []string, dry bool) (*importRun, error) {
	d, err := newImportDedup(ctx, tx, userID, prefix, keys)
	if err != nil {
		return nil, err
	}
	return &importRun{importDedup: d, source: source, dry: dry}, nil
}

// period widens the batch's period to include a row's time.
func (r *importRun) period(t time.Time) {
	if r.from == nil || t.Before(*r.from) {
		r.from = &t
	}
	if r.to == nil || t.After(*r.to) {
		r.to = &t
	}
}

// newBatch makes the import's batch, once.
func (r *importRun) newBatch() error {
	if r.batch != 0 {
		return nil
	}
	return r.tx.QueryRowContext(r.ctx,
		`INSERT INTO import_batches (user_id, source, label) VALUES ($1, $2, $3) RETURNING id`,
		r.userID, r.source, r.label).Scan(&r.batch)
}

// add records a row, confirmed, in the import's batch, and takes it.
func (r *importRun) add(t Transaction, key string) (int64, error) {
	if err := r.newBatch(); err != nil {
		return 0, err
	}
	var id int64
	if err := r.tx.QueryRowContext(r.ctx,
		`INSERT INTO transactions (user_id, kind, amount, account_id, to_account_id, category_id, occurred_at,
			payee, note, status, source, import_key, import_batch_id)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'confirmed', 'import', $10, $11) RETURNING id`,
		r.userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID, t.OccurredAt, t.Payee, t.Note, key, r.batch).Scan(&id); err != nil {
		return 0, err
	}
	r.period(t.OccurredAt)
	r.take(id)
	return id, nil
}

// finish saves what the import did on its batch, and the starting balance
// it changed (adjusted, of adjustedAccount), for undo. An import that
// changed nothing has no batch. It sets res.BatchID.
func (r *importRun) finish(res *ImportResult, adjustedAccount int64, adjusted int64) error {
	if r.dry || r.batch == 0 && adjusted == 0 {
		return nil
	}
	if err := r.newBatch(); err != nil {
		return err
	}
	var acct *int64
	if adjusted != 0 {
		acct = &adjustedAccount
	}
	res.BatchID = &r.batch
	_, err := r.tx.ExecContext(r.ctx,
		`UPDATE import_batches SET imported = $3, duplicates = $4, matched = $5, skipped = $6,
			period_from = $7, period_to = $8, adjusted_account_id = $9, adjusted_amount = $10
		 WHERE user_id = $1 AND id = $2`,
		r.userID, r.batch, res.Imported, res.Duplicates, res.Matched, res.Skipped, r.from, r.to, acct, adjusted)
	return err
}

// accountNames is the user's accounts' names by id.
func accountNames(ctx context.Context, tx *sql.Tx, userID int64) (map[int64]string, error) {
	rows, err := tx.QueryContext(ctx, `SELECT id, name FROM wallet_accounts WHERE user_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]string{}
	for rows.Next() {
		var id int64
		var name string
		if err := rows.Scan(&id, &name); err != nil {
			return nil, err
		}
		out[id] = name
	}
	return out, rows.Err()
}

// ImportBatch is one import that added records, for the import page's history.
type ImportBatch struct {
	ID         int64      `json:"id"`
	Source     string     `json:"source"`
	Label      string     `json:"label"`
	From       *time.Time `json:"from"`
	To         *time.Time `json:"to"`
	Imported   int        `json:"imported"`
	Duplicates int        `json:"duplicates"`
	Matched    int        `json:"matched"`
	Skipped    int        `json:"skipped"`
	// Remaining is how many of its records are still in the wallet.
	Remaining int        `json:"remaining"`
	CreatedAt time.Time  `json:"created_at"`
	UndoneAt  *time.Time `json:"undone_at"`
}

const maxBatches = 30

// ImportBatches lists the user's latest imports, newest first.
func (s *Store) ImportBatches(ctx context.Context, userID int64) ([]ImportBatch, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT b.id, b.source, b.label, b.period_from, b.period_to, b.imported, b.duplicates, b.matched, b.skipped,
			(SELECT count(*) FROM transactions t WHERE t.user_id = b.user_id AND t.import_batch_id = b.id),
			b.created_at, b.undone_at
		 FROM import_batches b WHERE b.user_id = $1 ORDER BY b.created_at DESC, b.id DESC LIMIT $2`,
		userID, maxBatches)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ImportBatch{}
	for rows.Next() {
		var b ImportBatch
		if err := rows.Scan(&b.ID, &b.Source, &b.Label, &b.From, &b.To, &b.Imported, &b.Duplicates, &b.Matched,
			&b.Skipped, &b.Remaining, &b.CreatedAt, &b.UndoneAt); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// UndoImport moves the records an import added, those still in the wallet,
// to the trash, and puts back a starting balance it changed. It returns
// their ids, which Restore brings back (within the trash's day).
func (s *Store) UndoImport(ctx context.Context, userID, batchID int64) ([]int64, error) {
	var adjusted int64
	err := s.db.QueryRowContext(ctx,
		`SELECT adjusted_amount FROM import_batches WHERE user_id = $1 AND id = $2`,
		userID, batchID).Scan(&adjusted)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, db.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	ids := []int64{}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id FROM transactions WHERE user_id = $1 AND import_batch_id = $2`, userID, batchID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if _, err := s.trash(ctx, userID, ids, false); err != nil {
		return nil, err
	}
	// The balance is put back once, however often the import is undone
	// and its records restored.
	if _, err := s.db.ExecContext(ctx,
		`WITH b AS (
			UPDATE import_batches SET undone_at = now(), adjusted_amount = 0
			WHERE user_id = $1 AND id = $2 RETURNING adjusted_account_id, $3::bigint AS amount
		)
		UPDATE wallet_accounts a SET initial_balance = a.initial_balance - b.amount
		FROM b WHERE a.user_id = $1 AND a.id = b.adjusted_account_id`,
		userID, batchID, adjusted); err != nil {
		return nil, err
	}
	return ids, nil
}

// listImports answers {"imports": [ImportBatch]}.
func (h *Handler) listImports(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.ImportBatches(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, map[string][]ImportBatch{"imports": list})
	return nil
}

// undoImport answers {"deleted": n, "ids": [...]}: the records moved to the
// trash, which POST /api/transactions/restore brings back.
func (h *Handler) undoImport(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "import")
	if err != nil {
		return err
	}
	ids, err := h.store.UndoImport(r.Context(), userID(r), id)
	if err := storeErr(err, "import"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"deleted": len(ids), "ids": ids})
	return nil
}
