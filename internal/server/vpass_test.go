package server_test

import (
	"strings"
	"testing"
	"time"

	"golang.org/x/text/encoding/japanese"

	"github.com/rudnam/wallet-app/internal/notify"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// vpassCSV is a Vpass statement for one card: four purchases, one of them
// (Nov 8, ¥1,798) also sent as a notification, and the bill's lines.
var vpassCSV = strings.Join([]string{
	`ヤマダ　タロウ　様,5334-91**-****-****,Ａｍａｚｏｎマスター`,
	`2026/11/03,ＧＯＯＧＬＥ　ＹＯＵＴＵＢＥＰＲＥＭＩＵ,1280,１,１,1280,`,
	`2026/11/08,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,1798,１,１,1798,`,
	`2026/11/09,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,2690,１,１,2690,`,
	`2026/11/10,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,-318,１,１,-318,`,
	`,※リボ払いお支払い額（弁済金）※,,,,5523,残高などはお支払予定明細欄をご覧ください`,
	`,,,,,10973,`,
}, "\r\n") + "\r\n"

func TestImportVpass(t *testing.T) {
	ts := newTestServer(t)
	c, other := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	other.signup("b@example.com")
	// The Nov 8 purchase came as a notification first, before the card had
	// an account of its own: it went to the only account, Cash.
	got := post(t, c, notify.Incoming{Package: vpass, When: time.Date(2026, 11, 8, 1, 23, 0, 0, time.UTC).UnixMilli(),
		Title: "ご利用のお知らせ", Text: vpassAlert})
	if got[0].TransactionID == nil {
		t.Fatalf("notification %+v", got[0])
	}
	amazon := newAccount(t, c, "Amazon Mastercard", "credit_card")

	sjis, err := japanese.ShiftJIS.NewEncoder().Bytes([]byte(vpassCSV))
	if err != nil {
		t.Fatal(err)
	}
	var p wallet.VpassPreview
	if code := c.send("/api/import/vpass?preview=1", "text/csv", sjis, nil, &p); code != 200 {
		t.Fatalf("preview: %d", code)
	}
	if p.Records != 4 || p.New != 3 || p.Matched != 1 || len(p.Rows) != 4 {
		t.Errorf("preview: %d records, %d new, %d matched (want 4, 3, 1)", p.Records, p.New, p.Matched)
	}
	if len(p.Methods) != 1 || p.Methods[0].Method != "Amazonマスター" || p.Methods[0].AccountID == nil || *p.Methods[0].AccountID != amazon {
		t.Errorf("the card should be suggested its account by name: %+v", p.Methods)
	}
	if p.Rows[1].Status != "matched" || p.Rows[1].MatchedID == nil || *p.Rows[1].MatchedID != *got[0].TransactionID {
		t.Errorf("the notified purchase should match its record: %+v", p.Rows[1])
	}
	if r := p.Rows[3]; r.Kind != "income" || r.Amount != 318 || r.Payee != "AMAZON.CO.JP" || !r.AllDay {
		t.Errorf("refund row %+v", r)
	}
	// Categories are asked for the rows to record: the notified Nov 8
	// purchase already has a record, so Amazon's purchases count one.
	groups := map[string]wallet.ImportCategory{}
	for _, g := range p.Categories {
		groups[g.Kind+" "+g.Name] = g
	}
	if len(p.Categories) != 3 || groups["expense AMAZON.CO.JP"].Count != 1 || groups["expense AMAZON.CO.JP"].Total != 2690 ||
		groups["income AMAZON.CO.JP"].Count != 1 || groups["expense GOOGLE YOUTUBEPREMIU"].Count != 1 {
		t.Errorf("categories %+v", p.Categories)
	}

	// Another user can't send records to this user's account.
	if code := other.do("POST", "/api/import/vpass", map[string]any{"csv": vpassCSV,
		"accounts": []map[string]any{{"name": "Amazonマスター", "account_id": amazon}}}, nil); code != 404 {
		t.Errorf("other user's account: %d, want 404", code)
	}

	var res wallet.ImportResult
	if code := c.do("POST", "/api/import/vpass", map[string]any{"csv": vpassCSV}, &res); code != 200 {
		t.Fatalf("import: %d", code)
	}
	if res.Imported != 3 || res.Matched != 1 || res.BatchID == nil {
		t.Errorf("import %+v", res)
	}
	var recs []wallet.Transaction
	c.do("GET", "/api/transactions?account_id="+itoa(amazon), nil, &recs)
	var balance int64
	for _, r := range recs {
		if r.Kind == "expense" {
			balance -= r.Amount
		} else {
			balance += r.Amount
		}
	}
	if len(recs) != 3 || balance != -1280-2690+318 {
		t.Errorf("card records %d, balance %d", len(recs), balance)
	}

	// The same statement again adds nothing, and the card's notifications
	// now go to its account.
	if code := c.do("POST", "/api/import/vpass", map[string]any{"csv": vpassCSV}, &res); code != 200 {
		t.Fatalf("import again: %d", code)
	}
	if res.Imported != 0 || res.Duplicates != 3 || res.Matched != 1 {
		t.Errorf("import again %+v", res)
	}
	// Nor does its preview ask for any categories.
	if code := c.send("/api/import/vpass?preview=1", "text/csv", sjis, nil, &p); code != 200 || p.New != 0 || len(p.Categories) != 0 {
		t.Errorf("preview again: %d, %d new, categories %+v", code, p.New, p.Categories)
	}
	got = post(t, c, notify.Incoming{Package: vpass, When: time.Date(2026, 11, 20, 1, 0, 0, 0, time.UTC).UnixMilli(),
		Title: "ご利用のお知らせ", Text: strings.Replace(vpassAlert, "2026/11/08 10:22", "2026/11/20 10:00", 1)})
	var later *wallet.Transaction
	for _, r := range records(t, c) {
		if r.ID == *got[0].TransactionID {
			later = &r
		}
	}
	if later == nil || later.AccountID != amazon {
		t.Errorf("a later notification went to %+v, want the card's account %d", later, amazon)
	}

	// Not a Vpass statement.
	if code := c.send("/api/import/vpass?preview=1", "text/csv", []byte(mufgCSV), nil, nil); code != 400 {
		t.Errorf("MUFG file: %d, want 400", code)
	}
}
