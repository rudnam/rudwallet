package wallet

import (
	"context"
	"fmt"
)

// CreateMany adds confirmed records in one go, all or none, e.g. a demo's
// sample data. Each different combination of accounts, category and kind is
// checked once, as Create checks it.
func (s *Store) CreateMany(ctx context.Context, userID int64, list []Transaction) error {
	if err := s.setUp(ctx, userID); err != nil {
		return err
	}
	type refs struct {
		kind             string
		account, to, cat int64
	}
	checked := map[refs]bool{}
	for _, t := range list {
		k := refs{kind: t.Kind, account: t.AccountID}
		if t.ToAccountID != nil {
			k.to = *t.ToAccountID
		}
		if t.CategoryID != nil {
			k.cat = *t.CategoryID
		}
		if checked[k] {
			continue
		}
		if err := s.checkRefs(ctx, userID, t); err != nil {
			return err
		}
		checked[k] = true
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	insert, err := tx.PrepareContext(ctx,
		`INSERT INTO transactions (user_id, kind, amount, account_id, to_account_id, category_id, occurred_at, payee, note, status, source)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'confirmed', 'manual')`)
	if err != nil {
		return err
	}
	defer insert.Close()
	for i, t := range list {
		if _, err := insert.ExecContext(ctx, userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID,
			t.OccurredAt, t.Payee, t.Note); err != nil {
			return fmt.Errorf("record %d: %w", i, err)
		}
	}
	return tx.Commit()
}
