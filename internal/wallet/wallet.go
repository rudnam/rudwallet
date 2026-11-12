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
	// ParentID is the main category a subcategory is under; null for a main
	// category. Categories are one level deep, and a subcategory has its
	// main category's kind.
	ParentID *int64 `json:"parent_id"`
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

	// defaultCategories are every new user's categories, each main one
	// followed by its subcategories (defaultParents). Icons are glyph keys
	// (web/src/components/Icon.tsx); older users' defaults store emoji, which
	// the app shows as the same glyphs. A subcategory wears its main
	// category's colour. Users who signed up before one was added can add it
	// later (MissingDefaults).
	defaultCategories = []Category{
		{Name: "Food & Drinks", Kind: "expense", Icon: "food", Color: "#d9534f"},
		{Name: "Groceries", Kind: "expense", Icon: "grocery", Color: "#d9534f"},
		{Name: "Restaurants", Kind: "expense", Icon: "food", Color: "#d9534f"},
		{Name: "Cafés", Kind: "expense", Icon: "coffee", Color: "#d9534f"},
		{Name: "Delivery", Kind: "expense", Icon: "other", Color: "#d9534f"},
		{Name: "Household", Kind: "expense", Icon: "clean", Color: "#b8873a"},
		{Name: "Toiletries", Kind: "expense", Icon: "beauty", Color: "#b8873a"},
		{Name: "Cleaning & laundry", Kind: "expense", Icon: "clean", Color: "#b8873a"},
		{Name: "Kitchen supplies", Kind: "expense", Icon: "food", Color: "#b8873a"},
		{Name: "Shopping", Kind: "expense", Icon: "shopping", Color: "#c9a227"},
		{Name: "Clothing", Kind: "expense", Icon: "clothes", Color: "#c9a227"},
		{Name: "Electronics", Kind: "expense", Icon: "laptop", Color: "#c9a227"},
		{Name: "Furniture & home", Kind: "expense", Icon: "home", Color: "#c9a227"},
		{Name: "Housing", Kind: "expense", Icon: "home", Color: "#8a5cc2"},
		{Name: "Rent", Kind: "expense", Icon: "home", Color: "#8a5cc2"},
		{Name: "Management fees", Kind: "expense", Icon: "bank", Color: "#8a5cc2"},
		{Name: "Repairs & maintenance", Kind: "expense", Icon: "clean", Color: "#8a5cc2"},
		{Name: "Bills & Utilities", Kind: "expense", Icon: "bills", Color: "#6d6fc4"},
		{Name: "Electricity & gas", Kind: "expense", Icon: "bills", Color: "#6d6fc4"},
		{Name: "Water", Kind: "expense", Icon: "bills", Color: "#6d6fc4"},
		{Name: "Phone & Internet", Kind: "expense", Icon: "phone", Color: "#6d6fc4"},
		{Name: "Subscriptions", Kind: "expense", Icon: "repeat", Color: "#6d6fc4"},
		{Name: "Transport", Kind: "expense", Icon: "transit", Color: "#3b82c4"},
		{Name: "Public transport", Kind: "expense", Icon: "transit", Color: "#3b82c4"},
		{Name: "Taxi", Kind: "expense", Icon: "car", Color: "#3b82c4"},
		{Name: "Car", Kind: "expense", Icon: "car", Color: "#3b82c4"},
		{Name: "Health", Kind: "expense", Icon: "health", Color: "#2aa198"},
		{Name: "Medical", Kind: "expense", Icon: "health", Color: "#2aa198"},
		{Name: "Pharmacy", Kind: "expense", Icon: "shield", Color: "#2aa198"},
		{Name: "Personal care", Kind: "expense", Icon: "beauty", Color: "#2aa198"},
		{Name: "Sport & Fitness", Kind: "expense", Icon: "sport", Color: "#2aa198"},
		{Name: "Entertainment", Kind: "expense", Icon: "fun", Color: "#d6619b"},
		{Name: "Hobbies", Kind: "expense", Icon: "beauty", Color: "#d6619b"},
		{Name: "Events & tickets", Kind: "expense", Icon: "tag", Color: "#d6619b"},
		{Name: "Games & media", Kind: "expense", Icon: "fun", Color: "#d6619b"},
		{Name: "Travel", Kind: "expense", Icon: "travel", Color: "#1f9bd1"},
		{Name: "Flights", Kind: "expense", Icon: "travel", Color: "#1f9bd1"},
		{Name: "Hotels", Kind: "expense", Icon: "home", Color: "#1f9bd1"},
		{Name: "Tours & activities", Kind: "expense", Icon: "tag", Color: "#1f9bd1"},
		{Name: "Family", Kind: "expense", Icon: "kids", Color: "#d99a2b"},
		{Name: "Kids", Kind: "expense", Icon: "kids", Color: "#d99a2b"},
		{Name: "Pets", Kind: "expense", Icon: "pet", Color: "#d99a2b"},
		{Name: "Family support", Kind: "expense", Icon: "gift", Color: "#d99a2b"},
		{Name: "Education", Kind: "expense", Icon: "school", Color: "#5b4a8a"},
		{Name: "Courses & lessons", Kind: "expense", Icon: "school", Color: "#5b4a8a"},
		{Name: "Books & materials", Kind: "expense", Icon: "school", Color: "#5b4a8a"},
		{Name: "Exams & certificates", Kind: "expense", Icon: "tag", Color: "#5b4a8a"},
		{Name: "Insurance", Kind: "expense", Icon: "shield", Color: "#4a6f8a"},
		{Name: "Life & health insurance", Kind: "expense", Icon: "health", Color: "#4a6f8a"},
		{Name: "Home insurance", Kind: "expense", Icon: "home", Color: "#4a6f8a"},
		{Name: "Car insurance", Kind: "expense", Icon: "car", Color: "#4a6f8a"},
		{Name: "Gifts & Donations", Kind: "expense", Icon: "gift", Color: "#c96442"},
		{Name: "Gifts", Kind: "expense", Icon: "gift", Color: "#c96442"},
		{Name: "Ceremonies", Kind: "expense", Icon: "gift", Color: "#c96442"},
		{Name: "Donations", Kind: "expense", Icon: "gift", Color: "#c96442"},
		{Name: "Taxes & Fees", Kind: "expense", Icon: "bank", Color: "#8c7b5e"},
		{Name: "Taxes", Kind: "expense", Icon: "bank", Color: "#8c7b5e"},
		{Name: "Pension & social insurance", Kind: "expense", Icon: "shield", Color: "#8c7b5e"},
		{Name: "Bank & card fees", Kind: "expense", Icon: "card", Color: "#8c7b5e"},
		{Name: "Other", Kind: "expense", Icon: "other", Color: "#7a7f7c"},
		{Name: "Salary", Kind: "income", Icon: "salary", Color: "#2f9e5b"},
		{Name: "Bonus", Kind: "income", Icon: "salary", Color: "#2f9e5b"},
		{Name: "Allowances", Kind: "income", Icon: "salary", Color: "#2f9e5b"},
		{Name: "Reimbursements", Kind: "income", Icon: "money", Color: "#2f9e5b"},
		{Name: "Side income", Kind: "income", Icon: "laptop", Color: "#2b7d8c"},
		{Name: "Freelance", Kind: "income", Icon: "laptop", Color: "#2b7d8c"},
		{Name: "Sold items", Kind: "income", Icon: "tag", Color: "#2b7d8c"},
		{Name: "Gig work", Kind: "income", Icon: "money", Color: "#2b7d8c"},
		{Name: "Investments", Kind: "income", Icon: "savings", Color: "#2a8a8a"},
		{Name: "Interest", Kind: "income", Icon: "savings", Color: "#2a8a8a"},
		{Name: "Dividends", Kind: "income", Icon: "savings", Color: "#2a8a8a"},
		{Name: "Capital gains", Kind: "income", Icon: "savings", Color: "#2a8a8a"},
		{Name: "Refunds & rewards", Kind: "income", Icon: "card", Color: "#b08a3e"},
		{Name: "Rewards & Cashback", Kind: "income", Icon: "card", Color: "#b08a3e"},
		{Name: "Refunds", Kind: "income", Icon: "shopping", Color: "#b08a3e"},
		{Name: "Tax refunds", Kind: "income", Icon: "bank", Color: "#b08a3e"},
		{Name: "Gifts & benefits", Kind: "income", Icon: "gift", Color: "#c96490"},
		{Name: "Gifts received", Kind: "income", Icon: "gift", Color: "#c96490"},
		{Name: "Government benefits", Kind: "income", Icon: "bank", Color: "#c96490"},
		{Name: "From family", Kind: "income", Icon: "kids", Color: "#c96490"},
		{Name: "Other income", Kind: "income", Icon: "money", Color: "#5aa469"},
	}

	// defaultParents are the default subcategories' main categories, by
	// English name. A main category comes before its subcategories above.
	// Every main category has a few; Other and Other income, the catch-alls,
	// have none.
	defaultParents = map[string]string{
		"Groceries": "Food & Drinks", "Restaurants": "Food & Drinks", "Cafés": "Food & Drinks", "Delivery": "Food & Drinks",
		"Toiletries": "Household", "Cleaning & laundry": "Household", "Kitchen supplies": "Household",
		"Clothing": "Shopping", "Electronics": "Shopping", "Furniture & home": "Shopping",
		"Rent": "Housing", "Management fees": "Housing", "Repairs & maintenance": "Housing",
		"Electricity & gas": "Bills & Utilities", "Water": "Bills & Utilities", "Phone & Internet": "Bills & Utilities",
		"Subscriptions":    "Bills & Utilities",
		"Public transport": "Transport", "Taxi": "Transport", "Car": "Transport",
		"Medical": "Health", "Pharmacy": "Health", "Personal care": "Health", "Sport & Fitness": "Health",
		"Hobbies": "Entertainment", "Events & tickets": "Entertainment", "Games & media": "Entertainment",
		"Flights": "Travel", "Hotels": "Travel", "Tours & activities": "Travel",
		"Kids": "Family", "Pets": "Family", "Family support": "Family",
		"Courses & lessons": "Education", "Books & materials": "Education", "Exams & certificates": "Education",
		"Life & health insurance": "Insurance", "Home insurance": "Insurance", "Car insurance": "Insurance",
		"Gifts": "Gifts & Donations", "Ceremonies": "Gifts & Donations", "Donations": "Gifts & Donations",
		"Taxes": "Taxes & Fees", "Pension & social insurance": "Taxes & Fees", "Bank & card fees": "Taxes & Fees",
		"Bonus": "Salary", "Allowances": "Salary", "Reimbursements": "Salary",
		"Freelance": "Side income", "Sold items": "Side income", "Gig work": "Side income",
		"Interest": "Investments", "Dividends": "Investments", "Capital gains": "Investments",
		"Rewards & Cashback": "Refunds & rewards", "Refunds": "Refunds & rewards", "Tax refunds": "Refunds & rewards",
		"Gifts received": "Gifts & benefits", "Government benefits": "Gifts & benefits", "From family": "Gifts & benefits",
	}

	// japaneseNames are the defaults' names in a wallet set up in Japanese,
	// by English name. Every default needs one (TestJapaneseNames).
	japaneseNames = map[string]string{
		"Food & Drinks": "食費", "Groceries": "食料品", "Restaurants": "外食", "Cafés": "カフェ", "Delivery": "デリバリー",
		"Household": "日用品", "Toiletries": "洗面・衛生用品", "Cleaning & laundry": "掃除・洗濯用品", "Kitchen supplies": "台所用品",
		"Shopping": "買い物", "Clothing": "衣服", "Electronics": "家電・ガジェット", "Furniture & home": "家具・インテリア",
		"Housing": "住居費", "Rent": "家賃", "Management fees": "管理費・共益費", "Repairs & maintenance": "修繕・メンテナンス",
		"Bills & Utilities": "水道光熱費", "Electricity & gas": "電気・ガス", "Water": "水道", "Phone & Internet": "通信費",
		"Subscriptions": "サブスク",
		"Transport":     "交通費", "Public transport": "電車・バス", "Taxi": "タクシー", "Car": "車",
		"Health": "医療費", "Medical": "病院", "Pharmacy": "薬・ドラッグストア", "Personal care": "美容・身だしなみ",
		"Sport & Fitness": "スポーツ・フィットネス",
		"Entertainment":   "娯楽", "Hobbies": "趣味", "Events & tickets": "イベント・チケット", "Games & media": "ゲーム・映像・音楽",
		"Travel": "旅行", "Flights": "航空券", "Hotels": "宿泊", "Tours & activities": "観光・アクティビティ",
		"Family": "家族", "Kids": "子ども", "Pets": "ペット", "Family support": "仕送り",
		"Education": "教育・教養", "Courses & lessons": "講座・習い事", "Books & materials": "書籍・教材", "Exams & certificates": "資格・検定",
		"Insurance": "保険", "Life & health insurance": "生命・医療保険", "Home insurance": "火災・地震保険", "Car insurance": "自動車保険",
		"Gifts & Donations": "贈り物・寄付", "Gifts": "プレゼント", "Ceremonies": "冠婚葬祭", "Donations": "寄付",
		"Taxes & Fees": "税金・手数料", "Taxes": "税金", "Pension & social insurance": "年金・社会保険", "Bank & card fees": "振込・カード手数料",
		"Other":  "その他",
		"Salary": "給与", "Bonus": "賞与", "Allowances": "手当", "Reimbursements": "立替精算",
		"Side income": "副業", "Freelance": "業務委託・フリーランス", "Sold items": "売却・フリマ", "Gig work": "スキマバイト",
		"Investments": "投資収入", "Interest": "利息", "Dividends": "配当", "Capital gains": "売却益",
		"Refunds & rewards": "返金・還元", "Rewards & Cashback": "ポイント・還元", "Refunds": "返金", "Tax refunds": "還付金",
		"Gifts & benefits": "贈与・給付", "Gifts received": "お祝い・贈与", "Government benefits": "給付金", "From family": "家族から",
		"Other income": "その他の収入",
	}

	// formerNames are names a default had before, in English or Japanese,
	// by its name now: a wallet with one has that default, under the old name.
	formerNames = map[string]string{
		"Interest & Dividends": "Investments", "利息・配当": "Investments",
	}
)

// localName is a default category's name in lang ("en" or "ja").
func localName(english, lang string) string {
	if j, ok := japaneseNames[english]; ok && lang == "ja" {
		return j
	}
	return english
}

// defaultName is the English name of the default category name is, in
// either language or by a former name (ignoring case); "" if none.
func defaultName(name string) string {
	for en, ja := range japaneseNames {
		if strings.EqualFold(name, en) || name == ja {
			return en
		}
	}
	for old, en := range formerNames {
		if strings.EqualFold(name, old) {
			return en
		}
	}
	return ""
}

// sameDefault: a and b name the same default category, in either language
// or by a former name.
func sameDefault(a, b string) bool {
	d := defaultName(a)
	return d != "" && d == defaultName(b)
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
	ids := map[string]int64{} // by English name
	for _, c := range defaultCategories {
		var parent *int64
		if p, ok := defaultParents[c.Name]; ok {
			id := ids[p]
			parent = &id
		}
		var id int64
		if err := tx.QueryRowContext(ctx,
			`INSERT INTO categories (user_id, name, kind, icon, color, parent_id) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
			userID, localName(c.Name, lang), c.Kind, c.Icon, c.Color, parent).Scan(&id); err != nil {
			return err
		}
		ids[c.Name] = id
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
