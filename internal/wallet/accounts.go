package wallet

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/rudnam/wallet-app/internal/db"
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

// accountOrder is the user's order of their accounts (see SetAccountOrder),
// for a query on wallet_accounts a. An account with no position (added by a
// release before positions) sorts by its id, as before.
const accountOrder = `COALESCE(a.position, a.id), a.id`

const accountColumns = `a.id, a.name, a.kind, a.initial_balance, ` + balanceSQL + `, a.color, a.archived`

func (s *Store) Accounts(ctx context.Context, userID int64) ([]Account, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+accountColumns+` FROM wallet_accounts a WHERE a.user_id = $1 ORDER BY a.archived, `+accountOrder, userID)
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
		`INSERT INTO wallet_accounts (user_id, name, kind, initial_balance, color, position)
		 VALUES ($1, $2, $3, $4, $5,
		   (SELECT COALESCE(max(COALESCE(a.position, a.id)), 0) + 1 FROM wallet_accounts a WHERE a.user_id = $1))
		 RETURNING id`,
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

// SetAccountOrder puts the user's accounts in the order of ids, the first
// first: in front on Wallet, and where payments go when no account is set.
// Accounts not listed keep their place after them. One that isn't the
// user's is db.ErrNotFound, and nothing changes.
func (s *Store) SetAccountOrder(ctx context.Context, userID int64, ids []int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Listed ones take 1..n; the rest move behind them, keeping their order.
	if _, err := tx.ExecContext(ctx,
		`UPDATE wallet_accounts a SET position = COALESCE(a.position, a.id) + $2 WHERE a.user_id = $1`,
		userID, int64(len(ids))+1); err != nil {
		return err
	}
	for i, id := range ids {
		res, err := tx.ExecContext(ctx,
			`UPDATE wallet_accounts SET position = $3 WHERE user_id = $1 AND id = $2`, userID, id, i+1)
		if err := rowsChanged(res, err); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// SetBalance changes an account's starting balance so that its balance (from
// its confirmed records) is balance now. No record is added, so every
// earlier balance of the account moves by the same amount; Reconcile records
// the difference instead.
func (s *Store) SetBalance(ctx context.Context, userID, id, balance int64) (Account, error) {
	if balance > maxAmount || balance < -maxAmount {
		return Account{}, invalid("that balance is too large")
	}
	if err := rowsChanged(s.db.ExecContext(ctx,
		`UPDATE wallet_accounts a SET initial_balance = $3 - (`+balanceSQL+` - a.initial_balance)
		 WHERE a.user_id = $1 AND a.id = $2`,
		userID, id, balance)); err != nil {
		return Account{}, err
	}
	return s.account(ctx, userID, id)
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

// Categories lists the user's categories, main ones and subcategories
// alike (a subcategory has a ParentID).
func (s *Store) Categories(ctx context.Context, userID int64) ([]Category, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+categoryColumns+` FROM categories WHERE user_id = $1 ORDER BY kind, id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Category{}
	for rows.Next() {
		var c Category
		if err := scanCategory(rows, &c); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

const categoryColumns = `id, name, kind, icon, color, parent_id`

func scanCategory(row interface{ Scan(...any) error }, c *Category) error {
	var parent sql.NullInt64
	err := row.Scan(&c.ID, &c.Name, &c.Kind, &c.Icon, &c.Color, &parent)
	c.ParentID = ptr(parent)
	return err
}

func (s *Store) category(ctx context.Context, userID, id int64) (Category, error) {
	var c Category
	err := scanCategory(s.db.QueryRowContext(ctx,
		`SELECT `+categoryColumns+` FROM categories WHERE user_id = $1 AND id = $2`, userID, id), &c)
	if errors.Is(err, sql.ErrNoRows) {
		return c, db.ErrNotFound
	}
	return c, err
}

// CreateCategory adds a main category, or a subcategory when c.ParentID is
// set (see checkParent).
func (s *Store) CreateCategory(ctx context.Context, userID int64, c Category) (Category, error) {
	tx, err := s.lockCategories(ctx, userID)
	if err != nil {
		return c, err
	}
	defer tx.Rollback()
	if err := checkParent(ctx, tx, userID, 0, c.Kind, c.ParentID); err != nil {
		return c, err
	}
	err = tx.QueryRowContext(ctx,
		`INSERT INTO categories (user_id, name, kind, icon, color, parent_id) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
		userID, c.Name, c.Kind, c.Icon, c.Color, c.ParentID).Scan(&c.ID)
	if db.IsUniqueViolation(err) {
		return c, ErrDuplicateName
	}
	if err != nil {
		return c, err
	}
	return c, tx.Commit()
}

// UpdateCategory changes a category's name, icon and colour (not its kind,
// which its records depend on), and with move its main category to
// c.ParentID (nil: it becomes a main one). Without move it stays where it
// is, for apps that don't know subcategories.
func (s *Store) UpdateCategory(ctx context.Context, userID int64, c Category, move bool) (Category, error) {
	tx, err := s.lockCategories(ctx, userID)
	if err != nil {
		return c, err
	}
	defer tx.Rollback()
	if move {
		var kind string
		err := tx.QueryRowContext(ctx, `SELECT kind FROM categories WHERE user_id = $1 AND id = $2`, userID, c.ID).Scan(&kind)
		if errors.Is(err, sql.ErrNoRows) {
			return c, db.ErrNotFound
		}
		if err != nil {
			return c, err
		}
		if err := checkParent(ctx, tx, userID, c.ID, kind, c.ParentID); err != nil {
			return c, err
		}
	}
	res, err := tx.ExecContext(ctx,
		`UPDATE categories SET name = $3, icon = $4, color = $5, parent_id = CASE WHEN $6 THEN $7 ELSE parent_id END
		 WHERE user_id = $1 AND id = $2`,
		userID, c.ID, c.Name, c.Icon, c.Color, move, c.ParentID)
	if db.IsUniqueViolation(err) {
		return c, ErrDuplicateName
	}
	if err := rowsChanged(res, err); err != nil {
		return c, err
	}
	if err := tx.Commit(); err != nil {
		return c, err
	}
	return s.category(ctx, userID, c.ID)
}

// lockCategories begins a transaction that holds the user's settings row,
// so two changes can't together nest categories two levels deep.
func (s *Store) lockCategories(ctx context.Context, userID int64) (*sql.Tx, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `SELECT 1 FROM user_settings WHERE user_id = $1 FOR UPDATE`, userID); err != nil {
		tx.Rollback()
		return nil, err
	}
	return tx, nil
}

// checkParent checks that category id (0 for a new one) of the kind can go
// under parent: one of the user's main categories of the same kind, and
// that id has no subcategories of its own. Categories are one level deep.
func checkParent(ctx context.Context, tx *sql.Tx, userID, id int64, kind string, parent *int64) error {
	if parent == nil {
		return nil
	}
	if *parent == id {
		return invalid("a category can't be under itself")
	}
	var pKind string
	var pParent sql.NullInt64
	err := tx.QueryRowContext(ctx, `SELECT kind, parent_id FROM categories WHERE user_id = $1 AND id = $2`, userID, *parent).
		Scan(&pKind, &pParent)
	if errors.Is(err, sql.ErrNoRows) {
		return invalid("no such category")
	}
	if err != nil {
		return err
	}
	if pKind != kind {
		return invalid("a subcategory must be the same type as its main category")
	}
	if pParent.Valid {
		return invalid("a subcategory can't have subcategories of its own")
	}
	if id == 0 {
		return nil
	}
	var has bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM categories WHERE user_id = $1 AND parent_id = $2)`, userID, id).Scan(&has); err != nil {
		return err
	}
	if has {
		return invalid("a category with subcategories can't go under another")
	}
	return nil
}

// DeleteCategory removes a category; its records become uncategorised, and
// its subcategories become main categories.
func (s *Store) DeleteCategory(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM categories WHERE user_id = $1 AND id = $2`, userID, id))
}

// Reconcile makes an account's balance what the user counted: the
// difference becomes one record at the time given, an expense when the
// account holds less than the app thought (e.g. cash spent and never
// recorded) and income when more. With no category given it gets the
// user's Other (or Other income) category, if they have one. No difference
// records nothing, and returns nil.
func (s *Store) Reconcile(ctx context.Context, userID, accountID, counted int64, categoryID *int64, at time.Time) (*Transaction, error) {
	a, err := s.account(ctx, userID, accountID)
	if err != nil {
		return nil, err
	}
	if counted < -maxAmount || counted > maxAmount {
		return nil, invalid("the balance is too large")
	}
	diff := a.Balance - counted
	if diff == 0 {
		return nil, nil
	}
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return nil, err
	}
	t := Transaction{Kind: "expense", Amount: diff, AccountID: accountID, OccurredAt: at, CategoryID: categoryID,
		Note: fmt.Sprintf("Untracked: counted %s, the app had %s", FormatMinor(counted, st.Currency), FormatMinor(a.Balance, st.Currency))}
	if diff < 0 {
		t.Kind, t.Amount = "income", -diff
	}
	if t.CategoryID == nil {
		cats, err := s.Categories(ctx, userID)
		if err != nil {
			return nil, err
		}
		name := map[string]string{"expense": "Other", "income": "Other income"}[t.Kind]
		t.CategoryID = findCategory(cats, t.Kind, name)
	}
	t, err = s.Create(ctx, userID, t)
	return &t, err
}
