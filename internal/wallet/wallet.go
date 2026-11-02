// Package wallet is the money tracker: accounts, categories, records
// (transactions), budgets, and the user's settings. The phone's payment
// notifications arrive through internal/notify, which creates records here
// with CreateFromNotification.
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
	// Tags are the user's own labels, in the order they added them; never nil.
	Tags []string `json:"tags"`
	// ClientID is the app's own name for a new record (see Create); never sent back.
	ClientID string `json:"-"`
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

	// defaultCategories are every new user's categories. Icons are glyph keys
	// (web/src/components/Icon.tsx); older users' defaults store emoji, which
	// the app shows as the same glyphs. Users who signed up before one was
	// added can add it later (MissingDefaults).
	defaultCategories = []Category{
		{Name: "Food & Drinks", Kind: "expense", Icon: "food", Color: "#d9534f"},
		{Name: "Groceries", Kind: "expense", Icon: "grocery", Color: "#e8833a"},
		{Name: "Shopping", Kind: "expense", Icon: "shopping", Color: "#c9a227"},
		{Name: "Transport", Kind: "expense", Icon: "transit", Color: "#3b82c4"},
		{Name: "Car", Kind: "expense", Icon: "car", Color: "#5f7387"},
		{Name: "Housing", Kind: "expense", Icon: "home", Color: "#8a5cc2"},
		{Name: "Bills & Utilities", Kind: "expense", Icon: "bills", Color: "#5b6ee1"},
		{Name: "Phone & Internet", Kind: "expense", Icon: "phone", Color: "#2a8a8a"},
		{Name: "Health", Kind: "expense", Icon: "health", Color: "#2aa198"},
		{Name: "Personal care", Kind: "expense", Icon: "beauty", Color: "#a8508a"},
		{Name: "Sport & Fitness", Kind: "expense", Icon: "sport", Color: "#4f8a4c"},
		{Name: "Entertainment", Kind: "expense", Icon: "fun", Color: "#d6619b"},
		{Name: "Education", Kind: "expense", Icon: "school", Color: "#5b4a8a"},
		{Name: "Travel", Kind: "expense", Icon: "travel", Color: "#1f9bd1"},
		{Name: "Gifts & Donations", Kind: "expense", Icon: "gift", Color: "#c96442"},
		{Name: "Taxes & Fees", Kind: "expense", Icon: "bank", Color: "#8c7b5e"},
		{Name: "Other", Kind: "expense", Icon: "other", Color: "#7a7f7c"},
		{Name: "Salary", Kind: "income", Icon: "salary", Color: "#2f9e5b"},
		{Name: "Rewards & Cashback", Kind: "income", Icon: "card", Color: "#b08a3e"},
		{Name: "Refunds", Kind: "income", Icon: "shopping", Color: "#3b6ea8"},
		{Name: "Interest & Dividends", Kind: "income", Icon: "savings", Color: "#2a8a8a"},
		{Name: "Other income", Kind: "income", Icon: "money", Color: "#5aa469"},
	}

	// japaneseNames are the defaults' names in a wallet set up in Japanese,
	// by English name. Every default needs one (TestJapaneseNames).
	japaneseNames = map[string]string{
		"Food & Drinks": "食費", "Groceries": "食料品", "Shopping": "買い物", "Transport": "交通費", "Car": "車",
		"Housing": "住居費", "Bills & Utilities": "水道光熱費", "Phone & Internet": "通信費", "Health": "医療費",
		"Personal care": "美容・身だしなみ", "Sport & Fitness": "スポーツ・フィットネス", "Entertainment": "娯楽",
		"Education": "教育・教養", "Travel": "旅行", "Gifts & Donations": "贈り物・寄付", "Taxes & Fees": "税金・手数料",
		"Other": "その他", "Salary": "給与", "Rewards & Cashback": "ポイント・還元", "Refunds": "返金",
		"Interest & Dividends": "利息・配当", "Other income": "その他の収入",
	}
)

// localName is a default category's name in lang ("en" or "ja").
func localName(english, lang string) string {
	if j, ok := japaneseNames[english]; ok && lang == "ja" {
		return j
	}
	return english
}

// sameDefault: a and b name the same default category, in either language.
func sameDefault(a, b string) bool {
	for en, ja := range japaneseNames {
		if (strings.EqualFold(a, en) || a == ja) && (strings.EqualFold(b, en) || b == ja) {
			return true
		}
	}
	return false
}

// walletLanguage is "ja" for a wallet whose categories are named in
// Japanese (set up while the app was in Japanese), else "en".
func walletLanguage(cats []Category) string {
	for _, c := range cats {
		for _, ja := range japaneseNames {
			if c.Name == ja {
				return "ja"
			}
		}
	}
	return "en"
}

// DefaultCategoryNames are the names a new wallet's categories get in lang,
// in the same order as in English, so a category can be found by its
// English name: DefaultCategoryNames("ja")[i] is DefaultCategoryNames("en")[i].
func DefaultCategoryNames(lang string) []string {
	names := make([]string, len(defaultCategories))
	for i, c := range defaultCategories {
		names[i] = localName(c.Name, lang)
	}
	return names
}

// cashName is the name of the account every wallet starts with.
var cashName = map[string]string{"en": "Cash", "ja": "現金"}

// setUp creates the user's settings, default categories and a Cash account
// the first time they open the wallet. It is safe to call on every request.
func (s *Store) setUp(ctx context.Context, userID int64) error {
	return s.SetUp(ctx, userID, "en")
}

// SetUp is setUp naming the categories and the Cash account in lang ("en"
// or "ja"), for when the app opens the wallet for the first time, or a demo
// starts.
func (s *Store) SetUp(ctx context.Context, userID int64, lang string) error {
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
		c.Name = localName(c.Name, lang)
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO categories (user_id, name, kind, icon, color) VALUES ($1, $2, $3, $4, $5)`,
			userID, c.Name, c.Kind, c.Icon, c.Color); err != nil {
			return err
		}
	}
	cash, ok := cashName[lang]
	if !ok {
		cash = cashName["en"]
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, 'cash')`, userID, cash); err != nil {
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
