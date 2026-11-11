package server_test

import (
	"fmt"
	"net/url"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/notify"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// pay posts a PayPay Card payment notification, as the phone would, and
// returns the record it made.
func pay(t *testing.T, c *client, merchant string, yen int, when time.Time) wallet.Transaction {
	t.Helper()
	jst := when.In(time.FixedZone("JST", 9*60*60))
	n := post(t, c, notify.Incoming{Package: payPay, When: when.UnixMilli(), Title: "支払いを受け付けました",
		Text: fmt.Sprintf("お店：%s\n金額：%d円\n日時：%s", merchant, yen, jst.Format("2006年1月2日 15:04"))})[0]
	if n.TransactionID == nil {
		t.Fatalf("notification %+v", n)
	}
	for _, x := range records(t, c) {
		if x.ID == *n.TransactionID {
			return x
		}
	}
	t.Fatalf("no record %d", *n.TransactionID)
	return wallet.Transaction{}
}

func confirm(t *testing.T, c *client, r wallet.Transaction, category int64, extra map[string]any) {
	t.Helper()
	body := map[string]any{
		"kind": r.Kind, "amount": r.Amount, "account_id": r.AccountID, "category_id": category,
		"occurred_at": r.OccurredAt, "payee": r.Payee, "status": "confirmed",
	}
	for k, v := range extra {
		body[k] = v
	}
	if code := c.do("PUT", fmt.Sprintf("/api/transactions/%d", r.ID), body, nil); code != 200 {
		t.Fatalf("confirm %d: %d", r.ID, code)
	}
}

func TestLearnedRules(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	food, shopping := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Shopping")
	day := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)

	// Confirming teaches the shop's category and the card's account.
	confirm(t, alice, pay(t, alice, "ローソン", 550, day), food, nil)
	var rules wallet.Rules
	if code := alice.do("GET", "/api/rules", nil, &rules); code != 200 {
		t.Fatalf("rules: %d", code)
	}
	if len(rules.Payees) != 1 || rules.Payees[0].Payee != "ローソン" || rules.Payees[0].CategoryID != food || rules.Payees[0].Records != 1 {
		t.Errorf("payee rules %+v", rules.Payees)
	}
	if len(rules.Cards) != 1 || rules.Cards[0].Source != "PayPay Card" || rules.Cards[0].Records != 1 {
		t.Errorf("card rules %+v", rules.Cards)
	}

	// A one-off category, confirmed without remembering, leaves the rule alone.
	r := pay(t, alice, "ローソン", 1200, day.Add(time.Hour))
	if r.CategoryID == nil || *r.CategoryID != food {
		t.Fatalf("rule not applied: %+v", r)
	}
	confirm(t, alice, r, shopping, map[string]any{"remember": false})
	if r := pay(t, alice, "ローソン", 300, day.Add(2*time.Hour)); r.CategoryID == nil || *r.CategoryID != food {
		t.Errorf("remember false changed the rule: %+v", r)
	}

	// Rules can be added, changed and forgotten by hand.
	var rule wallet.PayeeRule
	if code := alice.do("PUT", "/api/rules/payees", map[string]any{"payee": " ＡＭＡＺＯＮ ", "category_id": shopping}, &rule); code != 200 || rule.Key != "amazon" {
		t.Errorf("add rule: %d %+v", code, rule)
	}
	if r := pay(t, alice, "Amazon.co.jp", 2980, day.Add(3*time.Hour)); r.CategoryID == nil || *r.CategoryID != shopping {
		t.Errorf("added rule not applied: %+v", r)
	}
	if code := alice.do("DELETE", "/api/rules/payees?payee="+url.QueryEscape("ローソン"), nil, nil); code != 204 {
		t.Errorf("forget: %d", code)
	}
	if r := pay(t, alice, "ローソン", 410, day.Add(4*time.Hour)); r.CategoryID != nil {
		t.Errorf("forgotten rule still applied: %+v", r)
	}
	var card wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "PayPay Card", "kind": "credit_card"}, &card)
	if code := alice.do("PUT", "/api/rules/cards", map[string]any{"source": "PayPay Card", "account_id": card.ID}, nil); code != 204 {
		t.Errorf("move card: %d", code)
	}
	if r := pay(t, alice, "ファミリーマート", 180, day.Add(5*time.Hour)); r.AccountID != card.ID {
		t.Errorf("card rule not applied: %+v", r)
	}

	// Rules are private: bob sees none and can't change alice's.
	bob.do("GET", "/api/rules", nil, &rules)
	if len(rules.Payees) != 0 || len(rules.Cards) != 0 {
		t.Errorf("bob sees %+v", rules)
	}
	if code := bob.do("DELETE", "/api/rules/payees?payee=amazon", nil, nil); code != 404 {
		t.Errorf("bob forgets alice's rule: %d", code)
	}
	if code := bob.do("PUT", "/api/rules/cards", map[string]any{"source": "PayPay Card", "account_id": overview(t, bob).Accounts[0].ID}, nil); code != 404 {
		t.Errorf("bob moves alice's card: %d", code)
	}
	if code := alice.do("PUT", "/api/rules/payees", map[string]any{"payee": "x", "category_id": categoryID(t, overview(t, bob), "Shopping")}, nil); code != 400 {
		t.Errorf("rule with bob's category: %d", code)
	}
}

func TestConfirmAll(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	food, shopping := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Shopping")
	day := time.Date(2026, 10, 11, 4, 0, 0, 0, time.UTC)
	c.do("PUT", "/api/rules/payees", map[string]any{"payee": "ローソン", "category_id": food}, nil)

	known := pay(t, c, "ローソン", 550, day)
	newShop := pay(t, c, "無印良品", 1990, day.Add(time.Hour))
	unsorted := pay(t, c, "謎の店", 800, day.Add(2*time.Hour))
	// The new shop gets a category, as the review page does before confirming.
	c.do("POST", "/api/transactions/bulk", map[string]any{"ids": []int64{newShop.ID}, "action": "categorize", "category_id": shopping}, nil)
	var res wallet.BulkResult
	if code := c.do("POST", "/api/transactions/bulk", map[string]any{
		"ids": []int64{known.ID, newShop.ID, unsorted.ID}, "action": "confirm",
	}, &res); code != 200 || res.Changed != 2 || res.Skipped != 1 {
		t.Fatalf("confirm all: %d %+v", code, res)
	}
	var review []wallet.Transaction
	c.do("GET", "/api/transactions?status=review", nil, &review)
	if len(review) != 1 {
		t.Errorf("left to review %+v", review)
	}
	// The new shop was learned; the existing rule stayed.
	if r := pay(t, c, "無印良品", 590, day.Add(3*time.Hour)); r.CategoryID == nil || *r.CategoryID != shopping {
		t.Errorf("new shop not learned: %+v", r)
	}
	if r := pay(t, c, "ローソン", 120, day.Add(4*time.Hour)); r.CategoryID == nil || *r.CategoryID != food {
		t.Errorf("rule changed: %+v", r)
	}
}
