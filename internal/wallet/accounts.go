package wallet

import (
	"context"
	"database/sql"
	"errors"

	"github.com/francis/app-template/internal/db"
)

// balanceSQL is an account's balance from its confirmed records.
const balanceSQL = `a.initial_balance + COALESCE((
	SELECT sum(CASE
		WHEN t.kind = 'income' THEN t.amount
		WHEN t.kind = 'expense' THEN -t.amount
		WHEN t.kind = 'transfer' AND t.account_id = a.id THEN -t.amount
		ELSE t.amount END)
	FROM transactions t
	WHERE t.user_id = a.user_id AND t.status = 'confirmed' AND (t.account_id = a.id OR t.to_account_id = a.id)
), 0)`

func scanAccount(row interface{ Scan(...any) error }) (Account, error) {
	var a Account
	err := row.Scan(&a.ID, &a.Name, &a.Kind, &a.InitialBalance, &a.Balance, &a.Color, &a.Archived)
	return a, err
}

const accountColumns = `a.id, a.name, a.kind, a.initial_balance, ` + balanceSQL + `, a.color, a.archived`

func (s *Store) Accounts(ctx context.Context, userID int64) ([]Account, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+accountColumns+` FROM wallet_accounts a WHERE a.user_id = $1 ORDER BY a.archived, a.id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Account{}
	for rows.Next() {
		a, err := scanAccount(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) account(ctx context.Context, userID, id int64) (Account, error) {
	a, err := scanAccount(s.db.QueryRowContext(ctx,
		`SELECT `+accountColumns+` FROM wallet_accounts a WHERE a.user_id = $1 AND a.id = $2`, userID, id))
	if errors.Is(err, sql.ErrNoRows) {
		return a, db.ErrNotFound
	}
	return a, err
}

// ErrDuplicateName: the user already has one with that name.
var ErrDuplicateName = errors.New("duplicate name")

func (s *Store) CreateAccount(ctx context.Context, userID int64, a Account) (Account, error) {
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO wallet_accounts (user_id, name, kind, initial_balance, color) VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		userID, a.Name, a.Kind, a.InitialBalance, a.Color).Scan(&a.ID)
	if db.IsUniqueViolation(err) {
		return a, ErrDuplicateName
	}
	if err != nil {
		return a, err
	}
	return s.account(ctx, userID, a.ID)
}

func (s *Store) UpdateAccount(ctx context.Context, userID int64, a Account) (Account, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE wallet_accounts SET name = $3, kind = $4, initial_balance = $5, color = $6, archived = $7
		 WHERE user_id = $1 AND id = $2`,
		userID, a.ID, a.Name, a.Kind, a.InitialBalance, a.Color, a.Archived)
	if db.IsUniqueViolation(err) {
		return a, ErrDuplicateName
	}
	if err := rowsChanged(res, err); err != nil {
		return a, err
	}
	return s.account(ctx, userID, a.ID)
}

// DeleteAccount removes an account and every record in it.
func (s *Store) DeleteAccount(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM wallet_accounts WHERE user_id = $1 AND id = $2`, userID, id))
}

func rowsChanged(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return db.ErrNotFound
	}
	return nil
}

// ---------- Categories ----------

func (s *Store) Categories(ctx context.Context, userID int64) ([]Category, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, kind, icon, color FROM categories WHERE user_id = $1 ORDER BY kind, id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Category{}
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.Name, &c.Kind, &c.Icon, &c.Color); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) category(ctx context.Context, userID, id int64) (Category, error) {
	var c Category
	err := s.db.QueryRowContext(ctx,
		`SELECT id, name, kind, icon, color FROM categories WHERE user_id = $1 AND id = $2`, userID, id).
		Scan(&c.ID, &c.Name, &c.Kind, &c.Icon, &c.Color)
	if errors.Is(err, sql.ErrNoRows) {
		return c, db.ErrNotFound
	}
	return c, err
}

func (s *Store) CreateCategory(ctx context.Context, userID int64, c Category) (Category, error) {
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO categories (user_id, name, kind, icon, color) VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		userID, c.Name, c.Kind, c.Icon, c.Color).Scan(&c.ID)
	if db.IsUniqueViolation(err) {
		return c, ErrDuplicateName
	}
	return c, err
}

// UpdateCategory changes a category's name, icon and colour (not its kind,
// which its records depend on).
func (s *Store) UpdateCategory(ctx context.Context, userID int64, c Category) (Category, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE categories SET name = $3, icon = $4, color = $5 WHERE user_id = $1 AND id = $2`,
		userID, c.ID, c.Name, c.Icon, c.Color)
	if db.IsUniqueViolation(err) {
		return c, ErrDuplicateName
	}
	if err := rowsChanged(res, err); err != nil {
		return c, err
	}
	return s.category(ctx, userID, c.ID)
}

// DeleteCategory removes a category; its records become uncategorised.
func (s *Store) DeleteCategory(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM categories WHERE user_id = $1 AND id = $2`, userID, id))
}
