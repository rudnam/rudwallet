package server_test

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"golang.org/x/text/encoding/japanese"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// mufgCSV is an MUFG statement, from a balance of ¥7 to ¥134,409.
var mufgCSV = strings.Join([]string{
	`日付,摘要,摘要内容,支払い金額,預かり金額,差引残高,メモ,未資金化区分,入払区分`,
	`"2026/10/13","振込１","ペイペイ","","20,000","20,007","","",""`,
	`"2026/10/14","口座振替３","ＲＴＫ　ペイペイ","3,000","","17,007","","",""`,
	`"2026/10/14","カ－ド","","5,000","","12,007","","",""`,
	`"2026/10/14","カード","","5,000","","7,007","","",""`,
	`"2026/10/15","カードＣ１","","1,000","","6,007","","",""`,
	`"2026/10/15","手数料","ＡＴＭ","110","","5,897","","",""`,
	`"2026/10/25","振込１","シービーエステクノ（カ　","","250,000","255,897","","",""`,
	`"2026/10/27","口座振替４","ＰＡＹＰＡＹカード","100,000","","155,897","","",""`,
	`"2026/10/27","口座振替２","ミツイスミトモカ－ド","20,000","","135,897","","",""`,
	`"2026/10/28","Ｄ現金還元","","","12","135,909","","",""`,
	`"2026/10/30","振込２","ヤマダ　タロウ","2,500","","133,409","","",""`,
	`"2026/11/1","振込１","スズキ　ハナコ","","1,000","134,409","","",""`,
}, "\r\n") + "\r\n"

const employer = "シービーエステクノ(カ"

func TestImportMUFG(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	o := overview(t, c)
	cash := o.Accounts[0].ID
	category := map[string]int64{}
	for _, cat := range o.Categories {
		category[cat.Name] = cat.ID
	}
	account := func(name, kind string) int64 {
		t.Helper()
		var a wallet.Account
		if code := c.do("POST", "/api/accounts", map[string]any{"name": name, "kind": kind}, &a); code != 201 {
			t.Fatalf("create %s: %d", name, code)
		}
		return a.ID
	}
	mufg := account("MUFG", "bank")
	mufg44 := account("三菱ＵＦＪ銀行 *****44", "bank")
	payPayCard := account("PayPay Card", "credit_card")
	amazon := account("Amazon Mastercard", "credit_card")
	payPay := account("PayPay Balance", "e_money")

	// The ¥3,000 charge on Oct 14, already recorded (from PayPay) in the
	// other bank account: it must be matched, not imported again.
	var charge wallet.Transaction
	if code := c.do("POST", "/api/transactions", map[string]any{"kind": "transfer", "amount": 3000, "account_id": mufg44,
		"to_account_id": payPay, "occurred_at": time.Date(2026, 10, 14, 11, 10, 52, 0, time.UTC), "payee": "PayPay", "note": "Top-Up"}, &charge); code != 201 {
		t.Fatalf("create charge: %d", code)
	}

	sjis, err := japanese.ShiftJIS.NewEncoder().Bytes([]byte(mufgCSV))
	if err != nil {
		t.Fatal(err)
	}
	// The dry run, with the file as MUFG sends it.
	var p wallet.MUFGPreview
	if code := c.send("/api/import/mufg?preview=1", "text/csv", sjis, nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	if p.Records != 12 || p.New != 11 || len(p.Rows) != 12 {
		t.Errorf("preview: %d rows, %d new (want 12, 11)", p.Records, p.New)
	}
	roles := map[string]wallet.MUFGAccount{}
	for _, a := range p.Accounts {
		roles[a.Role] = a
	}
	if a := roles[wallet.MUFGBank]; a.AccountID == nil || *a.AccountID != mufg || len(a.Candidates) != 1 || a.Candidates[0] != mufg44 {
		t.Errorf("bank: %+v (want MUFG, and the other MUFG-looking account as a candidate)", a)
	}
	for role, want := range map[string]int64{wallet.MUFGCash: cash, wallet.MUFGPayPayCard: payPayCard, wallet.MUFGPayPay: payPay} {
		if a := roles[role]; a.AccountID == nil || *a.AccountID != want {
			t.Errorf("%s: %+v, want account %d", role, a, want)
		}
	}
	if a := roles[wallet.MUFGSMBCCard]; a.AccountID != nil || a.NewName != "SMBC Card" {
		t.Errorf("no account looks like the SMBC card yet: %+v", a)
	}
	if r := p.Rows[1]; r.Status != "matched" || *r.MatchedID != charge.ID || *r.MatchedAccountID != mufg44 {
		t.Errorf("the charge should match the record in the other bank account: %+v", r)
	}
	if r := p.Rows[10]; r.Rule || r.Kind != "expense" || r.Group != "ヤマダ タロウ" {
		t.Errorf("a 振込 to a person has no rule: %+v", r)
	}

	// Another user can't import into these accounts.
	if code := other.do("POST", "/api/import/mufg", map[string]any{"csv": mufgCSV,
		"accounts": []map[string]any{{"name": "bank", "account_id": mufg}}}, nil); code != 404 {
		t.Errorf("another user's account: %d, want 404", code)
	}

	// Apply, as the app sends it: UTF-8 in JSON, the card's account picked,
	// and the employer's income filed as salary.
	importIt := func(matchBalance bool) wallet.MUFGResult {
		t.Helper()
		var res wallet.MUFGResult
		if code := c.do("POST", "/api/import/mufg", map[string]any{
			"csv":           mufgCSV,
			"accounts":      []map[string]any{{"name": wallet.MUFGSMBCCard, "account_id": amazon}},
			"categories":    []map[string]any{{"name": employer, "kind": "income", "category_id": category["Salary"]}},
			"match_balance": matchBalance,
		}, &res); code != 200 {
			t.Fatalf("import: %d", code)
		}
		return res
	}
	res := importIt(false)
	if res.Imported != 11 || res.Matched != 1 || res.Duplicates != 0 || res.AccountsCreated != 0 {
		t.Errorf("import: %+v", res.ImportResult)
	}
	// The ¥3,000 sits in the other bank account, so MUFG is ¥3,000 too high,
	// less the ¥7 it started with. Only the ¥7 belongs in its starting
	// balance; the ¥3,000 is fixed by moving the record.
	if b := res.Balance; b.Closing != 134409 || b.Opening != 7 || b.App != 137402 || b.Difference != -2993 ||
		b.Misplaced != -3000 || b.SuggestedInitialBalance != 7 || b.Adjusted != 0 || b.InitialBalance != 0 {
		t.Errorf("balance: %+v", b)
	}

	var list []wallet.Transaction
	c.do("GET", fmt.Sprintf("/api/transactions?account_id=%d", mufg), nil, &list)
	got := map[string]wallet.Transaction{}
	for _, r := range list {
		if r.Source != "import" {
			t.Errorf("record %+v should be an import", r)
		}
		got[fmt.Sprintf("%s %d", r.Kind, r.Amount)] = r
	}
	if r := got["transfer 1000"]; *r.ToAccountID != cash || r.AccountID != mufg {
		t.Errorf("ATM withdrawal: %+v", r)
	}
	if r := got["transfer 20000"]; r.AccountID != payPay || *r.ToAccountID != mufg {
		t.Errorf("money in from PayPay goes from PayPay to the bank: %+v", r)
	}
	if r := got["transfer 100000"]; *r.ToAccountID != payPayCard {
		t.Errorf("PayPay Card's payment: %+v", r)
	}
	if r := got["income 250000"]; r.CategoryID == nil || *r.CategoryID != category["Salary"] || r.Payee != employer {
		t.Errorf("salary: %+v", r)
	}
	if r := got["expense 110"]; r.CategoryID == nil || *r.CategoryID != category["Bank & card fees"] {
		t.Errorf("the ATM fee: %+v", r)
	}
	if r := got["income 12"]; r.CategoryID == nil || *r.CategoryID != category["Rewards & Cashback"] {
		t.Errorf("the debit card's cashback: %+v", r)
	}
	if r := got["expense 2500"]; r.CategoryID != nil || r.Status != "confirmed" || r.Payee != "ヤマダ タロウ" {
		t.Errorf("a transfer to a person is recorded without a category, to sort: %+v", r)
	}
	if !got["transfer 1000"].OccurredAt.Equal(time.Date(2026, 10, 15, 3, 0, 0, 0, time.UTC)) {
		t.Errorf("rows are recorded at noon in Japan: %v", got["transfer 1000"].OccurredAt)
	}
	atm := 0
	for _, r := range list {
		if r.ToAccountID != nil && *r.ToAccountID == cash {
			atm++
		}
	}
	if atm != 3 {
		t.Errorf("three ATM withdrawals, two equal on one day, want 3 records: %d", atm)
	}

	// Importing again, or an overlapping period, adds nothing; the choices
	// are remembered.
	if again := importIt(false); again.Imported != 0 || again.Duplicates != 11 || again.Matched != 1 {
		t.Errorf("re-import: %+v", again.ImportResult)
	}

	// With match_balance the import sets MUFG's starting balance: to the ¥7
	// missing from before the statement, leaving the misplaced ¥3,000.
	if b := importIt(true).Balance; b.Adjusted != 7 || b.InitialBalance != 7 || b.App != 137409 || b.Difference != -3000 || b.Misplaced != -3000 {
		t.Errorf("matching the balance: %+v", b)
	}
	if a := accountByID(t, c, mufg); a.InitialBalance != 7 {
		t.Errorf("MUFG's starting balance: %d, want 7", a.InitialBalance)
	}
	// Once the charge is moved to MUFG, the account matches the statement,
	// and matching again changes nothing.
	moved := map[string]any{"kind": "transfer", "amount": 3000, "account_id": mufg, "to_account_id": payPay,
		"occurred_at": charge.OccurredAt, "payee": charge.Payee, "note": charge.Note, "status": "confirmed"}
	if code := c.do("PUT", fmt.Sprintf("/api/transactions/%d", charge.ID), moved, nil); code != 200 {
		t.Fatalf("move the charge: %d", code)
	}
	if b := importIt(true).Balance; b.Adjusted != 0 || b.Difference != 0 || b.Misplaced != 0 || b.InitialBalance != 7 || b.App != 134409 {
		t.Errorf("after moving the charge: %+v", b)
	}
	if a := accountByID(t, c, mufg); a.Balance != 134409 {
		t.Errorf("MUFG's balance: %d, want the statement's 134409", a.Balance)
	}
	c.send("/api/import/mufg?preview=1", "text/csv", []byte(mufgCSV), nil, &p)
	if p.New != 0 {
		t.Errorf("after the import nothing is new: %d", p.New)
	}
	for _, a := range p.Accounts {
		if a.Role == wallet.MUFGSMBCCard && (a.AccountID == nil || *a.AccountID != amazon) {
			t.Errorf("the SMBC card's account should be remembered: %+v", a)
		}
	}
	for _, g := range p.Categories {
		if g.Name == employer && (!g.Learned || *g.CategoryID != category["Salary"]) {
			t.Errorf("the employer's category should be remembered: %+v", g)
		}
	}
	// What the import remembers isn't shown as learned from payments.
	var rules wallet.Rules
	c.do("GET", "/api/rules", nil, &rules)
	if len(rules.Payees) != 0 || len(rules.Cards) != 0 {
		t.Errorf("rules: %+v", rules)
	}

	// A statement with a row missing is refused.
	broken := strings.Replace(mufgCSV, `"2026/10/15","カードＣ１","","1,000","","6,007","","",""`+"\r\n", "", 1)
	if code := c.send("/api/import/mufg?preview=1", "text/csv", []byte(broken), nil, nil); code != 400 {
		t.Errorf("a statement whose balances don't add up: %d, want 400", code)
	}
}

func accountByID(t *testing.T, c *client, id int64) wallet.Account {
	t.Helper()
	for _, a := range overview(t, c).Accounts {
		if a.ID == id {
			return a
		}
	}
	t.Fatalf("no account %d", id)
	return wallet.Account{}
}

func TestReconcileAccount(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	o := overview(t, c)
	cash := o.Accounts[0].ID
	var bank wallet.Account
	c.do("POST", "/api/accounts", map[string]any{"name": "Bank", "kind": "bank"}, &bank)
	// ¥11,000 withdrawn, ¥500 of it recorded as spent.
	c.do("POST", "/api/transactions", map[string]any{"kind": "transfer", "amount": 11000, "account_id": bank.ID, "to_account_id": cash,
		"occurred_at": time.Date(2026, 10, 14, 3, 0, 0, 0, time.UTC)}, nil)
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 500, "account_id": cash,
		"occurred_at": time.Date(2026, 10, 15, 3, 0, 0, 0, time.UTC), "payee": "Lawson"}, nil)

	var out struct{ Transaction *wallet.Transaction }
	if code := c.do("POST", fmt.Sprintf("/api/accounts/%d/reconcile", cash), map[string]any{"balance": 4000}, &out); code != 201 {
		t.Fatalf("reconcile: %d", code)
	}
	var otherCat int64
	for _, cat := range o.Categories {
		if cat.Name == "Other" {
			otherCat = cat.ID
		}
	}
	if r := out.Transaction; r == nil || r.Kind != "expense" || r.Amount != 6500 || r.CategoryID == nil || *r.CategoryID != otherCat {
		t.Errorf("want one ¥6,500 expense in Other: %+v", r)
	}
	for _, a := range overview(t, c).Accounts {
		if a.ID == cash && a.Balance != 4000 {
			t.Errorf("cash balance %d, want 4000", a.Balance)
		}
	}
	// Already right: nothing recorded.
	if code := c.do("POST", fmt.Sprintf("/api/accounts/%d/reconcile", cash), map[string]any{"balance": 4000}, &out); code != 200 || out.Transaction != nil {
		t.Errorf("no difference: %d %+v", code, out.Transaction)
	}
	// More than the app thought is income.
	c.do("POST", fmt.Sprintf("/api/accounts/%d/reconcile", cash), map[string]any{"balance": 4100}, &out)
	if r := out.Transaction; r == nil || r.Kind != "income" || r.Amount != 100 {
		t.Errorf("want ¥100 income: %+v", r)
	}
	if code := c.do("POST", fmt.Sprintf("/api/accounts/%d/reconcile", cash), map[string]any{}, nil); code != 400 {
		t.Errorf("no balance: %d, want 400", code)
	}
	if code := other.do("POST", fmt.Sprintf("/api/accounts/%d/reconcile", cash), map[string]any{"balance": 0}, nil); code != 404 {
		t.Errorf("another user's account: %d, want 404", code)
	}
}
