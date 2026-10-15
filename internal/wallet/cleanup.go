package wallet

import (
	"cmp"
	"context"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/lib/pq"
)

// This file is for tidying a wallet from outside the app (internal/mcp):
// deleting records all or none, finding likely duplicates, correcting a
// balance, and confirming records that wait for review.

// DeleteRecords deletes the user's records with these IDs, all or none: if
// any isn't the user's, nothing is deleted. A transfer is one record, so
// deleting it takes it out of both its accounts. It returns what it deleted.
func (s *Store) DeleteRecords(ctx context.Context, userID int64, ids []int64) ([]Transaction, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx,
		`DELETE FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[]) RETURNING `+txColumns,
		userID, pq.Array(ids))
	if err != nil {
		return nil, err
	}
	deleted := []Transaction{}
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		deleted = append(deleted, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if missing := missingIDs(ids, deleted); len(missing) > 0 {
		return nil, invalid("no such record: %s; nothing was deleted", idList(missing))
	}
	slices.SortFunc(deleted, func(a, b Transaction) int { return cmp.Compare(a.ID, b.ID) })
	return deleted, tx.Commit()
}

// missingIDs are the ids not among found.
func missingIDs(ids []int64, found []Transaction) []int64 {
	have := map[int64]bool{}
	for _, t := range found {
		have[t.ID] = true
	}
	var missing []int64
	for _, id := range ids {
		if !have[id] {
			missing = append(missing, id)
		}
	}
	return missing
}

// idList writes up to ten IDs, e.g. "4, 9 and 3 more".
func idList(ids []int64) string {
	var parts []string
	for _, id := range ids[:min(len(ids), 10)] {
		parts = append(parts, fmt.Sprint(id))
	}
	out := strings.Join(parts, ", ")
	if len(ids) > 10 {
		out += fmt.Sprintf(" and %d more", len(ids)-10)
	}
	return out
}

// DuplicateQuery says which records count as likely the same payment.
type DuplicateQuery struct {
	From, To time.Time // both records in [From, To); zero means any
	// AccountID, if set, keeps pairs with a record in this account.
	AccountID int64
	// MaxApart is how far apart in time the two records may be.
	MaxApart time.Duration
	// TolerancePercent is how much the amounts may differ, as a percentage
	// of the larger one.
	TolerancePercent int
}

// DuplicatePair is two records of the same kind with close amounts and
// times, A the older by ID.
type DuplicatePair struct {
	A, B Transaction
	// SamePayee: their payees match, ignoring case, spacing and a branch
	// name after the shop's.
	SamePayee bool
}

// maxDuplicatePairs caps the pairs one search compares.
const maxDuplicatePairs = 2000

// Duplicates finds pairs of the user's records that may be one payment
// recorded twice, in any accounts, and only reads. The closest come first:
// equal amounts, then matching payees, then nearest in time. truncated says
// there were more than maxDuplicatePairs; a shorter period finds the rest.
func (s *Store) Duplicates(ctx context.Context, userID int64, q DuplicateQuery) (pairs []DuplicatePair, truncated bool, err error) {
	where := []string{"a.user_id = $1"}
	args := []any{userID, fmt.Sprintf("%d seconds", int(q.MaxApart.Seconds())), q.TolerancePercent}
	add := func(cond string, v any) {
		args = append(args, v)
		where = append(where, fmt.Sprintf(cond, len(args)))
	}
	if !q.From.IsZero() {
		add("a.occurred_at >= $%[1]d AND b.occurred_at >= $%[1]d", q.From)
	}
	if !q.To.IsZero() {
		add("a.occurred_at < $%[1]d AND b.occurred_at < $%[1]d", q.To)
	}
	if q.AccountID != 0 {
		add("$%[1]d IN (a.account_id, a.to_account_id, b.account_id, b.to_account_id)", q.AccountID)
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
		 ORDER BY abs(extract(epoch FROM a.occurred_at - b.occurred_at)), a.id, b.id
		 LIMIT `+fmt.Sprint(maxDuplicatePairs+1), args...)
	if err != nil {
		return nil, false, err
	}
	var ids [][2]int64
	for rows.Next() {
		var a, b int64
		if err := rows.Scan(&a, &b); err != nil {
			rows.Close()
			return nil, false, err
		}
		ids = append(ids, [2]int64{a, b})
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	if len(ids) > maxDuplicatePairs {
		ids, truncated = ids[:maxDuplicatePairs], true
	}
	records, err := s.byID(ctx, userID, ids)
	if err != nil {
		return nil, false, err
	}
	for _, p := range ids {
		a, b := records[p[0]], records[p[1]]
		pairs = append(pairs, DuplicatePair{A: a, B: b, SamePayee: samePayee(a.Payee, b.Payee)})
	}
	// Stable, so pairs that tie stay nearest in time first.
	slices.SortStableFunc(pairs, func(x, y DuplicatePair) int {
		if xe, ye := x.A.Amount == x.B.Amount, y.A.Amount == y.B.Amount; xe != ye {
			return boolFirst(xe)
		}
		if x.SamePayee != y.SamePayee {
			return boolFirst(x.SamePayee)
		}
		return 0
	})
	return pairs, truncated, nil
}

func boolFirst(b bool) int {
	if b {
		return -1
	}
	return 1
}

// byID loads the user's records in pairs, by ID.
func (s *Store) byID(ctx context.Context, userID int64, pairs [][2]int64) (map[int64]Transaction, error) {
	var ids []int64
	for _, p := range pairs {
		ids = append(ids, p[0], p[1])
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[])`, userID, pq.Array(ids))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]Transaction{}
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			return nil, err
		}
		out[t.ID] = t
	}
	return out, rows.Err()
}

// samePayee: a and b name the same shop, ignoring case and spacing, or one
// starts with the other (a chain and its branch).
func samePayee(a, b string) bool {
	ka, kb := PayeeKey(a), PayeeKey(b)
	if ka == "" || kb == "" {
		return false
	}
	if len(ka) > len(kb) {
		ka, kb = kb, ka
	}
	return ka == kb || (len([]rune(ka)) >= 3 && strings.HasPrefix(kb, ka))
}

// BalanceChange is an account before and after SetBalance.
type BalanceChange struct {
	Before, After Account
	// ToReview counts the account's records waiting for review, which no
	// balance includes until they are confirmed.
	ToReview int
}

// SetBalance corrects an account's balance to the figure on a statement,
// without changing any record: it sets the starting balance so that the
// starting balance plus the confirmed records before asOf comes to balance.
// A zero asOf counts every record, so the balance shown is balance.
func (s *Store) SetBalance(ctx context.Context, userID, accountID, balance int64, asOf time.Time) (BalanceChange, error) {
	var c BalanceChange
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return c, err
	}
	defer tx.Rollback()
	// Lock the account, so a concurrent correction can't interleave.
	if err := rowsChanged(tx.ExecContext(ctx,
		`SELECT 1 FROM wallet_accounts WHERE user_id = $1 AND id = $2 FOR UPDATE`, userID, accountID)); err != nil {
		return c, err
	}
	if c.Before, err = scanAccount(tx.QueryRowContext(ctx,
		`SELECT `+accountColumns+` FROM wallet_accounts a WHERE a.user_id = $1 AND a.id = $2`, userID, accountID)); err != nil {
		return c, err
	}
	var records int64
	if err := tx.QueryRowContext(ctx,
		`SELECT COALESCE(sum(CASE
			WHEN kind = 'income' THEN amount
			WHEN kind = 'expense' THEN -amount
			WHEN kind = 'transfer' AND account_id = $2 THEN -amount
			ELSE amount END), 0)
		 FROM transactions
		 WHERE user_id = $1 AND status = 'confirmed' AND (account_id = $2 OR to_account_id = $2)
		   AND ($3::timestamptz IS NULL OR occurred_at < $3)`,
		userID, accountID, nullTime(asOf)).Scan(&records); err != nil {
		return c, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE wallet_accounts SET initial_balance = $3 WHERE user_id = $1 AND id = $2`,
		userID, accountID, balance-records); err != nil {
		return c, err
	}
	if c.After, err = scanAccount(tx.QueryRowContext(ctx,
		`SELECT `+accountColumns+` FROM wallet_accounts a WHERE a.user_id = $1 AND a.id = $2`, userID, accountID)); err != nil {
		return c, err
	}
	if err := tx.QueryRowContext(ctx,
		`SELECT count(*) FROM transactions
		 WHERE user_id = $1 AND status = 'review' AND (account_id = $2 OR to_account_id = $2)`,
		userID, accountID).Scan(&c.ToReview); err != nil {
		return c, err
	}
	return c, tx.Commit()
}

func nullTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

// NotConfirmed is a record ConfirmRecords left as it was, and why.
type NotConfirmed struct {
	ID     int64  `json:"id"`
	Reason string `json:"reason"`
}

// ConfirmRecords confirms the user's records among ids that wait for review,
// as the app's review page does: each needs an amount and, unless it is a
// transfer, a category. A record flagged as a possible duplicate is
// confirmed only if keepDuplicates is set, and is then no longer flagged.
// Shops and cards nothing was learned for yet are remembered. The rest are
// returned with the reason.
func (s *Store) ConfirmRecords(ctx context.Context, userID int64, ids []int64, keepDuplicates bool) ([]Transaction, []NotConfirmed, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE user_id = $1 AND id = ANY($2::bigint[]) ORDER BY id FOR UPDATE`,
		userID, pq.Array(ids))
	if err != nil {
		return nil, nil, err
	}
	var found []Transaction
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			rows.Close()
			return nil, nil, err
		}
		found = append(found, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, nil, err
	}
	skipped := []NotConfirmed{}
	for _, id := range missingIDs(ids, found) {
		skipped = append(skipped, NotConfirmed{id, "no such record"})
	}
	var ready []int64
	for _, t := range found {
		reason := ""
		switch {
		case t.Status != "review":
			reason = "already confirmed"
		case t.Amount <= 0:
			reason = "no amount yet: the user must enter it in the app"
		case t.Kind != "transfer" && t.CategoryID == nil:
			reason = "no category: set one first"
		case t.DuplicateOfID != nil && !keepDuplicates:
			reason = fmt.Sprintf("possible duplicate of record %d: delete one, or confirm with keep_duplicates once the user says both are real", *t.DuplicateOfID)
		}
		if reason != "" {
			skipped = append(skipped, NotConfirmed{t.ID, reason})
			continue
		}
		ready = append(ready, t.ID)
	}
	confirmed := []Transaction{}
	if len(ready) > 0 {
		rows, err := tx.QueryContext(ctx,
			`UPDATE transactions SET status = 'confirmed', duplicate_of_id = NULL
			 WHERE user_id = $1 AND id = ANY($2::bigint[]) RETURNING `+txColumns, userID, pq.Array(ready))
		if err != nil {
			return nil, nil, err
		}
		for rows.Next() {
			t, err := scanTx(rows)
			if err != nil {
				rows.Close()
				return nil, nil, err
			}
			confirmed = append(confirmed, t)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, nil, err
		}
	}
	for _, t := range confirmed {
		if t.Source != "email" && t.Source != "notification" {
			continue
		}
		if err := learnIfNew(ctx, tx, userID, t); err != nil {
			return nil, nil, err
		}
	}
	slices.SortFunc(confirmed, func(a, b Transaction) int { return cmp.Compare(a.ID, b.ID) })
	return confirmed, skipped, tx.Commit()
}
