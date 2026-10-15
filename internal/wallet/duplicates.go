package wallet

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/lib/pq"
)

// Records may be one payment recorded twice, e.g. by hand and by an import,
// when they are this close: within DuplicateSpan, and amounts within
// DuplicateTolerance percent of the larger.
const (
	DuplicateSpan      = 3 * 24 * time.Hour
	DuplicateTolerance = 5
)

// Duplicates finds pairs of the user's records of the same kind that may be
// one payment, in any accounts, both in [from, to) (zero means any). Equal
// amounts come first, then the nearest in time. It only reads.
func (s *Store) Duplicates(ctx context.Context, userID int64, from, to time.Time) ([][2]Transaction, error) {
	where := []string{"a.user_id = $1"}
	args := []any{userID, fmt.Sprintf("%d seconds", int(DuplicateSpan.Seconds())), DuplicateTolerance}
	add := func(cond string, v any) {
		args = append(args, v)
		where = append(where, fmt.Sprintf(cond, len(args)))
	}
	if !from.IsZero() {
		add("a.occurred_at >= $%[1]d AND b.occurred_at >= $%[1]d", from)
	}
	if !to.IsZero() {
		add("a.occurred_at < $%[1]d AND b.occurred_at < $%[1]d", to)
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT a.id, b.id
		 FROM transactions a JOIN transactions b
		   ON b.user_id = a.user_id AND b.id > a.id AND b.kind = a.kind
		  AND b.occurred_at BETWEEN a.occurred_at - $2::interval AND a.occurred_at + $2::interval
		  -- An amount of 0 is a foreign payment waiting for its amount.
		  AND a.amount > 0 AND b.amount > 0
		  AND abs(a.amount - b.amount) * 100 <= greatest(a.amount, b.amount) * $3
		 WHERE `+strings.Join(where, " AND ")+`
		 ORDER BY a.amount <> b.amount, abs(extract(epoch FROM a.occurred_at - b.occurred_at)), a.id, b.id
		 LIMIT `+fmt.Sprint(maxList), args...)
	if err != nil {
		return nil, err
	}
	var pairs [][2]int64
	var ids []int64
	for rows.Next() {
		var p [2]int64
		if err := rows.Scan(&p[0], &p[1]); err != nil {
			rows.Close()
			return nil, err
		}
		pairs = append(pairs, p)
		ids = append(ids, p[0], p[1])
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	rows, err = s.db.QueryContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[])`, userID, pq.Array(ids))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[int64]Transaction{}
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			return nil, err
		}
		byID[t.ID] = t
	}
	out := [][2]Transaction{}
	for _, p := range pairs {
		out = append(out, [2]Transaction{byID[p[0]], byID[p[1]]})
	}
	return out, rows.Err()
}
