package server_test

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

func overview(t *testing.T, c *client) wallet.Overview {
	t.Helper()
	var o wallet.Overview
	if code := c.do("GET", "/api/wallet", nil, &o); code != 200 {
		t.Fatalf("GET /api/wallet: %d", code)
	}
	return o
}

func categoryID(t *testing.T, o wallet.Overview, name string) int64 {
	t.Helper()
	for _, c := range o.Categories {
		if c.Name == name {
			return c.ID
		}
	}
	t.Fatalf("no category %q", name)
	return 0
}

func TestWalletSetUpOnFirstVisit(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	if o.Settings.Currency != "JPY" || o.Settings.CurrencyExponent != 0 {
		t.Errorf("settings %+v", o.Settings)
	}
	if len(o.Accounts) != 1 || o.Accounts[0].Name != "Cash" || len(o.Categories) < 10 {
		t.Fatalf("defaults: %d accounts, %d categories", len(o.Accounts), len(o.Categories))
	}
	// Visiting again doesn't add them twice.
	if again := overview(t, c); len(again.Accounts) != 1 || len(again.Categories) != len(o.Categories) {
		t.Errorf("set up twice: %+v", again)
	}
}

func TestWalletSetUpInJapanese(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	c.headers["Accept-Language"] = "ja"
	o := overview(t, c)
	if len(o.Accounts) != 1 || o.Accounts[0].Name != "現金" {
		t.Fatalf("accounts %+v", o.Accounts)
	}
	categoryID(t, o, "食費")
	categoryID(t, o, "給与")
	// The names are the person's own from then on: English later changes nothing.
	c.headers["Accept-Language"] = "en"
	if again := overview(t, c); again.Accounts[0].Name != "現金" || len(again.Categories) != len(o.Categories) {
		t.Errorf("set up again in English: %+v", again)
	}
}

func TestRecordsAndBalances(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	food := categoryID(t, o, "Food & Drinks")
	salary := categoryID(t, o, "Salary")

	var bank wallet.Account
	if code := alice.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank", "initial_balance": 100000}, &bank); code != 201 {
		t.Fatalf("create account: %d", code)
	}
	if code := alice.do("POST", "/api/accounts", map[string]any{"name": "bank ", "kind": "bank"}, nil); code != 201 {
		t.Errorf("names differing in case are allowed: %d", code)
	}
	if code := alice.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, nil); code != 409 {
		t.Errorf("duplicate account name: %d, want 409", code)
	}

	now := time.Date(2026, 10, 11, 3, 0, 0, 0, time.UTC)
	records := []map[string]any{
		{"kind": "expense", "amount": 1200, "account_id": cash, "category_id": food, "occurred_at": now, "payee": "Ramen"},
		{"kind": "income", "amount": 300000, "account_id": bank.ID, "category_id": salary, "occurred_at": now},
		{"kind": "transfer", "amount": 20000, "account_id": bank.ID, "to_account_id": cash, "occurred_at": now},
	}
	for _, r := range records {
		if code := alice.do("POST", "/api/transactions", r, nil); code != 201 {
			t.Fatalf("create %v: %d", r["kind"], code)
		}
	}
	balances := map[int64]int64{}
	for _, a := range overview(t, alice).Accounts {
		balances[a.ID] = a.Balance
	}
	if balances[cash] != -1200+20000 || balances[bank.ID] != 100000+300000-20000 {
		t.Errorf("balances %v", balances)
	}

	// Filters.
	var list []wallet.Transaction
	alice.do("GET", fmt.Sprintf("/api/transactions?account_id=%d", cash), nil, &list)
	if len(list) != 2 {
		t.Errorf("cash records (incl. transfer in): %d", len(list))
	}
	alice.do("GET", "/api/transactions?q=ramen", nil, &list)
	if len(list) != 1 || list[0].Payee != "Ramen" {
		t.Errorf("search: %+v", list)
	}
	from := url.QueryEscape(now.Add(time.Hour).Format(time.RFC3339))
	alice.do("GET", "/api/transactions?from="+from, nil, &list)
	if len(list) != 0 {
		t.Errorf("from filter: %d", len(list))
	}

	// Bob sees none of it and can't touch it.
	bob.do("GET", "/api/transactions", nil, &list)
	if len(list) != 0 {
		t.Errorf("bob sees %d records", len(list))
	}
	alice.do("GET", "/api/transactions", nil, &list)
	path := fmt.Sprintf("/api/transactions/%d", list[0].ID)
	if code := bob.do("DELETE", path, nil, nil); code != 404 {
		t.Errorf("bob deletes alice's record: %d", code)
	}
	bobCash := overview(t, bob).Accounts[0].ID
	if code := bob.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1, "account_id": cash, "occurred_at": now}, nil); code != 400 {
		t.Errorf("bob records into alice's account: %d", code)
	}
	if code := bob.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1, "account_id": bobCash, "category_id": food, "occurred_at": now}, nil); code != 400 {
		t.Errorf("bob uses alice's category: %d", code)
	}
	if code := bob.do("PUT", fmt.Sprintf("/api/accounts/%d", cash), map[string]any{"name": "x", "kind": "cash"}, nil); code != 404 {
		t.Errorf("bob renames alice's account: %d", code)
	}

	if code := alice.do("DELETE", path, nil, nil); code != 204 {
		t.Errorf("delete: %d", code)
	}
}

func TestRecordValidation(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	cash := o.Accounts[0].ID
	salary := categoryID(t, o, "Salary")
	now := time.Now()
	for name, body := range map[string]map[string]any{
		"zero amount":             {"kind": "expense", "amount": 0, "account_id": cash, "occurred_at": now},
		"bad kind":                {"kind": "gift", "amount": 1, "account_id": cash, "occurred_at": now},
		"no date":                 {"kind": "expense", "amount": 1, "account_id": cash},
		"income category":         {"kind": "expense", "amount": 1, "account_id": cash, "category_id": salary, "occurred_at": now},
		"transfer to same":        {"kind": "transfer", "amount": 1, "account_id": cash, "to_account_id": cash, "occurred_at": now},
		"expense with to_account": {"kind": "expense", "amount": 1, "account_id": cash, "to_account_id": cash, "occurred_at": now},
	} {
		if code := c.do("POST", "/api/transactions", body, nil); code != 400 {
			t.Errorf("%s: %d, want 400", name, code)
		}
	}
}

func TestCurrencyLocksOnceRecordsExist(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	var st wallet.Settings
	if code := c.do("PUT", "/api/settings", map[string]any{"currency": "usd"}, &st); code != 200 || st.Currency != "USD" || st.CurrencyExponent != 2 {
		t.Fatalf("set currency: %d %+v", code, st)
	}
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1250, "account_id": o.Accounts[0].ID, "occurred_at": time.Now()}, nil)
	if code := c.do("PUT", "/api/settings", map[string]any{"currency": "EUR"}, nil); code != 409 {
		t.Errorf("change currency with records: %d, want 409", code)
	}
	if code := c.do("PUT", "/api/settings", map[string]any{"currency": "USD", "auto_confirm": true}, nil); code != 200 {
		t.Errorf("other settings still change: %d", code)
	}
}

func TestBudgets(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	food := categoryID(t, o, "Food & Drinks")
	var b wallet.Budget
	if code := c.do("POST", "/api/budgets", map[string]any{"name": "Eating out", "category_id": food, "amount": 30000}, &b); code != 201 {
		t.Fatalf("create: %d", code)
	}
	if code := c.do("POST", "/api/budgets", map[string]any{"name": "Everything", "amount": 150000}, nil); code != 201 {
		t.Fatalf("create all-expenses budget: %d", code)
	}
	if code := c.do("POST", "/api/budgets", map[string]any{"name": "Pay", "category_id": categoryID(t, o, "Salary"), "amount": 1}, nil); code != 400 {
		t.Errorf("income category budget: %d", code)
	}
	if code := c.do("PUT", fmt.Sprintf("/api/budgets/%d", b.ID), map[string]any{"name": "Food", "category_id": food, "amount": 40000}, nil); code != 200 {
		t.Errorf("update: %d", code)
	}
	var list []wallet.Budget
	c.do("GET", "/api/budgets", nil, &list)
	if len(list) != 2 || list[0].Amount != 40000 {
		t.Errorf("list %+v", list)
	}
	// Deleting the category removes its budget.
	c.do("DELETE", fmt.Sprintf("/api/categories/%d", food), nil, nil)
	c.do("GET", "/api/budgets", nil, &list)
	if len(list) != 1 {
		t.Errorf("after deleting category: %+v", list)
	}
}

func TestImportBudgetBakers(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	overview(t, c)
	csv := strings.Join([]string{
		"\ufeffaccount;category;currency;amount;ref_currency_amount;type;payment_type;payment_type_local;note;date;gps_latitude;gps_longitude;gps_accuracy_in_meters;warranty_in_month;transfer;payee;labels;envelope_id;custom_category",
		"Cash;Food & Drinks;JPY;-1200.00;-1200.00;Expenses;CASH;Cash;lunch;2026-10-01 12:30:00;;;;0;false;Ramen Shop;;1000;false",
		"PayPay Card;Coffee;JPY;-429.00;-429.00;Expenses;CREDIT_CARD;Card;;2026-10-02 07:45:00;;;;0;false;7-Eleven;;1000;false",
		"Bank;Salary;JPY;300000.00;300000.00;Income;TRANSFER;Transfer;;2026-10-25 09:00:00;;;;0;false;;;5000;false",
		"Bank;Transfer, withdraw;JPY;-20000.00;-20000.00;Expenses;TRANSFER;Transfer;;2026-10-26 10:00:00;;;;0;true;;;20001;false",
		"Cash;Transfer, withdraw;JPY;20000.00;20000.00;Income;TRANSFER;Transfer;;2026-10-26 10:00:00;;;;0;true;;;20001;false",
		"Cash;Travel;USD;-10.00;-1500.00;Expenses;CASH;Cash;;2026-10-03 10:00:00;;;;0;false;;;1000;false",
		"Cash;Food & Drinks;JPY;abc;;Expenses;CASH;Cash;;2026-10-04 10:00:00;;;;0;false;;;1000;false",
	}, "\n")
	var res wallet.ImportResult
	if code := c.send("/api/import/budgetbakers?tz_offset=-540", "text/csv", []byte(csv), nil, &res); code != 200 {
		t.Fatalf("import: %d", code)
	}
	if res.Imported != 5 || res.AccountsCreated != 2 || res.CategoriesCreated != 1 || len(res.Problems) != 1 {
		t.Errorf("result %+v (want 5 records incl. 1 transfer; PayPay Card and Bank; Coffee (Travel is a default); 1 bad row)", res)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions?q=ramen", nil, &list)
	if len(list) != 1 || list[0].Amount != 1200 || !list[0].OccurredAt.Equal(time.Date(2026, 10, 1, 3, 30, 0, 0, time.UTC)) {
		t.Errorf("ramen: %+v (12:30 JST = 03:30 UTC)", list)
	}
	c.do("GET", "/api/transactions", nil, &list)
	transfers, usdRow := 0, false
	for _, r := range list {
		if r.Kind == "transfer" {
			transfers++
		}
		usdRow = usdRow || r.Amount == 1500 // the USD row uses ref_currency_amount
	}
	if transfers != 1 || !usdRow {
		t.Errorf("transfers: %d, USD row in yen: %v", transfers, usdRow)
	}
	// Importing the same file again adds nothing.
	c.send("/api/import/budgetbakers", "text/csv", []byte(csv), nil, &res)
	if res.Imported != 0 || res.Duplicates != 5 {
		t.Errorf("re-import %+v", res)
	}
	if code := c.send("/api/import/budgetbakers", "text/plain", []byte(csv), nil, nil); code != 415 {
		t.Errorf("wrong content type: %d", code)
	}
	if code := c.send("/api/import/budgetbakers", "text/csv", []byte("a,b\n1,2"), nil, nil); code != 400 {
		t.Errorf("not a Wallet export: %d", code)
	}
}

// The preview suggests where each BudgetBakers category goes: the user's own
// of that name, the default it fits, or a new one; the import follows the
// user's choices.
func TestImportBudgetBakersChoices(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	o := overview(t, c)
	cash, food, salary := o.Accounts[0].ID, categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Salary")
	// As for a user who signed up before Phone & Internet was a default.
	if code := c.do("DELETE", fmt.Sprintf("/api/categories/%d", categoryID(t, o, "Phone & Internet")), nil, nil); code != 204 {
		t.Fatalf("delete category: %d", code)
	}
	var missing []wallet.Category
	c.do("GET", "/api/categories/missing", nil, &missing)
	if len(missing) != 1 || missing[0].Name != "Phone & Internet" || missing[0].Icon != "phone" {
		t.Errorf("missing defaults: %+v", missing)
	}

	csv := []byte(strings.Join([]string{
		"account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels",
		"Wallet;Restaurant, fast-food;JPY;1200;1200;Expense;Cash;;2026-10-01T03:00:00.000Z;false;Ramen Shop;",
		"Wallet;Restaurant, fast-food;JPY;800;800;Expense;Cash;;2026-10-02T03:00:00.000Z;false;Soba;",
		"Wallet;Phone, cell phone;JPY;5000;5000;Expense;Cash;;2026-10-03T03:00:00.000Z;false;Docomo;",
		"Wallet;Coffee beans;JPY;1500;1500;Expense;Cash;;2026-10-04T03:00:00.000Z;false;Roastery;",
		"Wallet;Bar, cafe;JPY;600;600;Expense;Cash;;2026-10-05T03:00:00.000Z;false;Doutor;",
		"Bank;Wage, invoices;JPY;300000;300000;Income;Transfer;;2026-10-25T00:00:00.000Z;false;;",
	}, "\n"))
	var p wallet.BudgetBakersPreview
	if code := c.send("/api/import/budgetbakers?preview=1", "text/csv", csv, nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	groups := map[string]wallet.ImportCategory{}
	for _, g := range p.Categories {
		groups[g.Name] = g
	}
	if p.Records != 6 || len(p.Categories) != 5 || len(p.Accounts) != 2 || p.Accounts[0].Name != "Wallet" || p.Accounts[0].NewKind != "cash" {
		t.Errorf("preview %+v", p)
	}
	if g := groups["Restaurant, fast-food"]; g.CategoryID == nil || *g.CategoryID != food || g.Count != 2 || g.Total != 2000 {
		t.Errorf("Restaurant, fast-food should go to Food & Drinks: %+v", g)
	}
	if g := groups["Phone, cell phone"]; g.CategoryID != nil || g.NewName != "Phone & Internet" {
		t.Errorf("Phone, cell phone should go to a new Phone & Internet: %+v", g)
	}
	if g := groups["Coffee beans"]; g.CategoryID != nil || g.NewName != "Coffee beans" {
		t.Errorf("an unknown category is suggested as itself: %+v", g)
	}
	if g := groups["Wage, invoices"]; g.CategoryID == nil || *g.CategoryID != salary || g.Kind != "income" {
		t.Errorf("Wage, invoices should go to Salary: %+v", g)
	}

	choose := func(cats []wallet.CategoryChoice) int {
		return c.do("POST", "/api/import/budgetbakers", map[string]any{
			"csv":        string(csv),
			"accounts":   []wallet.AccountChoice{{Name: "Wallet", AccountID: cash}},
			"categories": cats,
		}, nil)
	}
	otherFood := categoryID(t, overview(t, other), "Food & Drinks")
	if code := choose([]wallet.CategoryChoice{{Name: "Bar, cafe", Kind: "expense", CategoryID: otherFood}}); code != 400 {
		t.Errorf("another user's category: %d, want 400", code)
	}
	if code := choose([]wallet.CategoryChoice{{Name: "Wage, invoices", Kind: "income", CategoryID: food}}); code != 400 {
		t.Errorf("an expense category for income: %d, want 400", code)
	}

	var res wallet.ImportResult
	code := c.do("POST", "/api/import/budgetbakers", map[string]any{
		"csv":      string(csv),
		"accounts": []wallet.AccountChoice{{Name: "Wallet", AccountID: cash}},
		"categories": []wallet.CategoryChoice{
			{Name: "Restaurant, fast-food", Kind: "expense", CategoryID: food},
			{Name: "Phone, cell phone", Kind: "expense", NewName: "Phone & Internet"},
			{Name: "Coffee beans", Kind: "expense", NewName: "Coffee"},
			{Name: "Bar, cafe", Kind: "expense"},
			{Name: "Wage, invoices", Kind: "income", CategoryID: salary},
		},
	}, &res)
	if code != 200 || res.Imported != 6 || res.AccountsCreated != 1 || res.CategoriesCreated != 2 {
		t.Fatalf("import: %d %+v (want 6 records, only Bank created, Phone & Internet and Coffee)", code, res)
	}
	o = overview(t, c)
	byName := map[string]wallet.Category{}
	for _, cat := range o.Categories {
		byName[cat.Name] = cat
	}
	if byName["Phone & Internet"].Icon != "phone" || byName["Restaurant, fast-food"].ID != 0 {
		t.Errorf("categories after import: %+v", o.Categories)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	got := map[string]*int64{}
	for _, r := range list {
		got[r.Payee] = r.CategoryID
		if r.Kind == "expense" && r.AccountID != cash {
			t.Errorf("Wallet's records go to Cash: %+v", r)
		}
	}
	want := map[string]*int64{"Ramen Shop": &food, "Soba": &food, "Docomo": ptr(byName["Phone & Internet"].ID),
		"Roastery": ptr(byName["Coffee"].ID), "Doutor": nil}
	for payee, w := range want {
		if g := got[payee]; (g == nil) != (w == nil) || g != nil && *g != *w {
			t.Errorf("%s: category %v, want %v", payee, g, w)
		}
	}
}

func ptr(n int64) *int64 { return &n }

// An account in an import file can go to a new account under a name the
// user typed, or, when they already have one by that name, to it.
func TestImportNewAccountName(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	cash := overview(t, c).Accounts[0]
	csv := strings.Join([]string{
		"account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels",
		"Wallet;Food & Drinks;JPY;1200;1200;Expense;Cash;;2026-10-01T03:00:00.000Z;false;Ramen Shop;",
		"Bank;Salary;JPY;300000;300000;Income;Transfer;;2026-10-25T00:00:00.000Z;false;;",
		"PayPay Card;Food & Drinks;JPY;318;318;Expense;Cash;;2026-10-11T04:53:13.000Z;false;Soba;",
	}, "\n")
	importWith := func(accounts []wallet.AccountChoice, res *wallet.ImportResult) int {
		return c.do("POST", "/api/import/budgetbakers", map[string]any{"csv": csv, "accounts": accounts}, res)
	}
	if code := importWith([]wallet.AccountChoice{{Name: "Bank", NewName: strings.Repeat("x", 61)}}, &wallet.ImportResult{}); code != 400 {
		t.Errorf("a name over 60 characters: %d, want 400", code)
	}
	var res wallet.ImportResult
	code := importWith([]wallet.AccountChoice{
		{Name: "Bank", NewName: "  MUFG Savings  "},
		{Name: "Wallet", NewName: strings.ToUpper(cash.Name)}, // the user's Cash, ignoring case
		{Name: "PayPay Card"},                                 // as before: the account named after it
	}, &res)
	if code != 200 || res.Imported != 3 || res.AccountsCreated != 2 {
		t.Fatalf("import: %d %+v (want 3 records; MUFG Savings and PayPay Card created)", code, res)
	}
	byName := map[string]int64{}
	for _, a := range overview(t, c).Accounts {
		byName[a.Name] = a.ID
	}
	if len(byName) != 3 || byName["MUFG Savings"] == 0 || byName["PayPay Card"] == 0 {
		t.Errorf("accounts after import: %v (want Cash, MUFG Savings, PayPay Card)", byName)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	want := map[int64]int64{1200: cash.ID, 300000: byName["MUFG Savings"], 318: byName["PayPay Card"]}
	for _, r := range list {
		if r.AccountID != want[r.Amount] {
			t.Errorf("record of %d in account %d, want %d", r.Amount, r.AccountID, want[r.Amount])
		}
	}

	// The next export's accounts are suggested where they went this time.
	var p wallet.BudgetBakersPreview
	c.send("/api/import/budgetbakers?preview=1", "text/csv", []byte(csv), nil, &p)
	for _, a := range p.Accounts {
		if a.AccountID == nil || *a.AccountID != map[string]int64{"Wallet": cash.ID, "Bank": byName["MUFG Savings"], "PayPay Card": byName["PayPay Card"]}[a.Name] {
			t.Errorf("%s: suggested %v", a.Name, a.AccountID)
		}
	}
	// What the import remembers isn't a card rule for the user to manage.
	var rules wallet.Rules
	c.do("GET", "/api/rules", nil, &rules)
	if len(rules.Cards) != 0 {
		t.Errorf("card rules: %+v", rules.Cards)
	}
}

// A way of paying with a suggested account can still go to a new one, and
// two ways can share it.
func TestImportPayPayNewAccountName(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	overview(t, c)
	var card wallet.Account
	if code := c.do("POST", "/api/accounts", map[string]any{"name": "PayPay Gold", "kind": "credit_card"}, &card); code != 201 {
		t.Fatalf("create account: %d", code)
	}
	csv := strings.Join([]string{
		"取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号",
		"2026/10/01 08:15,\"1,200\",-,-,-,-,-,支払い,ローソン,PayPay残高,-,本人,00001",
		"2026/10/02 12:05,\"1,280\",-,-,-,-,-,支払い,お店,PayPayカード,一回払い,本人,00002",
	}, "\n")
	var res wallet.ImportResult
	code := c.do("POST", "/api/import/paypay", map[string]any{"csv": csv, "accounts": []wallet.AccountChoice{
		{Name: "PayPayカード", NewName: "Family PayPay"},
		{Name: "PayPay残高", NewName: "family paypay"},
	}}, &res)
	if code != 200 || res.Imported != 2 || res.AccountsCreated != 1 {
		t.Fatalf("import: %d %+v (want 2 records in one new account)", code, res)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	if len(list) != 2 || list[0].AccountID != list[1].AccountID || list[0].AccountID == card.ID {
		t.Errorf("records: %+v (want both in the new Family PayPay, not PayPay Gold)", list)
	}
	var p wallet.PayPayPreview
	c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p)
	for _, m := range p.Methods {
		if m.AccountID == nil || *m.AccountID != list[0].AccountID {
			t.Errorf("%s: the next import should suggest Family PayPay: %+v", m.Method, m)
		}
	}
}

// Newer Wallet exports leave amounts unsigned and give the direction in type.
func TestImportBudgetBakersUnsignedAmounts(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	overview(t, c)
	csv := strings.Join([]string{
		"account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels",
		"PayPay Card;Food & Drinks;JPY;318;318;Expense;Cash;;2026-10-11T04:53:13.000Z;false;;",
		"Bank;Salary;JPY;300000;300000;Income;Transfer;;2026-10-25T00:00:00.000Z;false;;",
		"Bank;Transfer, withdraw;JPY;20000;20000;Expense;Transfer;;2026-10-26T01:00:00.000Z;true;;",
		"Cash;Transfer, withdraw;JPY;20000;20000;Income;Transfer;;2026-10-26T01:00:00.000Z;true;;",
	}, "\n")
	var res wallet.ImportResult
	if code := c.send("/api/import/budgetbakers", "text/csv", []byte(csv), nil, &res); code != 200 || res.Imported != 3 {
		t.Fatalf("import: %d %+v", code, res)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	kinds := map[int64]string{}
	for _, r := range list {
		kinds[r.Amount] = r.Kind
	}
	if kinds[318] != "expense" || kinds[300000] != "income" || kinds[20000] != "transfer" {
		t.Errorf("kinds by amount: %v", kinds)
	}
}

func TestImportPayPay(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	cash := overview(t, c).Accounts[0].ID
	var card wallet.Account
	if code := c.do("POST", "/api/accounts", map[string]any{"name": "PayPay Gold", "kind": "credit_card"}, &card); code != 201 {
		t.Fatalf("create account: %d", code)
	}
	// Already recorded from its email: the import must not add it again.
	emailed := map[string]any{"kind": "expense", "amount": 1280, "account_id": card.ID,
		"occurred_at": time.Date(2026, 10, 2, 3, 4, 0, 0, time.UTC), "payee": "SEVEN-ELEVEN"}
	if code := c.do("POST", "/api/transactions", emailed, nil); code != 201 {
		t.Fatalf("create record: %d", code)
	}

	csv := strings.Join([]string{
		"取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号",
		"2026/10/01 08:15,\"1,200\",-,-,-,-,-,支払い,ローソン,PayPay残高,-,本人,00001",
		"2026/10/02 12:05,\"1,280\",-,-,-,-,-,支払い,お店,PayPayカード,一回払い,本人,00002",
		"2026/10/03 09:00,-,\"5,000\",-,-,-,-,チャージ,PayPay銀行,PayPay銀行,-,本人,00003",
		"2026/10/04 18:00,\"3,000\",-,20,USD,150.00,US,支払い,お店,PayPayカード,一回払い,本人,00004",
		"2026/10/05 10:00,-,15,-,-,-,-,ポイント、残高の獲得,PayPay,PayPayポイント,-,本人,lp-00005",
		"2026/10/06 10:00,abc,-,-,-,-,-,支払い,ローソン,PayPay残高,-,本人,00006",
	}, "\n")

	var p wallet.PayPayPreview
	if code := c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	suggested := map[string]wallet.PayPayMethod{}
	for _, m := range p.Methods {
		suggested[m.Method] = m
	}
	if p.Records != 5 || len(p.Problems) != 1 || len(p.Methods) != 4 {
		t.Errorf("preview %+v", p)
	}
	if m := suggested["PayPayカード"]; m.Count != 2 || m.AccountID == nil || *m.AccountID != card.ID {
		t.Errorf("PayPayカード should go to the PayPay card account: %+v", m)
	}
	if m := suggested["PayPay残高"]; m.Count != 2 || m.AccountID != nil || m.NewName != "PayPay" || m.NewKind != "e_money" {
		t.Errorf("PayPay残高 should get a new PayPay account (a payment and a charge): %+v", m)
	}

	if code := c.send("/api/import/paypay?account="+url.QueryEscape(strconv.FormatInt(cash, 10)+":PayPayポイント"), "text/csv", []byte(csv), nil, nil); code != 200 {
		t.Fatalf("import: %d", code)
	}
	var res wallet.ImportResult
	c.send("/api/import/paypay", "text/csv", []byte(csv), nil, &res)
	if res.Imported != 0 || res.Duplicates != 4 || res.Matched != 1 {
		t.Errorf("re-import %+v (want 4 already imported, 1 already recorded)", res)
	}

	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	byKey := map[string]wallet.Transaction{}
	for _, r := range list {
		byKey[fmt.Sprintf("%s %d", r.Kind, r.Amount)] = r
	}
	if len(list) != 5 {
		t.Errorf("want the emailed record and 4 imported, got %d: %+v", len(list), list)
	}
	lawson := byKey["expense 1200"]
	if lawson.Payee != "ローソン" || !lawson.OccurredAt.Equal(time.Date(2026, 9, 30, 23, 15, 0, 0, time.UTC)) {
		t.Errorf("payment: %+v (08:15 JST = 23:15 UTC the day before)", lawson)
	}
	charge := byKey["transfer 5000"]
	if charge.ToAccountID == nil || *charge.ToAccountID != lawson.AccountID || charge.AccountID == lawson.AccountID {
		t.Errorf("a charge moves money from the bank to the PayPay account: %+v", charge)
	}
	if abroad := byKey["expense 3000"]; abroad.AccountID != card.ID || abroad.Payee != "" || abroad.Note != "USD 20 at 150.00 (US)" {
		t.Errorf("card payment abroad: %+v", abroad)
	}
	if points := byKey["income 15"]; points.AccountID != cash {
		t.Errorf("points go to the chosen account: %+v", points)
	}

	// The choice is remembered.
	c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p)
	for _, m := range p.Methods {
		if m.AccountID == nil {
			t.Errorf("no account remembered for %s", m.Method)
		}
	}

	otherCash := overview(t, other).Accounts[0].ID
	if code := c.send("/api/import/paypay?account="+url.QueryEscape(strconv.FormatInt(otherCash, 10)+":PayPay残高"), "text/csv", []byte(csv), nil, nil); code != 404 {
		t.Errorf("another user's account: %d, want 404", code)
	}
	if code := c.send("/api/import/paypay", "text/csv", []byte("a,b\n1,2"), nil, nil); code != 400 {
		t.Errorf("not a PayPay export: %d", code)
	}
}

// The PayPay preview groups payments by shop and suggests a category for
// each; the categories chosen for shops are remembered.
func TestImportPayPayCategories(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	groceries, rewards := categoryID(t, o, "Groceries"), categoryID(t, o, "Rewards & Cashback")
	csv := strings.Join([]string{
		"取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号",
		"2026/10/01 08:15,\"1,200\",-,-,-,-,-,支払い,ローソン 渋谷店,PayPay残高,-,本人,c-01",
		"2026/10/02 08:15,300,-,-,-,-,-,支払い,ローソン 渋谷店,PayPay残高,-,本人,c-02",
		"2026/10/03 07:52,\"3,616\",-,22,USD,164.364,US,支払い,ANTHROPIC* CLAUDE SUB,PayPay残高,-,本人,c-03",
		"2026/10/04 12:00,980,-,-,-,-,-,支払い,JR東日本,PayPay残高,-,本人,c-04",
		"2026/10/05 10:00,-,15,-,-,-,-,ポイント、残高の獲得,PayPay,PayPayポイント,-,本人,c-05",
	}, "\n")
	var p wallet.PayPayPreview
	if code := c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	groups := map[string]wallet.ImportCategory{}
	for _, g := range p.Categories {
		groups[g.Name] = g
	}
	if len(p.Categories) != 4 {
		t.Errorf("want 4 groups: %+v", p.Categories)
	}
	if g := groups["ローソン 渋谷店"]; g.Count != 2 || g.Total != 1500 || g.CategoryID == nil || *g.CategoryID != categoryID(t, o, "Food & Drinks") {
		t.Errorf("a konbini is suggested Food & Drinks: %+v", g)
	}
	if g := groups["JR東日本"]; g.CategoryID == nil || *g.CategoryID != categoryID(t, o, "Transport") {
		t.Errorf("JR is suggested Transport: %+v", g)
	}
	if g := groups["ANTHROPIC* CLAUDE SUB"]; g.CategoryID != nil || g.NewName != "" {
		t.Errorf("an unknown shop gets no suggestion: %+v", g)
	}
	if g := groups["PayPay"]; g.Kind != "income" || g.CategoryID == nil || *g.CategoryID != rewards {
		t.Errorf("points earned are suggested Rewards & Cashback: %+v", g)
	}

	var res wallet.ImportResult
	code := c.do("POST", "/api/import/paypay", map[string]any{
		"csv": csv,
		"categories": []wallet.CategoryChoice{
			{Name: "ローソン 渋谷店", Kind: "expense", CategoryID: groceries},
			{Name: "ANTHROPIC* CLAUDE SUB", Kind: "expense", NewName: "Subscriptions"},
			{Name: "JR東日本", Kind: "expense"},
			{Name: "PayPay", Kind: "income", CategoryID: rewards},
		},
	}, &res)
	if code != 200 || res.Imported != 5 || res.CategoriesCreated != 1 {
		t.Fatalf("import: %d %+v", code, res)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	for _, r := range list {
		switch {
		case r.Payee == "ローソン 渋谷店" && (r.CategoryID == nil || *r.CategoryID != groceries),
			r.Payee == "JR東日本" && r.CategoryID != nil,
			r.Kind == "income" && (r.CategoryID == nil || *r.CategoryID != rewards),
			r.Payee == "ANTHROPIC* CLAUDE SUB" && r.CategoryID == nil:
			t.Errorf("record %+v", r)
		}
	}
	// Expense choices are remembered for the shop; income teaches nothing.
	var rules wallet.Rules
	c.do("GET", "/api/rules", nil, &rules)
	learned := map[string]int64{}
	for _, r := range rules.Payees {
		learned[r.Key] = r.CategoryID
	}
	if len(learned) != 2 || learned["ローソン 渋谷店"] != groceries || learned["paypay"] != 0 {
		t.Errorf("rules learned: %+v", rules.Payees)
	}
	c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p)
	for _, g := range p.Categories {
		if g.Name == "ローソン 渋谷店" && (!g.Learned || *g.CategoryID != groceries) {
			t.Errorf("the shop's category is remembered: %+v", g)
		}
	}
}

// With the PayPay app in English, the export's headers and values are in
// English, and a payment split over points and a card is one row.
func TestImportPayPayEnglish(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var gold wallet.Account
	if code := c.do("POST", "/api/accounts", map[string]any{"name": "Gold 8602", "kind": "credit_card"}, &gold); code != 201 {
		t.Fatalf("create account: %d", code)
	}

	csv := "\xEF\xBB\xBF" + strings.Join([]string{
		"Date & Time,Amount Outgoing (Yen),Amount Incoming (Yen),Amount Outgoing Overseas,Currency,Exchange Rate (Yen),Country Paid In,Transaction Type,Business Name,Method,Payment Option,User,Transaction ID",
		"2026/10/11 13:21:44,\"4,994\",-,-,-,-,-,Payment,IKEAオンライン,PayPay Card Gold VISA 8602,One-time Payment,Personal Use,PPCD_A_01",
		"2026/10/11 09:55:15,\"5,480\",-,-,-,-,-,Payment,Yahoo!ショッピング,\"PayPay Point (686yen), Credit VISA 8602 (4,794yen)\",-,-,0502",
		"2026/10/10 09:55:16,-,50,-,-,-,-,\"Points, Balance Earned\",Yahoo!ショッピング,PayPay Point,-,-,lp-03",
		"2026/10/03 07:52:47,\"3,616\",-,22,USD,164.36400000,US,Payment,ANTHROPIC* CLAUDE SUB,PayPay Card VISA 6385,One-time Payment,Personal Use,PPCD_A_04",
		"2026/10/02 08:00:00,\"1,000\",-,-,-,-,-,Payment,ローソン,Credit VISA 6385,-,-,0505",
		"2026/09/28 14:44:38,-,\"4,000\",-,-,-,-,Top-Up,PayPay,三菱ＵＦＪ銀行 *****44,-,-,0506",
		"2026/09/28 14:45:14,\"3,000\",-,-,-,-,-,Money Sent,サミー,PayPay Balance,-,-,0507",
		"2026/09/27 10:00:00,\"1,000\",-,-,-,-,-,Payment,ローソン,\"PayPay Point (1yen), Credit VISA 6385 (2yen)\",-,-,0508",
	}, "\r\n")

	var p wallet.PayPayPreview
	if code := c.send("/api/import/paypay?preview=1", "text/csv", []byte(csv), nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	suggested := map[string]wallet.PayPayMethod{}
	for _, m := range p.Methods {
		suggested[m.Method] = m
	}
	if p.Records != 8 || len(p.Problems) != 1 {
		t.Errorf("preview %+v (want 8 records, the split payment counted twice, and 1 that doesn't add up)", p)
	}
	for _, m := range []string{"PayPay Card Gold VISA 8602", "Credit VISA 8602"} {
		if s := suggested[m]; s.AccountID == nil || *s.AccountID != gold.ID {
			t.Errorf("%s should go to the card ending 8602: %+v", m, s)
		}
	}
	for _, m := range []string{"PayPay Card VISA 6385", "Credit VISA 6385"} {
		if s := suggested[m]; s.NewName != "PayPay Card 6385" || s.NewKind != "credit_card" {
			t.Errorf("%s should get a new account for the card ending 6385: %+v", m, s)
		}
	}
	if s := suggested["PayPayポイント"]; s.Count != 2 || s.NewName != "PayPay Points" {
		t.Errorf("PayPay Point is read as PayPayポイント: %+v", s)
	}
	if s := suggested["PayPay残高"]; s.Count != 2 || s.NewName != "PayPay" {
		t.Errorf("PayPay Balance is read as PayPay残高 (a payment and a top-up): %+v", s)
	}

	var res wallet.ImportResult
	if code := c.send("/api/import/paypay", "text/csv", []byte(csv), nil, &res); code != 200 {
		t.Fatalf("import: %d", code)
	}
	if res.Imported != 8 || res.AccountsCreated != 4 {
		t.Errorf("import %+v (want 8 imported; accounts PayPay, PayPay Points, PayPay Card 6385, and the bank)", res)
	}

	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	byKey := map[string]wallet.Transaction{}
	for _, r := range list {
		byKey[fmt.Sprintf("%s %d", r.Kind, r.Amount)] = r
	}
	if r := byKey["expense 4794"]; r.AccountID != gold.ID || r.Payee != "Yahoo!ショッピング" {
		t.Errorf("the card's share of a split payment: %+v", r)
	}
	if r, ok := byKey["expense 686"]; !ok || r.AccountID == gold.ID {
		t.Errorf("the points' share of a split payment: %+v", r)
	}
	if r := byKey["expense 3616"]; r.Note != "USD 22 at 164.364 (US)" || byKey["expense 1000"].AccountID != r.AccountID {
		t.Errorf("both ways of paying with the card ending 6385 go to one account: %+v, %+v", r, byKey["expense 1000"])
	}
	if r := byKey["transfer 4000"]; r.ToAccountID == nil || *r.ToAccountID != byKey["expense 3000"].AccountID {
		t.Errorf("a top-up moves money from the bank to the PayPay account: %+v", r)
	}
	if r := byKey["income 50"]; r.Note != "Points, Balance Earned" || r.AccountID != byKey["expense 686"].AccountID {
		t.Errorf("points earned: %+v", r)
	}
}

// A way of paying can be left out of a PayPay import, e.g. points for
// someone who doesn't track them, and that choice is remembered.
func TestImportPayPaySkipsAWayOfPaying(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var card, bank wallet.Account
	c.do("POST", "/api/accounts", map[string]any{"name": "Card 8602", "kind": "credit_card"}, &card)
	c.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)

	header := "Date & Time,Amount Outgoing (Yen),Amount Incoming (Yen),Amount Outgoing Overseas,Currency,Exchange Rate (Yen),Country Paid In,Transaction Type,Business Name,Method,Payment Option,User,Transaction ID"
	csv := strings.Join([]string{
		header,
		"2026/10/11 09:55:15,\"5,480\",-,-,-,-,-,Payment,Yahoo!ショッピング,\"PayPay Point (686yen), Credit VISA 8602 (4,794yen)\",-,-,s-01",
		"2026/10/10 09:55:16,-,50,-,-,-,-,\"Points, Balance Earned\",Yahoo!ショッピング,PayPay Point,-,-,s-02",
		"2026/10/09 10:00:00,\"300\",-,-,-,-,-,Payment,ローソン,PayPay Point,-,-,s-03",
	}, "\n")
	choose := func(csv string, accounts map[string]int64) wallet.ImportResult {
		t.Helper()
		var list []map[string]any
		for name, id := range accounts {
			list = append(list, map[string]any{"name": name, "account_id": id})
		}
		var res wallet.ImportResult
		if code := c.do("POST", "/api/import/paypay", map[string]any{"csv": csv, "accounts": list}, &res); code != 200 {
			t.Fatalf("import: %d", code)
		}
		return res
	}
	res := choose(csv, map[string]int64{"PayPayポイント": wallet.SkipAccount, "Credit VISA 8602": card.ID})
	if res.Imported != 1 || res.Skipped != 3 || res.AccountsCreated != 0 {
		t.Errorf("import %+v (want only the card's share, and points left out)", res)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	if len(list) != 1 || list[0].Amount != 4794 || list[0].AccountID != card.ID {
		t.Errorf("records %+v (want the card's 4,794)", list)
	}

	// Next time, points are suggested left out, and are, unless chosen.
	more := csv + "\n2026/10/12 10:00:00,\"200\",-,-,-,-,-,Payment,ローソン,PayPay Point,-,-,s-04"
	var p wallet.PayPayPreview
	c.send("/api/import/paypay?preview=1", "text/csv", []byte(more), nil, &p)
	for _, m := range p.Methods {
		if want := m.Method == "PayPayポイント"; m.Skip != want {
			t.Errorf("%s: skip %v, want %v", m.Method, m.Skip, want)
		}
	}
	if res := choose(more, nil); res.Imported != 0 || res.Skipped != 4 || res.Duplicates != 1 {
		t.Errorf("import again %+v (want points left out as remembered)", res)
	}
	if res := choose(more, map[string]int64{"PayPayポイント": 0}); res.Imported != 4 || res.AccountsCreated != 1 {
		t.Errorf("import with points %+v (want the 4 points records, in a new account)", res)
	}
	c.send("/api/import/paypay?preview=1", "text/csv", []byte(more), nil, &p)
	for _, m := range p.Methods {
		if m.Skip {
			t.Errorf("%s is still suggested left out", m.Method)
		}
	}

	// A charge into a balance that isn't imported is money out of the bank.
	charge := header + "\n2026/09/28 14:44:38,-,\"4,000\",-,-,-,-,Top-Up,PayPay,Bank,-,-,s-05"
	if res := choose(charge, map[string]int64{"PayPay残高": wallet.SkipAccount, "Bank": bank.ID}); res.Imported != 1 {
		t.Errorf("charge %+v", res)
	}
	c.do("GET", "/api/transactions?account_id="+strconv.FormatInt(bank.ID, 10), nil, &list)
	if len(list) != 1 || list[0].Kind != "expense" || list[0].Amount != 4000 {
		t.Errorf("a charge into a balance left out: %+v", list)
	}

	if code := c.do("POST", "/api/import/paypay", map[string]any{"csv": csv, "accounts": []map[string]any{{"name": "PayPayポイント", "account_id": -2}}}, nil); code != 400 {
		t.Errorf("account_id -2: %d, want 400", code)
	}
}

func TestWalletNeedsSignIn(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	for _, path := range []string{"/api/wallet", "/api/transactions", "/api/budgets", "/api/inbox"} {
		if code := c.do("GET", path, nil, nil); code != 401 {
			t.Errorf("GET %s: %d, want 401", path, code)
		}
	}
}

func TestCrossOriginWritesAreRefused(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	c.headers["Origin"] = "https://evil.example"
	if code := c.do("POST", "/api/accounts", map[string]string{"name": "x", "kind": "cash"}, nil); code != 403 {
		t.Errorf("cross-origin POST: %d, want 403", code)
	}
}

func TestStats(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	food := categoryID(t, o, "Food & Drinks")
	salary := categoryID(t, o, "Salary")
	var bank wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)

	// 23:30 UTC on Oct 1 is already Oct 2 in Tokyo.
	day := func(d, h int) time.Time { return time.Date(2026, 10, d, h, 30, 0, 0, time.UTC) }
	for _, r := range []map[string]any{
		{"kind": "expense", "amount": 1000, "account_id": cash, "category_id": food, "occurred_at": day(1, 3)},
		{"kind": "expense", "amount": 500, "account_id": cash, "category_id": food, "occurred_at": day(1, 23)},
		{"kind": "expense", "amount": 300, "account_id": bank.ID, "occurred_at": day(5, 3)},
		{"kind": "income", "amount": 9000, "account_id": bank.ID, "category_id": salary, "occurred_at": day(5, 3)},
		{"kind": "transfer", "amount": 7000, "account_id": bank.ID, "to_account_id": cash, "occurred_at": day(5, 3)},
		{"kind": "expense", "amount": 50000, "account_id": cash, "occurred_at": day(20, 3)}, // outside the period
	} {
		if code := alice.do("POST", "/api/transactions", r, nil); code != 201 {
			t.Fatalf("create %v: %d", r, code)
		}
	}

	path := func(extra string) string {
		return "/api/stats?from=" + url.QueryEscape(day(1, 0).Format(time.RFC3339)) +
			"&to=" + url.QueryEscape(day(10, 0).Format(time.RFC3339)) + extra
	}
	var st wallet.Stats
	if code := alice.do("GET", path("&tz=Asia/Tokyo"), nil, &st); code != 200 {
		t.Fatalf("GET stats: %d", code)
	}
	if st.Spent != 1800 || st.Income != 9000 {
		t.Errorf("spent %d, income %d; want 1800 and 9000 (no transfers)", st.Spent, st.Income)
	}
	if len(st.Categories) != 3 || st.Categories[0].Amount != 9000 || *st.Categories[1].CategoryID != food ||
		st.Categories[1].Amount != 1500 || st.Categories[1].Count != 2 || st.Categories[2].CategoryID != nil {
		t.Errorf("categories %+v", st.Categories)
	}
	days := fmt.Sprint(st.Days)
	if want := "[{2026-10-01 1000 0} {2026-10-02 500 0} {2026-10-05 300 9000}]"; days != want {
		t.Errorf("days in Tokyo %s, want %s", days, want)
	}
	alice.do("GET", path(""), nil, &st)
	if want := "[{2026-10-01 1500 0} {2026-10-05 300 9000}]"; fmt.Sprint(st.Days) != want {
		t.Errorf("days in UTC %s, want %s", fmt.Sprint(st.Days), want)
	}
	alice.do("GET", path(fmt.Sprintf("&account_id=%d", bank.ID)), nil, &st)
	if st.Spent != 300 || st.Income != 9000 {
		t.Errorf("bank only: spent %d, income %d", st.Spent, st.Income)
	}

	// Bob sees none of it.
	if code := bob.do("GET", path(""), nil, &st); code != 200 || st.Spent != 0 || len(st.Days) != 0 {
		t.Errorf("bob: %d %+v", code, st)
	}

	for _, bad := range []string{
		"/api/stats",
		path("&tz=Mars/Olympus"),
		path("&account_id=x"),
		"/api/stats?from=2026-10-10T00:00:00Z&to=2026-10-01T00:00:00Z",
		"/api/stats?from=1990-01-01T00:00:00Z&to=2026-10-01T00:00:00Z",
	} {
		if code := alice.do("GET", bad, nil, nil); code != 400 {
			t.Errorf("GET %s: %d, want 400", bad, code)
		}
	}
}

func TestBulkRecords(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	food := categoryID(t, o, "Food & Drinks")
	salary := categoryID(t, o, "Salary")
	var bank wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)
	now := time.Now()
	create := func(c *client, r map[string]any) int64 {
		t.Helper()
		var tx wallet.Transaction
		if code := c.do("POST", "/api/transactions", r, &tx); code != 201 {
			t.Fatalf("create %v: %d", r, code)
		}
		return tx.ID
	}
	lunch := create(alice, map[string]any{"kind": "expense", "amount": 900, "account_id": cash, "occurred_at": now})
	dinner := create(alice, map[string]any{"kind": "expense", "amount": 2000, "account_id": cash, "category_id": food, "occurred_at": now})
	pay := create(alice, map[string]any{"kind": "income", "amount": 9000, "account_id": cash, "occurred_at": now})
	toBank := create(alice, map[string]any{"kind": "transfer", "amount": 500, "account_id": cash, "to_account_id": bank.ID, "occurred_at": now})
	bobs := create(bob, map[string]any{"kind": "expense", "amount": 1, "account_id": overview(t, bob).Accounts[0].ID, "occurred_at": now})

	bulk := func(body map[string]any) (int, wallet.BulkResult) {
		var res wallet.BulkResult
		return alice.do("POST", "/api/transactions/bulk", body, &res), res
	}
	get := func(id int64) wallet.Transaction {
		var list []wallet.Transaction
		alice.do("GET", "/api/transactions", nil, &list)
		for _, tx := range list {
			if tx.ID == id {
				return tx
			}
		}
		return wallet.Transaction{}
	}
	all := []int64{lunch, dinner, pay, toBank, bobs}

	// An expense category fits only the expenses; Bob's record is out of reach.
	if code, res := bulk(map[string]any{"ids": all, "action": "categorize", "category_id": food}); code != 200 || res.Changed != 2 || res.Skipped != 3 {
		t.Errorf("categorize: %d %+v", code, res)
	}
	if c := get(lunch).CategoryID; c == nil || *c != food {
		t.Errorf("lunch category %v", c)
	}
	if code, res := bulk(map[string]any{"ids": []int64{pay}, "action": "categorize", "category_id": salary}); code != 200 || res.Changed != 1 {
		t.Errorf("categorize income: %d %+v", code, res)
	}
	// No category: everything but the transfer.
	if code, res := bulk(map[string]any{"ids": all, "action": "categorize", "category_id": nil}); code != 200 || res.Changed != 3 {
		t.Errorf("uncategorize: %d %+v", code, res)
	}
	if get(dinner).CategoryID != nil {
		t.Error("dinner still has a category")
	}

	// Moving to the bank leaves the transfer into the bank alone.
	if code, res := bulk(map[string]any{"ids": all, "action": "move", "account_id": bank.ID}); code != 200 || res.Changed != 3 {
		t.Errorf("move: %d %+v", code, res)
	}
	if get(lunch).AccountID != bank.ID || get(toBank).AccountID != cash {
		t.Errorf("after move: lunch in %d, transfer from %d", get(lunch).AccountID, get(toBank).AccountID)
	}

	for _, bad := range []map[string]any{
		{"ids": []int64{}, "action": "delete"},
		{"ids": all, "action": "explode"},
		{"ids": all, "action": "categorize", "category_id": categoryID(t, overview(t, bob), "Salary")},
		{"ids": all, "action": "move", "account_id": overview(t, bob).Accounts[0].ID},
	} {
		if code, _ := bulk(bad); code != 400 {
			t.Errorf("%v: %d, want 400", bad, code)
		}
	}

	// Duplicates count once.
	if code, res := bulk(map[string]any{"ids": append(all, lunch), "action": "delete"}); code != 200 || res.Changed != 4 || res.Skipped != 1 {
		t.Errorf("delete: %d %+v", code, res)
	}
	var list []wallet.Transaction
	if alice.do("GET", "/api/transactions", nil, &list); len(list) != 0 {
		t.Errorf("alice has %d records left", len(list))
	}
	if bob.do("GET", "/api/transactions", nil, &list); len(list) != 1 {
		t.Errorf("bob has %d records, want 1", len(list))
	}
}

func TestRestoreDeletedRecords(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	food := categoryID(t, o, "Food & Drinks")
	at := time.Date(2026, 10, 5, 12, 30, 15, 0, time.UTC)
	create := func(r map[string]any) wallet.Transaction {
		t.Helper()
		var tx wallet.Transaction
		if code := alice.do("POST", "/api/transactions", r, &tx); code != 201 {
			t.Fatalf("create %v: %d", r, code)
		}
		return tx
	}
	restore := func(c *client, ids ...int64) (int, wallet.RestoreResult) {
		var res wallet.RestoreResult
		return c.do("POST", "/api/transactions/restore", map[string]any{"ids": ids}, &res), res
	}
	list := func() []wallet.Transaction {
		var l []wallet.Transaction
		alice.do("GET", "/api/transactions", nil, &l)
		return l
	}
	lunch := create(map[string]any{"kind": "expense", "amount": 900, "account_id": cash, "category_id": food, "occurred_at": at, "payee": "Ramen", "note": "egg"})

	// A deleted record comes back as it was, with its id.
	if code := alice.do("DELETE", fmt.Sprintf("/api/transactions/%d", lunch.ID), nil, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	if l := list(); len(l) != 0 {
		t.Fatalf("still listed: %+v", l)
	}
	// Not by someone else.
	if code, _ := restore(bob, lunch.ID); code != 404 {
		t.Errorf("bob restores alice's record: %d, want 404", code)
	}
	if code, res := restore(alice, lunch.ID); code != 200 || res.Restored != 1 {
		t.Fatalf("restore: %d %+v", code, res)
	}
	l := list()
	if len(l) != 1 || l[0].ID != lunch.ID || l[0].Amount != 900 || l[0].Payee != "Ramen" || l[0].Note != "egg" ||
		l[0].CategoryID == nil || *l[0].CategoryID != food || !l[0].OccurredAt.Equal(at) || !l[0].CreatedAt.Equal(lunch.CreatedAt) {
		t.Errorf("restored %+v, want %+v", l, lunch)
	}
	if b := overview(t, alice).Accounts[0].Balance; b != -900 {
		t.Errorf("balance %d, want -900", b)
	}
	// Once only.
	if code, _ := restore(alice, lunch.ID); code != 404 {
		t.Errorf("second restore: %d, want 404", code)
	}

	// A bulk delete comes back in one go.
	dinner := create(map[string]any{"kind": "expense", "amount": 2000, "account_id": cash, "occurred_at": at})
	var bulk wallet.BulkResult
	alice.do("POST", "/api/transactions/bulk", map[string]any{"ids": []int64{lunch.ID, dinner.ID}, "action": "delete"}, &bulk)
	if bulk.Changed != 2 {
		t.Fatalf("bulk delete %+v", bulk)
	}
	if code, res := restore(alice, lunch.ID, dinner.ID, lunch.ID); code != 200 || res.Restored != 2 {
		t.Errorf("restore both: %d %+v", code, res)
	}
	if l := list(); len(l) != 2 {
		t.Errorf("after restoring both: %d records", len(l))
	}

	// A record deleted with its account is gone for good.
	var bank wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)
	rent := create(map[string]any{"kind": "expense", "amount": 50000, "account_id": bank.ID, "occurred_at": at})
	alice.do("DELETE", fmt.Sprintf("/api/transactions/%d", rent.ID), nil, nil)
	alice.do("DELETE", fmt.Sprintf("/api/accounts/%d", bank.ID), nil, nil)
	if code, _ := restore(alice, rent.ID); code != 404 {
		t.Errorf("restore into a deleted account: %d, want 404", code)
	}

	if code, _ := restore(alice); code != 400 {
		t.Errorf("no ids: %d, want 400", code)
	}
}

func TestAccountOrder(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	cash := overview(t, c).Accounts[0]
	var bank, card wallet.Account
	c.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)
	c.do("POST", "/api/accounts", map[string]any{"name": "Card", "kind": "credit_card"}, &card)
	names := func() []string {
		var out []string
		for _, a := range overview(t, c).Accounts {
			out = append(out, a.Name)
		}
		return out
	}
	if got := fmt.Sprint(names()); got != "[Cash Bank Card]" {
		t.Fatalf("before: %s, want the order they were added in", got)
	}

	if code := c.do("PUT", "/api/accounts/order", map[string]any{"ids": []int64{card.ID, cash.ID, bank.ID}}, nil); code != 204 {
		t.Fatalf("order: %d", code)
	}
	if got := fmt.Sprint(names()); got != "[Card Cash Bank]" {
		t.Errorf("after: %s", got)
	}
	// One listed: it goes first, and the rest keep their order behind it.
	c.do("PUT", "/api/accounts/order", map[string]any{"ids": []int64{bank.ID}}, nil)
	if got := fmt.Sprint(names()); got != "[Bank Card Cash]" {
		t.Errorf("one listed: %s", got)
	}
	// A new account goes last.
	c.do("POST", "/api/accounts", map[string]any{"name": "PayPay", "kind": "e_money"}, nil)
	if got := fmt.Sprint(names()); got != "[Bank Card Cash PayPay]" {
		t.Errorf("new account: %s", got)
	}

	if code := other.do("PUT", "/api/accounts/order", map[string]any{"ids": []int64{cash.ID}}, nil); code != 404 {
		t.Errorf("another user's account: %d, want 404", code)
	}
	if code := c.do("PUT", "/api/accounts/order", map[string]any{"ids": []int64{card.ID, 999999}}, nil); code != 404 {
		t.Errorf("an unknown account: %d, want 404", code)
	}
	if got := fmt.Sprint(names()); got != "[Bank Card Cash PayPay]" {
		t.Errorf("a refused order changed it: %s", got)
	}
	if code := c.do("PUT", "/api/accounts/order", map[string]any{"ids": []int64{}}, nil); code != 400 {
		t.Errorf("no ids: %d, want 400", code)
	}
}
