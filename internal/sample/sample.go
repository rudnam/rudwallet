// Package sample makes months of believable money for someone living in
// Japan: accounts, records, budgets and a few payments from the phone's
// notifications waiting for review. The demo (internal/demo) fills each visitor's wallet with it, and
// e2e/seed fills the preview account. Names are in English or Japanese.
//
// The same wallet comes out every time for the same day: the randomness has
// a fixed seed.
package sample

import (
	"math/rand/v2"
	"time"
)

// Wallet is what to add to a new wallet, which already has the default
// categories and a cash account (AccountCash).
type Wallet struct {
	Accounts []Account
	Records  []Record
	Budgets  []Budget
	// Review are payments read from the phone's notifications, still waiting for the person.
	Review []Payment
}

// Account keys. AccountCash is the account every wallet starts with.
const (
	AccountCash   = "cash"
	AccountBank   = "bank"
	AccountCard   = "card"
	AccountPayPay = "paypay"
)

// Account is a new account. Key is how records refer to it.
type Account struct {
	Key, Name, Kind string
	Balance         int64
	Color           string
}

// Record is a confirmed record. Category is a default category's English
// name (see wallet.DefaultCategoryNames), empty for transfers.
type Record struct {
	Kind      string
	Amount    int64
	Account   string
	ToAccount string
	Category  string
	At        time.Time
	Payee     string
}

// Budget is a monthly budget; an empty Category is everything.
type Budget struct {
	Name     string
	Category string
	Amount   int64
}

// Payment is a payment as the phone's notification reports it. Source is the card.
type Payment struct {
	Amount   int64
	Merchant string
	At       time.Time
	Source   string
}

// names are what the person would call things, in each language.
var names = map[string]map[string]string{
	"en": {
		"bank": "MUFG", "card": "PayPay Card", "paypay": "PayPay",
		"employer": "Employer", "rent": "Rent", "electric": "Chubu Electric", "gas": "Toho Gas",
		"commute": "JR Central commuter pass", "card payment": "Card payment", "charge": "PayPay top-up",
		"atm": "ATM", "mercari": "Mercari sale",
		"everything": "Everything", "eating out": "Eating out", "groceries": "Groceries",
	},
	"ja": {
		"bank": "三菱UFJ銀行", "card": "PayPayカード", "paypay": "PayPay",
		"employer": "勤務先", "rent": "家賃", "electric": "中部電力", "gas": "東邦ガス",
		"commute": "JR東海 定期券", "card payment": "カードの引き落とし", "charge": "PayPayチャージ",
		"atm": "ATM", "mercari": "メルカリ 売上",
		"everything": "全体", "eating out": "外食", "groceries": "食料品",
	},
}

// New makes months of history before now's month, and this month up to now,
// in now's time zone, named in lang ("en" or "ja"; anything else is "en").
func New(now time.Time, months int, lang string) Wallet {
	n, ok := names[lang]
	if !ok {
		n = names["en"]
	}
	var w Wallet
	w.Accounts = []Account{
		{Key: AccountBank, Name: n["bank"], Kind: "bank", Balance: 420000, Color: "#3b6ea8"},
		{Key: AccountCard, Name: n["card"], Kind: "credit_card", Balance: 0, Color: "#c8463d"},
		{Key: AccountPayPay, Name: n["paypay"], Kind: "e_money", Balance: 3000, Color: "#d4553f"},
	}

	r := rand.New(rand.NewPCG(7, 11))
	loc := now.Location()
	start := time.Date(now.Year(), now.Month()-time.Month(months), 1, 0, 0, 0, 0, loc)
	add := func(kind string, amount int64, account, category string, at time.Time, payee string, to ...string) {
		if at.After(now) {
			return
		}
		rec := Record{Kind: kind, Amount: amount, Account: account, Category: category, At: at, Payee: payee}
		if len(to) > 0 {
			rec.ToAccount = to[0]
		}
		w.Records = append(w.Records, rec)
	}
	between := func(lo, hi int64) int64 { return (lo + r.Int64N(hi-lo+1)) / 10 * 10 }
	pick := func(list ...string) string { return list[r.IntN(len(list))] }

	for m := start; !m.After(now); m = m.AddDate(0, 1, 0) {
		day := func(d, h, min int) time.Time { return time.Date(m.Year(), m.Month(), d, h, min, 0, 0, loc) }
		days := time.Date(m.Year(), m.Month()+1, 0, 0, 0, 0, 0, loc).Day()
		// Each month drifts a little, and the month before this one ate out a lot.
		eatOut := 1.0
		if prev := time.Date(now.Year(), now.Month()-1, 1, 0, 0, 0, 0, loc); m.Equal(prev) {
			eatOut = 1.7
		}

		// Regular money in and out.
		add("income", 285000, AccountBank, "Salary", day(25, 9, 0), n["employer"])
		add("expense", 72000, AccountBank, "Housing", day(27, 10, 0), n["rent"])
		add("expense", between(4800, 9200), AccountCard, "Bills & Utilities", day(14, 8, 0), n["electric"])
		add("expense", between(2600, 3800), AccountCard, "Bills & Utilities", day(18, 8, 0), n["gas"])
		add("expense", 2970, AccountCard, "Bills & Utilities", day(20, 8, 0), "ahamo")
		// At midnight on the 1st, so this month has spending from its first minute.
		add("expense", 11680, AccountCard, "Transport", day(1, 0, 0), n["commute"])
		add("transfer", between(86000, 104000), AccountBank, "", day(10, 9, 0), n["card payment"], AccountCard)
		add("transfer", 12000, AccountBank, "", day(26, 12, 0), n["charge"], AccountPayPay)
		add("transfer", 10000, AccountBank, "", day(5, 18, 30), n["atm"], AccountCash)
		if m.Month()%3 == 0 {
			add("income", between(3000, 12000), AccountBank, "Other income", day(15, 20, 0), n["mercari"])
		}

		for d := 1; d <= days; d++ {
			wd := day(d, 0, 0).Weekday()
			// Groceries twice a week.
			if wd == time.Wednesday || wd == time.Saturday {
				add("expense", between(1800, 6200), AccountCard, "Groceries", day(d, 17, r.IntN(60)), pick("アピタ知立店", "イオン刈谷店", "業務スーパー", "Aldi"))
			}
			// Coffee and lunch on workdays, now and then.
			if wd != time.Saturday && wd != time.Sunday && r.Float64() < 0.45*eatOut {
				paid := AccountPayPay
				if r.Float64() < 0.3 {
					paid = AccountCash
				}
				add("expense", between(380, 1200), paid, "Food & Drinks", day(d, 12, r.IntN(50)), pick("セブン－イレブン", "ファミリーマート", "すき家", "コメダ珈琲店", "スターバックス"))
			}
			// Dinner out at weekends.
			if (wd == time.Friday || wd == time.Saturday) && r.Float64() < 0.5*eatOut {
				add("expense", between(1400, 4800), AccountCard, "Food & Drinks", day(d, 19, r.IntN(60)), pick("大戸屋 知立店", "一蘭", "スシロー", "鳥貴族"))
			}
			if r.Float64() < 0.06 {
				add("expense", between(1200, 14000), AccountCard, "Shopping", day(d, 15, r.IntN(60)), pick("Amazon", "ユニクロ", "無印良品", "ニトリ"))
			}
			if r.Float64() < 0.04 {
				add("expense", between(1200, 4500), AccountCard, "Entertainment", day(d, 20, r.IntN(60)), pick("TOHOシネマズ", "Steam", "カラオケ まねきねこ"))
			}
			if r.Float64() < 0.03 {
				add("expense", between(600, 3500), AccountCash, "Health", day(d, 11, r.IntN(60)), pick("スギ薬局 大曽根店", "クリニック"))
			}
			if wd == time.Sunday && r.Float64() < 0.2 {
				add("expense", between(380, 1520), AccountPayPay, "Transport", day(d, 10, r.IntN(60)), "名鉄")
			}
		}
	}
	// One trip, two months ago.
	trip := time.Date(now.Year(), now.Month()-2, 12, 8, 0, 0, 0, loc)
	add("expense", 28400, AccountCard, "Travel", trip, "JR 新幹線 東京")
	add("expense", 18600, AccountCard, "Travel", trip.Add(10*time.Hour), "東横イン 東京駅")

	w.Budgets = []Budget{
		{Name: n["everything"], Amount: 200000},
		{Name: n["eating out"], Category: "Food & Drinks", Amount: 20000},
		{Name: n["groceries"], Category: "Groceries", Amount: 32000},
	}

	// Two PayPay Card payments from the last few hours, as PayPay's
	// notifications name the card.
	card := "PayPay Card"
	w.Review = []Payment{
		{Amount: 4994, Merchant: "IKEAオンライン", At: now.Add(-2 * time.Hour).Truncate(time.Minute), Source: card},
		{Amount: 1280, Merchant: "Amazon.co.jp", At: now.Add(-26 * time.Hour).Truncate(time.Minute), Source: card},
	}
	return w
}
