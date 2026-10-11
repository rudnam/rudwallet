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
