package server_test

import (
	"encoding/hex"
	"fmt"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

const icIDm = "01120312AB34CD56"

// icEntry is a transit IC card's history block (internal/wallet/importic.go):
// terminal and process, a day in October 2026, the balance after it, and
// its sequence number. A shop's entry (terminal C7) is at 13:45:30.
func icEntry(terminal, process byte, day, balance, seq int) string {
	b := make([]byte, 16)
	b[0], b[1] = terminal, process
	b[4], b[5] = 26<<1|10>>3, (10&7)<<5|byte(day)
	if terminal == 0xc7 {
		b[6], b[7] = 13<<3|45>>3, (45&7)<<5|30/2
	}
	b[10], b[11] = byte(balance), byte(balance>>8)
	b[13], b[14] = byte(seq>>8), byte(seq)
	return hex.EncodeToString(b)
}

func TestImportICCard(t *testing.T) {
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

	// Newest first, as the card gives it: a fare (where the history
	// starts), a top-up, a train fare, a bus fare, a shop and a refund.
	history := []string{
		icEntry(0x16, 0x04, 4, 2480, 105),
		icEntry(0xc7, 0x46, 3, 2430, 104),
		icEntry(0x05, 0x0d, 3, 2580, 103),
		icEntry(0x16, 0x01, 2, 2790, 102),
		icEntry(0x08, 0x02, 2, 3000, 101),
		icEntry(0x16, 0x01, 1, 1000, 100),
	}
	preview := func(history []string) wallet.ICPreview {
		t.Helper()
		var p wallet.ICPreview
		if code := c.do("POST", "/api/import/ic-card?preview=1", map[string]any{"idm": icIDm, "history": history}, &p); code != 200 {
			t.Fatalf("preview: %d", code)
		}
		return p
	}
	p := preview(history)
	if p.Card != "CD56" || p.From != "2026-10-01" || p.To != "2026-10-04" || p.Records != 5 || p.New != 5 || p.Gap {
		t.Errorf("preview: %+v", p)
	}
	if len(p.Accounts) != 2 || p.Accounts[0].AccountID != nil || p.Accounts[0].NewName != "IC card CD56" ||
		p.Accounts[1].Role != wallet.ICTopUp || p.Accounts[1].AccountID == nil || *p.Accounts[1].AccountID != cash {
		t.Errorf("accounts: a new one for the card, and cash for top-ups: %+v", p.Accounts)
	}
	if p.Rows[0].Status != "start" || p.Rows[4].Time != "13:45" || p.Rows[4].Amount != -150 || p.Rows[4].Group != "Shop payment" {
		t.Errorf("rows: %+v", p.Rows)
	}
	groups := map[string]wallet.ImportCategory{}
	for _, g := range p.Categories {
		groups[g.Name] = g
	}
	if g := groups["Train"]; g.CategoryID == nil || *g.CategoryID != category["Transport"] || g.Total != 210 {
		t.Errorf("train fares are transport: %+v", g)
	}
	if g := groups["Refund"]; g.Kind != "income" || g.Total != 50 {
		t.Errorf("refund: %+v", g)
	}
	// The app's account would have ¥1,480; the card has ¥2,480, what it
	// held before the first entry.
	if b := p.Balance; b.Card != 2480 || b.App != 1480 || b.Difference != 1000 || b.SuggestedInitialBalance != 1000 {
		t.Errorf("balance: %+v", b)
	}

	importIt := func(history []string, accounts []map[string]any) wallet.ICResult {
		t.Helper()
		var res wallet.ICResult
		if code := c.do("POST", "/api/import/ic-card", map[string]any{
			"idm": icIDm, "history": history, "accounts": accounts, "match_balance": true,
			"categories": []map[string]any{{"name": "Shop payment", "kind": "expense", "category_id": category["Shopping"]}},
		}, &res); code != 200 {
			t.Fatalf("import: %d", code)
		}
		return res
	}
	res := importIt(history, nil)
	if res.Imported != 5 || res.AccountsCreated != 1 || res.Balance.Adjusted != 1000 || res.Balance.Difference != 0 {
		t.Errorf("import: %+v %+v", res.ImportResult, res.Balance)
	}
	card := *res.Balance.AccountID
	if a := accountByID(t, c, card); a.Name != "IC card CD56" || a.Kind != "e_money" || a.Balance != 2480 {
		t.Errorf("the card's account: %+v", a)
	}
	var list []wallet.Transaction
	c.do("GET", fmt.Sprintf("/api/transactions?account_id=%d", card), nil, &list)
	got := map[string]wallet.Transaction{}
	for _, r := range list {
		got[fmt.Sprintf("%s %s %d", r.Kind, r.Payee, r.Amount)] = r
	}
	if r := got["transfer  2000"]; r.AccountID != cash || *r.ToAccountID != card {
		t.Errorf("a top-up moves cash to the card: %+v", r)
	}
	if r := got["expense Bus 210"]; r.CategoryID == nil || *r.CategoryID != category["Transport"] {
		t.Errorf("bus fare: %+v", r)
	}
	if r := got["expense Shop payment 150"]; r.CategoryID == nil || *r.CategoryID != category["Shopping"] ||
		!r.OccurredAt.Equal(time.Date(2026, 10, 3, 4, 45, 30, 0, time.UTC)) {
		t.Errorf("a shop payment keeps its time: %+v", r)
	}

	// Another user can't import into this account.
	if code := other.do("POST", "/api/import/ic-card", map[string]any{"idm": icIDm, "history": history,
		"accounts": []map[string]any{{"name": wallet.ICCard, "account_id": card}}}, nil); code != 404 {
		t.Errorf("another user's account: %d, want 404", code)
	}

	// A fare recorded by hand, then the card read again: the oldest entries
	// have scrolled off, two are new, and the hand-made one is matched.
	if code := c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 170, "account_id": card,
		"occurred_at": time.Date(2026, 10, 5, 9, 0, 0, 0, time.UTC), "payee": "JR"}, nil); code != 201 {
		t.Fatal("record by hand")
	}
	later := append([]string{icEntry(0x16, 0x01, 6, 2110, 107), icEntry(0x16, 0x01, 5, 2310, 106)}, history[:4]...)
	p = preview(later)
	if p.New != 1 || p.Gap || p.Accounts[0].AccountID == nil || *p.Accounts[0].AccountID != card || p.Balance.Difference != 0 {
		t.Errorf("read again: %+v", p)
	}
	if r := p.Rows[4]; r.Status != "matched" || r.Amount != -170 {
		t.Errorf("the fare recorded by hand: %+v", r)
	}
	if res := importIt(later, nil); res.Imported != 1 || res.Matched != 1 || res.Duplicates != 4 || res.Balance.Adjusted != 0 {
		t.Errorf("import again: %+v %+v", res.ImportResult, res.Balance)
	}
	if a := accountByID(t, c, card); a.Balance != 2110 {
		t.Errorf("the card's account: %d, want the card's 2110", a.Balance)
	}

	// Read after more entries than the card keeps: the ones between are lost.
	if p := preview([]string{icEntry(0x16, 0x01, 21, 800, 151), icEntry(0x16, 0x01, 20, 1000, 150)}); !p.Gap || p.New != 1 {
		t.Errorf("a gap: %+v", p)
	}

	// Not a card read.
	if code := c.do("POST", "/api/import/ic-card?preview=1", map[string]any{"idm": "nope", "history": history}, nil); code != 400 {
		t.Errorf("a bad IDm: %d, want 400", code)
	}
}
