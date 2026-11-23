package server_test

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

const payPayHeader = "Date & Time,Amount Outgoing (Yen),Amount Incoming (Yen),Amount Outgoing Overseas,Currency,Exchange Rate (Yen),Country Paid In,Transaction Type,Business Name,Method,Payment Option,User,Transaction ID"

// payPayImport imports a PayPay file with these accounts by 取引方法.
func payPayImport(t *testing.T, c *client, csv string, accounts map[string]int64) wallet.ImportResult {
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

func newAccount(t *testing.T, c *client, name, kind string) int64 {
	t.Helper()
	var a wallet.Account
	if code := c.do("POST", "/api/accounts", map[string]any{"name": name, "kind": kind}, &a); code != 201 {
		t.Fatalf("create %s: %d", name, code)
	}
	return a.ID
}

// Records imported before PayPay keys named the row's kind and method have
// keys that counted every row with the 取引番号: points earned later on a
// payment, listed above it in a newer export, took the payment's number.
// Those records are found by what they are, not by key.
func TestImportPayPayOlderKeys(t *testing.T) {
	ts, conn := newTestServerDB(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	paypay := newAccount(t, c, "PayPay", "e_money")
	points := newAccount(t, c, "PayPay Points", "e_money")
	card := newAccount(t, c, "Card 8602", "credit_card")
	accounts := map[string]int64{"PayPay残高": paypay, "PayPayポイント": points, "Credit VISA 8602": card}

	older := strings.Join([]string{
		payPayHeader,
		`2026/09/25 19:14:00,"5,314",-,-,-,-,-,Payment,Uber Eats,PayPay Balance,-,-,k-01`,
		`2026/09/20 10:00:00,"5,480",-,-,-,-,-,Payment,Yahoo!ショッピング,"PayPay Point (686yen), Credit VISA 8602 (4,794yen)",-,-,k-02`,
	}, "\n")
	if res := payPayImport(t, c, older, accounts); res.Imported != 3 {
		t.Fatalf("first import: %+v", res)
	}
	// As the import keyed them before: by the 取引番号 and its count.
	if _, err := conn.Exec(`UPDATE transactions SET import_key = 'pp:k-01:1' WHERE import_key LIKE 'pp:k-01:%'`); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(`UPDATE transactions SET import_key = 'pp:k-02:' || CASE WHEN amount = 686 THEN '1' ELSE '2' END WHERE import_key LIKE 'pp:k-02:%'`); err != nil {
		t.Fatal(err)
	}

	newer := strings.Join([]string{
		payPayHeader,
		`2026/10/02 08:00:00,-,27,-,-,-,-,"Points, Balance Earned",Uber Eats,PayPay Point,-,-,k-01`,
		`2026/09/25 19:14:00,"5,314",-,-,-,-,-,Payment,Uber Eats,PayPay Balance,-,-,k-01`,
		`2026/09/20 10:00:00,"5,480",-,-,-,-,-,Payment,Yahoo!ショッピング,"PayPay Point (686yen), Credit VISA 8602 (4,794yen)",-,-,k-02`,
	}, "\n")
	res := payPayImport(t, c, newer, accounts)
	if res.Imported != 1 || res.Duplicates != 3 {
		t.Errorf("newer export %+v (want only the points earned new; the payment and the split payment imported before)", res)
	}
	if again := payPayImport(t, c, newer, accounts); again.Imported != 0 || again.Duplicates != 4 {
		t.Errorf("the same export again %+v", again)
	}
}

// A preview is the import run and rolled back: its counts are the import's,
// it follows the choices sent with it, and it changes nothing.
func TestImportPreviewIsTheImport(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	paypay := newAccount(t, c, "PayPay", "e_money")
	bank := newAccount(t, c, "Bank", "bank")
	// Recorded from its notification already.
	if code := c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1200, "account_id": paypay,
		"occurred_at": time.Date(2026, 9, 30, 23, 15, 30, 0, time.UTC), "payee": "LAWSON"}, nil); code != 201 {
		t.Fatalf("create record: %d", code)
	}
	csv := strings.Join([]string{
		payPayHeader,
		`2026/10/01 08:15:00,"1,200",-,-,-,-,-,Payment,ローソン,PayPay Balance,-,-,v-01`,
		`2026/10/02 09:00:00,500,-,-,-,-,-,Payment,ファミリーマート,PayPay Balance,-,-,v-02`,
		`2026/10/03 10:00:00,-,"3,000",-,-,-,-,Top-Up,PayPay,Bank,-,-,v-03`,
		`2026/10/04 11:00:00,300,-,-,-,-,-,Payment,セブン-イレブン,PayPay Point,-,-,v-04`,
	}, "\n")
	preview := func(accounts []map[string]any) wallet.PayPayPreview {
		t.Helper()
		var p wallet.PayPayPreview
		if code := c.do("POST", "/api/import/paypay?preview=1", map[string]any{"csv": csv, "accounts": accounts}, &p); code != 200 {
			t.Fatalf("preview: %d", code)
		}
		return p
	}
	choices := []map[string]any{
		{"name": "PayPay残高", "account_id": paypay},
		{"name": "Bank", "account_id": bank},
		{"name": "PayPayポイント", "account_id": wallet.SkipAccount},
	}
	p := preview(choices)
	if p.New != 2 || p.Matched != 1 || p.Skipped != 1 || p.Duplicates != 0 || len(p.Rows) != 4 {
		t.Errorf("preview: new %d, matched %d, skipped %d, duplicates %d, %d rows", p.New, p.Matched, p.Skipped, p.Duplicates, len(p.Rows))
	}
	status := map[string]string{}
	for _, r := range p.Rows {
		status[r.Payee] = r.Status
	}
	if status["ローソン"] != "matched" || status["ファミリーマート"] != "new" || status["セブン-イレブン"] != "skipped" {
		t.Errorf("rows: %+v", p.Rows)
	}
	if r := p.Rows[2]; r.Kind != "transfer" || r.Account != "Bank" || r.ToAccount != "PayPay" {
		t.Errorf("the top-up, as it would be recorded: %+v", r)
	}
	// Points imported, to a new account, instead: one more new.
	if p := preview([]map[string]any{{"name": "PayPay残高", "account_id": paypay}, {"name": "Bank", "account_id": bank}, {"name": "PayPayポイント", "account_id": 0}}); p.New != 3 || p.Skipped != 0 {
		t.Errorf("with points: new %d, skipped %d", p.New, p.Skipped)
	}

	// Nothing was written: no records, accounts, or imports.
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	if o := overview(t, c); len(list) != 1 || len(o.Accounts) != 3 {
		t.Errorf("after previews: %d records, %d accounts (want 1 and 3)", len(list), len(o.Accounts))
	}
	var h struct{ Imports []wallet.ImportBatch }
	c.do("GET", "/api/imports", nil, &h)
	if len(h.Imports) != 0 {
		t.Errorf("previews are not imports: %+v", h.Imports)
	}

	res := payPayImport(t, c, csv, map[string]int64{"PayPay残高": paypay, "Bank": bank, "PayPayポイント": wallet.SkipAccount})
	if res.Imported != p.New || res.Matched != p.Matched || res.Skipped != p.Skipped {
		t.Errorf("import %+v, preview said new %d, matched %d, skipped %d", res, p.New, p.Matched, p.Skipped)
	}
	if p := preview(choices); p.New != 0 || p.Duplicates != 2 || p.Matched != 1 {
		t.Errorf("preview after the import: new %d, duplicates %d, matched %d", p.New, p.Duplicates, p.Matched)
	}
}

// Each import that adds records is listed, and can be undone in one step:
// its records go to the trash, from where they can be restored.
func TestImportHistoryAndUndo(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	paypay := newAccount(t, c, "PayPay", "e_money")
	csv := strings.Join([]string{
		payPayHeader,
		`2026/10/01 08:15:00,"1,200",-,-,-,-,-,Payment,ローソン,PayPay Balance,-,-,h-01`,
		`2026/10/02 09:00:00,500,-,-,-,-,-,Payment,ファミリーマート,PayPay Balance,-,-,h-02`,
	}, "\n")
	accounts := map[string]int64{"PayPay残高": paypay}
	res := payPayImport(t, c, csv, accounts)
	if res.Imported != 2 || res.BatchID == nil {
		t.Fatalf("import: %+v", res)
	}
	if again := payPayImport(t, c, csv, accounts); again.BatchID != nil {
		t.Errorf("an import that adds nothing isn't kept: %+v", again)
	}
	history := func() []wallet.ImportBatch {
		t.Helper()
		var h struct{ Imports []wallet.ImportBatch }
		if code := c.do("GET", "/api/imports", nil, &h); code != 200 {
			t.Fatalf("history: %d", code)
		}
		return h.Imports
	}
	h := history()
	if len(h) != 1 || h[0].ID != *res.BatchID || h[0].Source != "paypay" || h[0].Imported != 2 || h[0].Remaining != 2 ||
		h[0].From == nil || !h[0].From.Equal(time.Date(2026, 9, 30, 23, 15, 0, 0, time.UTC)) || h[0].UndoneAt != nil {
		t.Errorf("history: %+v", h)
	}

	undo := fmt.Sprintf("/api/imports/%d/undo", *res.BatchID)
	if code := other.do("POST", undo, nil, nil); code != 404 {
		t.Errorf("another user's import: %d, want 404", code)
	}
	var undone struct {
		Deleted int
		IDs     []int64
	}
	if code := c.do("POST", undo, nil, &undone); code != 200 || undone.Deleted != 2 {
		t.Fatalf("undo: %d %+v", code, undone)
	}
	var list []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &list)
	if len(list) != 0 {
		t.Errorf("after undo: %+v", list)
	}
	if h := history(); h[0].Remaining != 0 || h[0].UndoneAt == nil {
		t.Errorf("history after undo: %+v", h[0])
	}

	// Restored, it's no longer undone.
	if code := c.do("POST", "/api/transactions/restore", map[string]any{"ids": undone.IDs}, nil); code != 200 {
		t.Fatalf("restore: %d", code)
	}
	if h := history(); h[0].Remaining != 2 || h[0].UndoneAt != nil {
		t.Errorf("history after restoring: %+v", h[0])
	}
	// Undone again, the file can be imported afresh.
	c.do("POST", undo, nil, nil)
	if res := payPayImport(t, c, csv, accounts); res.Imported != 2 {
		t.Errorf("importing after undo: %+v", res)
	}
}

// Undoing an import that set a starting balance puts it back.
func TestImportUndoPutsBackTheBalance(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	mufg := newAccount(t, c, "MUFG", "bank")
	var res wallet.MUFGResult
	if code := c.do("POST", "/api/import/mufg", map[string]any{"csv": mufgCSV, "match_balance": true}, &res); code != 200 {
		t.Fatalf("import: %d", code)
	}
	if res.Balance.Adjusted == 0 || res.BatchID == nil {
		t.Fatalf("import: %+v", res.ImportResult)
	}
	if a := accountByID(t, c, mufg); a.InitialBalance != res.Balance.Adjusted || a.Balance != 134409 {
		t.Errorf("MUFG after the import: %+v", a)
	}
	if code := c.do("POST", fmt.Sprintf("/api/imports/%d/undo", *res.BatchID), nil, nil); code != 200 {
		t.Fatalf("undo: %d", code)
	}
	if a := accountByID(t, c, mufg); a.InitialBalance != 0 || a.Balance != 0 {
		t.Errorf("MUFG after undo: %+v (want its starting balance back at 0)", a)
	}
	// Undoing again changes nothing.
	c.do("POST", fmt.Sprintf("/api/imports/%d/undo", *res.BatchID), nil, nil)
	if a := accountByID(t, c, mufg); a.InitialBalance != 0 {
		t.Errorf("MUFG after a second undo: %+v", a)
	}
}

// A statement or card read again finds the rows imported before even when
// their keys changed (e.g. the bank's wording of a row), by what they are.
func TestImportFindsRowsWhoseKeyChanged(t *testing.T) {
	ts, conn := newTestServerDB(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	newAccount(t, c, "MUFG", "bank")
	var res wallet.MUFGResult
	c.do("POST", "/api/import/mufg", map[string]any{"csv": mufgCSV}, &res)
	if res.Imported == 0 {
		t.Fatalf("import: %+v", res.ImportResult)
	}
	if _, err := conn.Exec(`UPDATE transactions SET import_key = import_key || '-old' WHERE import_key LIKE 'mufg:%'`); err != nil {
		t.Fatal(err)
	}
	var again wallet.MUFGResult
	c.do("POST", "/api/import/mufg", map[string]any{"csv": mufgCSV}, &again)
	if again.Imported != 0 || again.Duplicates != res.Imported {
		t.Errorf("statement again: %+v (want all %d imported before)", again.ImportResult, res.Imported)
	}

	history := []string{
		icEntry(0x16, 0x04, 4, 2480, 105),
		icEntry(0xc7, 0x46, 3, 2430, 104),
		icEntry(0x16, 0x01, 2, 2790, 102),
		icEntry(0x08, 0x02, 2, 3000, 101),
		icEntry(0x16, 0x01, 1, 1000, 100),
	}
	var ic wallet.ICResult
	c.do("POST", "/api/import/ic-card", map[string]any{"idm": icIDm, "history": history}, &ic)
	if ic.Imported == 0 {
		t.Fatalf("card: %+v", ic.ImportResult)
	}
	if _, err := conn.Exec(`UPDATE transactions SET import_key = import_key || '-old' WHERE import_key LIKE 'ic:%'`); err != nil {
		t.Fatal(err)
	}
	var icAgain wallet.ICResult
	c.do("POST", "/api/import/ic-card", map[string]any{"idm": icIDm, "history": history}, &icAgain)
	if icAgain.Imported != 0 || icAgain.Duplicates != ic.Imported {
		t.Errorf("card again: %+v (want all %d imported before)", icAgain.ImportResult, ic.Imported)
	}
}

// BudgetBakers keys a row by all its columns, so a row edited there since
// the last export is found by what it is; and a payment already recorded
// another way isn't added again.
func TestImportBudgetBakersDuplicates(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	cash := overview(t, c).Accounts[0].ID
	if code := c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 640, "account_id": cash,
		"occurred_at": time.Date(2026, 10, 12, 5, 0, 0, 0, time.UTC), "payee": "Cafe"}, nil); code != 201 {
		t.Fatalf("create record: %d", code)
	}
	header := "account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels"
	csv := strings.Join([]string{
		header,
		"Cash;Food & Drinks;JPY;318;318;Expense;Cash;;2026-10-11T04:53:13.000Z;false;;",
		"Cash;Food & Drinks;JPY;640;640;Expense;Cash;;2026-10-12T04:40:00.000Z;false;Cafe;",
		"Cash;Food & Drinks;JPY;900;900;Expense;Cash;;2026-10-13T04:40:00.000Z;false;;",
	}, "\n")
	bb := func(csv string) wallet.ImportResult {
		t.Helper()
		var res wallet.ImportResult
		if code := c.send("/api/import/budgetbakers", "text/csv", []byte(csv), nil, &res); code != 200 {
			t.Fatalf("import: %d", code)
		}
		return res
	}
	var p wallet.BudgetBakersPreview
	c.send("/api/import/budgetbakers?preview=1", "text/csv", []byte(csv), nil, &p)
	if p.New != 2 || p.Matched != 1 {
		t.Errorf("preview: new %d, matched %d", p.New, p.Matched)
	}
	if res := bb(csv); res.Imported != 2 || res.Matched != 1 {
		t.Errorf("import: %+v (want the cafe already recorded)", res)
	}
	edited := strings.Replace(csv, "Cash;Food & Drinks;JPY;318;318;Expense;Cash;;", "Cash;Groceries;JPY;318;318;Expense;Cash;milk;", 1)
	if res := bb(edited); res.Imported != 0 || res.Duplicates != 2 || res.Matched != 1 {
		t.Errorf("the export again, one row edited: %+v", res)
	}
}
