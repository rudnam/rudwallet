package wallet

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/lib/pq"
)

// importDedup decides which rows of an import are already in the wallet.
// Every importer asks it the same questions, in this order:
//
//  1. Imported before, by key: the row's import_key is on a record. All the
//     file's keys are looked up at the start, and the records they find
//     are taken, so no other row of the file can be matched to them.
//  2. Imported before, by content: a row's key can change between two
//     exports of it (PayPay numbers rows sharing a 取引番号 by their place
//     in the file; BudgetBakers keys a row by every column, which change
//     when it's edited there). A record imported from the same source
//     (sameRow), of the same kind and amount, between the same accounts, at
//     the same moment, is the same row.
//  3. Recorded another way (from a notification, by hand, or by another
//     importer): recorded, or the importer's own rule, which excludes
//     taken records.
//
// Each record matches one row at most, and the records the import adds are
// taken too (add), so rows of one file never match each other.
type importDedup struct {
	ctx    context.Context
	tx     *sql.Tx
	userID int64
	prefix string // the source's import_key prefix, e.g. "pp:"
	keys   map[string]int64
	taken  []int64 // never nil: pq sends a nil slice as NULL, which matches nothing
}

func newImportDedup(ctx context.Context, tx *sql.Tx, userID int64, prefix string, keys []string) (*importDedup, error) {
	d := &importDedup{ctx: ctx, tx: tx, userID: userID, prefix: prefix, keys: map[string]int64{}, taken: []int64{}}
	if keys == nil {
		keys = []string{}
	}
	rows, err := tx.QueryContext(ctx,
		`SELECT id, import_key FROM transactions WHERE user_id = $1 AND import_key = ANY($2::text[])`,
		userID, pq.Array(keys))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var key string
		if err := rows.Scan(&id, &key); err != nil {
			return nil, err
		}
		d.keys[key] = id
		d.taken = append(d.taken, id)
	}
	return d, rows.Err()
}

// imported is the record imported before with this key, if any.
func (d *importDedup) imported(key string) (int64, bool) {
	id, ok := d.keys[key]
	return id, ok
}

// sameRow finds a record imported from the same source that is this row
// under another key, and takes it. account 0 is an account the import
// creates, which has no records yet.
func (d *importDedup) sameRow(t Transaction) (int64, bool, error) {
	if t.AccountID == 0 || t.ToAccountID != nil && *t.ToAccountID == 0 {
		return 0, false, nil
	}
	return d.find(
		`SELECT id FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND account_id = $4
		   AND to_account_id IS NOT DISTINCT FROM $5 AND occurred_at = $6
		   AND import_key LIKE $7 || '%'
		   AND NOT (id = ANY($8::bigint[]))
		 ORDER BY id LIMIT 1`,
		d.userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.OccurredAt, d.prefix, pq.Array(d.taken))
}

// recorded finds a record made another way (not by this source's imports)
// of the same kind and amount, between the same accounts, within window
// either side of the row's time, the nearest first, and takes it.
func (d *importDedup) recorded(t Transaction, window time.Duration) (int64, bool, error) {
	if t.AccountID == 0 || t.ToAccountID != nil && *t.ToAccountID == 0 {
		return 0, false, nil
	}
	return d.find(
		`SELECT id FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND account_id = $4
		   AND to_account_id IS NOT DISTINCT FROM $5
		   AND occurred_at BETWEEN $6::timestamptz - $7 * interval '1 second' AND $6::timestamptz + $7 * interval '1 second'
		   AND (import_key IS NULL OR import_key NOT LIKE $8 || '%')
		   AND NOT (id = ANY($9::bigint[]))
		 ORDER BY abs(extract(epoch FROM occurred_at - $6::timestamptz)), id LIMIT 1`,
		d.userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.OccurredAt, int64(window/time.Second), d.prefix, pq.Array(d.taken))
}

// check asks the three questions in order for a row with this key that
// would be recorded as t. It answers "imported" or "matched" with the
// record found, or "" for a row to record; window is how far apart a
// record made another way may be.
func (d *importDedup) check(key string, t Transaction, window time.Duration) (string, int64, error) {
	if id, ok := d.imported(key); ok {
		return "imported", id, nil
	}
	if id, ok, err := d.sameRow(t); err != nil || ok {
		return "imported", id, err
	}
	if id, ok, err := d.recorded(t, window); err != nil || ok {
		return "matched", id, err
	}
	return "", 0, nil
}

// foreignSlack is how far apart, as a share of the billed amount, a
// payment in another currency and its notification's yen amount may be.
const foreignSlack = 0.05

// recordedNear is recorded for a payment whose amount others saw only
// roughly: a record made another way of the same kind, in the same
// account, within slack (a share of t's amount) of it, within window
// either side of its time. The nearest in time is taken, then the nearest
// in amount.
func (d *importDedup) recordedNear(t Transaction, window time.Duration, slack float64) (int64, bool, error) {
	if t.AccountID == 0 || t.ToAccountID != nil {
		return 0, false, nil
	}
	diff := int64(float64(t.Amount) * slack)
	return d.find(
		`SELECT id FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND account_id = $3 AND to_account_id IS NULL
		   AND amount BETWEEN $4::bigint - $5::bigint AND $4::bigint + $5::bigint
		   AND occurred_at BETWEEN $6::timestamptz - $7 * interval '1 second' AND $6::timestamptz + $7 * interval '1 second'
		   AND (import_key IS NULL OR import_key NOT LIKE $8 || '%')
		   AND NOT (id = ANY($9::bigint[]))
		 ORDER BY abs(extract(epoch FROM occurred_at - $6::timestamptz)), abs(amount - $4::bigint), id LIMIT 1`,
		d.userID, t.Kind, t.AccountID, t.Amount, diff, t.OccurredAt, int64(window/time.Second), d.prefix, pq.Array(d.taken))
}

// find runs a query for one record's id and takes it.
func (d *importDedup) find(q string, args ...any) (int64, bool, error) {
	var id int64
	err := d.tx.QueryRowContext(d.ctx, q, args...).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	d.take(id)
	return id, true, nil
}

// take marks a record as matched to a row, or added by the import.
func (d *importDedup) take(id int64) { d.taken = append(d.taken, id) }

// takenArray is the taken records, for an importer's own query.
func (d *importDedup) takenArray() any { return pq.Array(d.taken) }
