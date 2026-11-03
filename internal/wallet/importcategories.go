package wallet

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Imports let the user decide where records go before anything is
// recorded. The preview groups the file's records (by BudgetBakers
// category, or by PayPay shop) and suggests a category for each group; the
// import then takes the user's choice for each.

// ImportCategory is a group of records in an import file, and the category
// suggested for them.
type ImportCategory struct {
	// Name is the group as the file has it: a BudgetBakers category, or a
	// PayPay shop (or, without one, the record's note).
	Name  string `json:"name"`
	Kind  string `json:"kind"`
	Count int    `json:"count"`
	// Total is the sum of the records' amounts.
	Total int64 `json:"total"`
	// CategoryID is the suggested category of the user's. When it is nil,
	// NewName is a category to create, or, empty, no category.
	CategoryID *int64 `json:"category_id"`
	NewName    string `json:"new_name"`
	// Learned: the suggestion is what the user chose for this shop before.
	Learned bool `json:"learned"`
}

// CategoryChoice sends a group's records to a category: an existing one
// (CategoryID), one found by name or created (NewName), or, with neither,
// none.
type CategoryChoice struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	CategoryID int64  `json:"category_id"`
	NewName    string `json:"new_name"`
}

// AccountChoice sends the records of an account in the file (a BudgetBakers
// account, a PayPay way of paying) to one of the user's accounts; 0 takes
// the suggestion, or, with NewName, the account by that name (created when
// the user has none).
type AccountChoice struct {
	Name      string `json:"name"`
	AccountID int64  `json:"account_id"`
	NewName   string `json:"new_name,omitempty"`
}

// AccountPick is the account chosen for a group of an import's records: an
// existing one (ID), the account named NewName (created if missing), or,
// with neither, the suggestion.
type AccountPick struct {
	ID      int64
	NewName string
}

// accountPicks reads an import's account choices by name. min is the
// smallest account ID allowed (SkipAccount where records can be left out).
func accountPicks(choices []AccountChoice, min int64, into map[string]AccountPick) error {
	for _, a := range choices {
		if a.AccountID < min {
			return invalid("no such account")
		}
		p := AccountPick{ID: a.AccountID}
		if a.AccountID == 0 {
			n, err := accountName(a.NewName)
			if err != nil {
				return err
			}
			p.NewName = n
		}
		into[a.Name] = p
	}
	return nil
}

// accountName checks a new account's name; empty stays empty (none given).
func accountName(s string) (string, error) {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) > maxName {
		return "", invalid("a account name must be 1 to %d characters", maxName)
	}
	return s, nil
}

// accountNamed is the user's account with the name, ignoring case, created
// with the kind when there is none. created says it was.
func accountNamed(ctx context.Context, tx *sql.Tx, userID int64, name, kind string) (id int64, created bool, err error) {
	err = tx.QueryRowContext(ctx,
		`SELECT id FROM wallet_accounts WHERE user_id = $1 AND lower(name) = lower($2) ORDER BY id LIMIT 1`,
		userID, name).Scan(&id)
	if err == nil {
		return id, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, false, err
	}
	err = tx.QueryRowContext(ctx,
		`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, $3) RETURNING id`,
		userID, name, kind).Scan(&id)
	return id, err == nil, err
}

// ImportChoices is what the user picked in an import's preview. A group or
// account left out is imported as it was before previews had choices.
type ImportChoices struct {
	Accounts   []AccountChoice  `json:"accounts"`
	Categories []CategoryChoice `json:"categories"`
	// MatchBalance: set the bank's starting balance to match (MUFG only).
	MatchBalance bool `json:"match_balance"`
}

// MissingDefaults lists the default categories the user doesn't have (by
// name, ignoring case, in English or Japanese), named in the wallet's
// language, which they can add. Users who signed up before a default was
// added never got it.
func (s *Store) MissingDefaults(ctx context.Context, userID int64) ([]Category, error) {
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return nil, err
	}
	return missingDefaults(cats), nil
}

func missingDefaults(cats []Category) []Category {
	out := []Category{}
	lang := walletLanguage(cats)
	for _, d := range defaultCategories {
		if findCategory(cats, d.Kind, d.Name) == nil {
			d.Name = localName(d.Name, lang)
			out = append(out, d)
		}
	}
	return out
}

// findCategory returns the ID of the category of the kind with the name,
// ignoring case. A default category's name also finds it in the other
// language: "Food & Drinks" finds 食費.
func findCategory(cats []Category, kind, name string) *int64 {
	for _, c := range cats {
		if c.Kind == kind && (strings.EqualFold(c.Name, name) || sameDefault(c.Name, name)) {
			return &c.ID
		}
	}
	return nil
}

// suggestNamed suggests the user's category with the name, else a new one.
func suggestNamed(cats []Category, g *ImportCategory, name string) {
	if id := findCategory(cats, g.Kind, name); id != nil {
		g.CategoryID = id
	} else {
		g.NewName = localName(name, walletLanguage(cats))
	}
}

// importCategories finds and creates categories during an import.
type importCategories struct {
	ctx     context.Context
	tx      *sql.Tx
	userID  int64
	cats    []Category
	created int
}

func newImportCategories(ctx context.Context, tx *sql.Tx, s *Store, userID int64) (*importCategories, error) {
	cats, err := s.Categories(ctx, userID)
	return &importCategories{ctx: ctx, tx: tx, userID: userID, cats: cats}, err
}

func (ic *importCategories) kindOf(id int64) string {
	for _, c := range ic.cats {
		if c.ID == id {
			return c.Kind
		}
	}
	return ""
}

// named returns the user's category of the kind with the name, creating it
// (in a default's colours, if it is one) when missing.
func (ic *importCategories) named(kind, name string) (int64, error) {
	if id := findCategory(ic.cats, kind, name); id != nil {
		return *id, nil
	}
	c := Category{Name: name, Kind: kind, Color: "#66706b"}
	for _, d := range defaultCategories {
		if d.Kind == kind && (strings.EqualFold(d.Name, name) || sameDefault(d.Name, name)) {
			c = d
			c.Name = name // as the person chose it, e.g. in Japanese
		}
	}
	err := ic.tx.QueryRowContext(ic.ctx,
		`INSERT INTO categories (user_id, name, kind, icon, color) VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		ic.userID, c.Name, c.Kind, c.Icon, c.Color).Scan(&c.ID)
	if err != nil {
		return 0, err
	}
	ic.cats = append(ic.cats, c)
	ic.created++
	return c.ID, nil
}

// choose checks a choice and returns its category, or nil for none.
func (ic *importCategories) choose(ch CategoryChoice) (*int64, error) {
	if ch.Kind != "expense" && ch.Kind != "income" {
		return nil, invalid("a category's type must be expense or income")
	}
	if n := strings.TrimSpace(ch.NewName); n != "" {
		if len([]rune(n)) > maxName {
			return nil, invalid("a category name must be 1 to %d characters", maxName)
		}
		id, err := ic.named(ch.Kind, n)
		return &id, err
	}
	if ch.CategoryID == 0 {
		return nil, nil
	}
	switch ic.kindOf(ch.CategoryID) {
	case ch.Kind:
		return &ch.CategoryID, nil
	case "":
		return nil, invalid("no such category")
	}
	return nil, invalid("%q is not an %s category", ch.Name, ch.Kind)
}

// ---------- Suggestions ----------

// normName reduces a category name to lower-case words, so "Restaurant,
// fast-food" is "restaurant fast food".
func normName(s string) string {
	return strings.Join(strings.FieldsFunc(strings.ToLower(s), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	}), " ")
}

// budgetBakersCategories maps Wallet by BudgetBakers' standard categories
// (normName'd), top-level and sub-categories, to the default category they
// fit. Income has its own names; a name in both is looked up by kind.
var budgetBakersCategories = map[string]map[string]string{
	"expense": {
		"food drinks": "Food & Drinks", "food": "Food & Drinks", "restaurant fast food": "Food & Drinks",
		"restaurant": "Food & Drinks", "bar cafe": "Food & Drinks", "groceries": "Groceries",
		"alcohol tobacco": "Food & Drinks",

		"shopping": "Shopping", "clothes shoes": "Shopping", "jewels accessories": "Shopping",
		"kids": "Shopping", "home garden": "Shopping", "pets animals": "Shopping",
		"electronics accessories": "Shopping", "stationery tools": "Shopping",
		"health and beauty": "Personal care", "drug store chemist": "Health", "gifts joy": "Gifts & Donations",
		"free time": "Entertainment",

		"housing": "Housing", "rent": "Housing", "mortgage": "Housing", "maintenance repairs": "Housing",
		"property insurance": "Housing", "energy utilities": "Bills & Utilities", "services": "Bills & Utilities",

		"transportation": "Transport", "transport": "Transport", "public transport": "Transport", "taxi": "Transport",
		"long distance": "Travel", "business trips": "Travel",

		"vehicle": "Car", "fuel": "Car", "parking": "Car", "vehicle maintenance": "Car", "rentals": "Car",
		"vehicle insurance": "Car", "leasing": "Car",

		"life entertainment": "Entertainment", "entertainment": "Entertainment", "culture sport events": "Entertainment",
		"hobbies": "Entertainment", "books audio subscriptions": "Entertainment", "tv streaming": "Entertainment",
		"lottery gambling": "Entertainment", "software apps games": "Entertainment",
		"health care doctor": "Health", "health": "Health", "wellness beauty": "Personal care",
		"active sport fitness": "Sport & Fitness", "education development": "Education",
		"holiday trips hotels": "Travel", "travel": "Travel", "charity gifts": "Gifts & Donations",
		"life events": "Other",

		"communication pc": "Phone & Internet", "phone cell phone": "Phone & Internet", "internet": "Phone & Internet",
		"postal services": "Bills & Utilities",

		"financial expenses": "Taxes & Fees", "taxes": "Taxes & Fees", "insurances": "Taxes & Fees",
		"loan interest": "Taxes & Fees", "fines": "Taxes & Fees", "advisory": "Taxes & Fees",
		"charges fees": "Taxes & Fees", "child support": "Other",

		"others": "Other", "other": "Other", "missing": "Other",
	},
	"income": {
		"income": "Other income", "wage invoices": "Salary", "salary": "Salary",
		"interests dividends": "Interest & Dividends", "checks coupons": "Rewards & Cashback",
		"refunds tax purchase": "Refunds", "refunds": "Refunds",
		"sale": "Other income", "rental income": "Other income", "dues grants": "Other income",
		"lending renting": "Other income", "lottery gambling": "Other income", "child support": "Other income",
		"gifts": "Other income", "others": "Other income", "other": "Other income", "missing": "Other income",
	},
}

// suggestBudgetBakers suggests a category for a BudgetBakers category: the
// user's own of that name, else the default it fits (the user's, or new),
// else a new one of the same name.
func suggestBudgetBakers(cats []Category, g *ImportCategory) {
	if id := findCategory(cats, g.Kind, g.Name); id != nil {
		g.CategoryID = id
		return
	}
	if d, ok := budgetBakersCategories[g.Kind][normName(g.Name)]; ok {
		suggestNamed(cats, g, d)
		return
	}
	g.NewName = clip(g.Name, maxName)
}

// shopCategories guesses a default category from words in a shop's name,
// for shops common in Japan. Short Latin words must be whole words ("JR",
// not the jr in another word); the rest may be anywhere in the name.
// Earlier entries win, so Uber Eats is food before Uber is transport.
var shopCategories = []struct {
	category string
	words    []string
}{
	{"Food & Drinks", []string{"uber eats", "ubereats", "出前館", "wolt", "menu", "マクドナルド", "mcdonald", "スターバックス", "starbucks",
		"ドトール", "doutor", "タリーズ", "tully", "コメダ", "すき家", "吉野家", "松屋", "なか卯", "ガスト", "サイゼリヤ", "ケンタッキー", "kfc",
		"モスバーガー", "王将", "ココイチ", "coco壱", "丸亀製麺", "スシロー", "くら寿司", "はま寿司", "ラーメン", "らーめん", "寿司", "居酒屋",
		"レストラン", "restaurant", "cafe", "カフェ", "食堂", "ベーカリー", "bakery",
		"セブン-イレブン", "セブンイレブン", "seven-eleven", "7-eleven", "ファミリーマート", "ファミマ", "familymart", "ローソン", "lawson",
		"ミニストップ", "デイリーヤマザキ", "ニューデイズ", "newdays"}},
	{"Groceries", []string{"イオン", "aeon", "西友", "seiyu", "ライフ", "まいばすけっと", "業務スーパー", "オーケー", "イトーヨーカドー",
		"ヨークマート", "ヨークベニマル", "マルエツ", "サミット", "成城石井", "ベルク", "ロピア", "コープ", "スーパー", "supermarket",
		"コストコ", "costco"}},
	{"Health", []string{"薬局", "ドラッグ", "drug", "マツモトキヨシ", "マツキヨ", "ウエルシア", "スギ薬局", "ツルハ", "サンドラッグ",
		"ココカラ", "クリエイト", "クリニック", "clinic", "病院", "hospital", "歯科", "dental"}},
	{"Transport", []string{"jr", "鉄道", "電鉄", "メトロ", "metro", "東急", "小田急", "京王", "西武", "東武", "京急", "京成", "阪急", "阪神",
		"近鉄", "バス", "タクシー", "taxi", "go", "s.ride", "suica", "pasmo", "icoca", "uber", "ガソリン"}},
	{"Phone & Internet", []string{"ソフトバンク", "softbank", "ドコモ", "docomo", "au", "ワイモバイル", "ymobile", "楽天モバイル",
		"linemo", "ahamo", "povo", "uqモバイル", "ntt", "nuro", "ビッグローブ", "biglobe"}},
	{"Bills & Utilities", []string{"電力", "電気", "ガス", "水道", "東京電力", "関西電力", "東京ガス", "大阪ガス", "nhk", "郵便", "ヤマト運輸"}},
	{"Entertainment", []string{"netflix", "spotify", "youtube", "amazon prime", "prime video", "disney", "hulu", "u-next", "dazn",
		"abema", "steam", "nintendo", "任天堂", "playstation", "映画", "シネマ", "cinema", "toho", "カラオケ", "karaoke",
		"google play", "app store", "apple.com"}},
	{"Travel", []string{"ホテル", "hotel", "旅館", "airbnb", "agoda", "booking.com", "expedia", "楽天トラベル", "じゃらん",
		"ana", "jal", "peach", "jetstar", "ジェットスター", "スカイマーク"}},
	{"Sport & Fitness", []string{"ジム", "gym", "fitness", "フィットネス", "エニタイム", "anytime", "chocozap", "ちょこざっぷ", "ゴールドジム"}},
	{"Personal care", []string{"美容", "ヘアサロン", "サロン", "salon", "理容", "qb house", "hair"}},
	{"Education", []string{"書店", "紀伊國屋", "ブックオフ", "udemy", "塾", "スクール", "school", "英会話"}},
	{"Shopping", []string{"amazon", "アマゾン", "yahoo!ショッピング", "ヤフーショッピング", "楽天市場", "rakuten", "ユニクロ", "uniqlo",
		"gu", "ダイソー", "daiso", "セリア", "無印", "muji", "ニトリ", "nitori", "ikea", "ドン・キホーテ", "ドンキ", "ヨドバシ", "yodobashi",
		"ビックカメラ", "bic camera", "ヤマダ電機", "メルカリ", "mercari", "zozotown", "ロフト", "loft", "東急ハンズ", "ハンズ", "アップル",
		"apple store", "qoo10", "shein", "temu"}},
}

// incomeWords guesses an income category from what the record is.
var incomeWords = []struct {
	category string
	words    []string
}{
	{"Refunds", []string{"返金", "取り消し", "取消", "refund", "cancel"}},
	{"Rewards & Cashback", []string{"ポイント", "獲得", "キャッシュバック", "特典", "point", "cashback", "earned", "reward"}},
	{"Salary", []string{"給与", "給料", "賞与", "salary", "payroll"}},
	{"Interest & Dividends", []string{"利息", "配当", "interest", "dividend"}},
}

// guessCategory suggests a default category for a shop, or for income,
// by what the record says it is; "" if nothing fits.
func guessCategory(kind, shop, note string) string {
	if kind == "income" {
		text := PayeeKey(shop + " " + note)
		for _, c := range incomeWords {
			if hasWord(text, c.words) {
				return c.category
			}
		}
		return ""
	}
	text := PayeeKey(shop)
	for _, c := range shopCategories {
		if hasWord(text, c.words) {
			return c.category
		}
	}
	return ""
}

func hasWord(text string, words []string) bool {
	var tokens []string
	for _, w := range words {
		if len(w) > 3 || !isASCIIWord(w) {
			if strings.Contains(text, w) {
				return true
			}
			continue
		}
		if tokens == nil {
			tokens = strings.FieldsFunc(text, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) || r > unicode.MaxASCII })
		}
		if slices.Contains(tokens, w) {
			return true
		}
	}
	return false
}

func isASCIIWord(w string) bool {
	for _, r := range w {
		if r > unicode.MaxASCII {
			return false
		}
	}
	return true
}
