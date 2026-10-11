package wallet

import (
	"context"
	"database/sql"
	"errors"

	"github.com/francis/app-template/internal/db"
)

func (s *Store) Budgets(ctx context.Context, userID int64) ([]Budget, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, category_id, amount FROM budgets WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Budget{}
	for rows.Next() {
		var b Budget
		var cat sql.NullInt64
		if err := rows.Scan(&b.ID, &b.Name, &cat, &b.Amount); err != nil {
			return nil, err
		}
		b.CategoryID = ptr(cat)
		out = append(out, b)
	}
	return out, rows.Err()
}

func (s *Store) checkBudget(ctx context.Context, userID int64, b Budget) error {
	if b.CategoryID == nil {
		return nil
	}
	c, err := s.category(ctx, userID, *b.CategoryID)
	if errors.Is(err, db.ErrNotFound) {
		return invalid("no such category")
	}
	if err != nil {
		return err
	}
	if c.Kind != "expense" {
		return invalid("a budget needs an expense category")
	}
	return nil
}

func (s *Store) CreateBudget(ctx context.Context, userID int64, b Budget) (Budget, error) {
	if err := s.checkBudget(ctx, userID, b); err != nil {
		return b, err
	}
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO budgets (user_id, name, category_id, amount) VALUES ($1, $2, $3, $4) RETURNING id`,
		userID, b.Name, b.CategoryID, b.Amount).Scan(&b.ID)
	return b, err
}

func (s *Store) UpdateBudget(ctx context.Context, userID int64, b Budget) (Budget, error) {
	if err := s.checkBudget(ctx, userID, b); err != nil {
		return b, err
	}
	return b, rowsChanged(s.db.ExecContext(ctx,
		`UPDATE budgets SET name = $3, category_id = $4, amount = $5 WHERE user_id = $1 AND id = $2`,
		userID, b.ID, b.Name, b.CategoryID, b.Amount))
}

func (s *Store) DeleteBudget(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM budgets WHERE user_id = $1 AND id = $2`, userID, id))
}
