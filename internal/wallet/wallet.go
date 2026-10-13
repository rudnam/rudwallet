// Package wallet is the money tracker: accounts, categories, records
// (transactions), budgets, and the user's settings. Emailed payments arrive
// through internal/inbox, which creates records here with CreateFromEmail,
// and the phone's payment notifications through internal/notify, with
// CreateFromNotification.
package wallet

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

// Store scopes every query by user ID, so one user can never read or change
// another user's data.
type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

type Settings struct {
	Currency string `json:"currency"`
	// CurrencyExponent is how many decimal places amounts have: an amount of
	// 1250 is 1250 yen, or 12.50 dollars.
	CurrencyExponent int    `json:"currency_exponent"`
	AutoConfirm      bool   `json:"auto_confirm"`
	DefaultAccountID *int64 `json:"default_account_id"`
}

type Account struct {
	ID             int64  `json:"id"`
	Name           string `json:"name"`
	Kind           string `json:"kind"`
	InitialBalance int64  `json:"initial_balance"`
	// Balance counts confirmed records only.
	Balance  int64  `json:"balance"`
	Color    string `json:"color"`
	Archived bool   `json:"archived"`
}

type Category struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Kind  string `json:"kind"`
	Icon  string `json:"icon"`
	Color string `json:"color"`
}

type Transaction struct {
	ID            int64     `json:"id"`
	Kind          string    `json:"kind"`
	Amount        int64     `json:"amount"`
	AccountID     int64     `json:"account_id"`
	ToAccountID   *int64    `json:"to_account_id"`
	CategoryID    *int64    `json:"category_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	Payee         string    `json:"payee"`
	Note          string    `json:"note"`
	Status        string    `json:"status"`
	Source        string    `json:"source"`
	SourceLabel   string    `json:"source_label"`
	DuplicateOfID *int64    `json:"duplicate_of_id"`
	CreatedAt     time.Time `json:"created_at"`
}

type Budget struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	CategoryID *int64 `json:"category_id"`
	Amount     int64  `json:"amount"`
}

// Overview is everything the app needs on start-up.
type Overview struct {
	Settings    Settings   `json:"settings"`
	Accounts    []Account  `json:"accounts"`
	Categories  []Category `json:"categories"`
	ReviewCount int        `json:"review_count"`
}

var (
	accountKinds = []string{"cash", "bank", "credit_card", "e_money", "savings", "other"}

	defaultCategories = []Category{
		{Name: "Food & Drinks", Kind: "expense", Icon: "🍜", Color: "#d9534f"},
		{Name: "Groceries", Kind: "expense", Icon: "🛒", Color: "#e8833a"},
		{Name: "Shopping", Kind: "expense", Icon: "🛍️", Color: "#c9a227"},
		{Name: "Transport", Kind: "expense", Icon: "🚆", Color: "#3b82c4"},
		{Name: "Housing", Kind: "expense", Icon: "🏠", Color: "#8a5cc2"},
		{Name: "Bills & Utilities", Kind: "expense", Icon: "💡", Color: "#5b6ee1"},
		{Name: "Health", Kind: "expense", Icon: "💊", Color: "#2aa198"},
		{Name: "Entertainment", Kind: "expense", Icon: "🎬", Color: "#d6619b"},
		{Name: "Travel", Kind: "expense", Icon: "✈️", Color: "#1f9bd1"},
		{Name: "Other", Kind: "expense", Icon: "📦", Color: "#7a7f7c"},
		{Name: "Salary", Kind: "income", Icon: "💼", Color: "#2f9e5b"},
		{Name: "Other income", Kind: "income", Icon: "💰", Color: "#5aa469"},
	}
)

// setUp creates the user's settings, default categories and a Cash account
// the first time they open the wallet. It is safe to call on every request.
func (s *Store) setUp(ctx context.Context, userID int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `INSERT INTO user_settings (user_id) VALUES ($1) ON CONFLICT DO NOTHING`, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil // already set up
	}
	for _, c := range defaultCategories {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO categories (user_id, name, kind, icon, color) VALUES ($1, $2, $3, $4, $5)`,
			userID, c.Name, c.Kind, c.Icon, c.Color); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, 'Cash', 'cash')`, userID); err != nil {
		return err
	}
	return tx.Commit()
}

// Overview sets the user up if needed and returns their settings, accounts
// (with balances), categories, and how many records await review.
func (s *Store) Overview(ctx context.Context, userID int64) (Overview, error) {
	if err := s.setUp(ctx, userID); err != nil {
		return Overview{}, err
	}
	var o Overview
	var err error
	if o.Settings, err = s.Settings(ctx, userID); err != nil {
		return o, err
	}
	if o.Accounts, err = s.Accounts(ctx, userID); err != nil {
		return o, err
	}
	if o.Categories, err = s.Categories(ctx, userID); err != nil {
		return o, err
	}
	err = s.db.QueryRowContext(ctx,
		`SELECT count(*) FROM transactions WHERE user_id = $1 AND status = 'review'`, userID).Scan(&o.ReviewCount)
	return o, err
}

func (s *Store) Settings(ctx context.Context, userID int64) (Settings, error) {
	if err := s.setUp(ctx, userID); err != nil {
		return Settings{}, err
	}
	var st Settings
	var def sql.NullInt64
	err := s.db.QueryRowContext(ctx,
		`SELECT currency, auto_confirm, default_account_id FROM user_settings WHERE user_id = $1`, userID).
		Scan(&st.Currency, &st.AutoConfirm, &def)
	if def.Valid {
		st.DefaultAccountID = &def.Int64
	}
	st.CurrencyExponent = CurrencyExponent(st.Currency)
	return st, err
}

// ErrCurrencyLocked: the currency can't change once there are records,
// because their amounts are in it.
var ErrCurrencyLocked = errors.New("currency locked")

func (s *Store) UpdateSettings(ctx context.Context, userID int64, st Settings) error {
	if err := s.setUp(ctx, userID); err != nil {
		return err
	}
	if st.DefaultAccountID != nil {
		if _, err := s.account(ctx, userID, *st.DefaultAccountID); err != nil {
			return err
		}
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var current string
	if err := tx.QueryRowContext(ctx, `SELECT currency FROM user_settings WHERE user_id = $1 FOR UPDATE`, userID).Scan(&current); err != nil {
		return err
	}
	if current != st.Currency {
		var hasRecords bool
		if err := tx.QueryRowContext(ctx,
			`SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = $1)`, userID).Scan(&hasRecords); err != nil {
			return err
		}
		if hasRecords {
			return ErrCurrencyLocked
		}
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE user_settings SET currency = $2, auto_confirm = $3, default_account_id = $4 WHERE user_id = $1`,
		userID, st.Currency, st.AutoConfirm, st.DefaultAccountID); err != nil {
		return err
	}
	return tx.Commit()
}

// CurrencyExponent is the number of decimal places a currency uses.
func CurrencyExponent(currency string) int {
	switch strings.ToUpper(currency) {
	case "JPY", "KRW", "VND", "CLP", "ISK", "PYG", "UGX", "XAF", "XOF":
		return 0
	case "BHD", "KWD", "OMR", "JOD", "TND":
		return 3
	}
	return 2
}
