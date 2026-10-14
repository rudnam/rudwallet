package server_test

import (
	"encoding/base64"
	"fmt"
	"net/url"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/inbox"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// payPayEmail is a PayPay Card usage alert for a payment at a shop.
func payPayEmail(id, merchant string, yen int, when time.Time) []byte {
	jst := when.In(time.FixedZone("JST", 9*60*60))
	body := fmt.Sprintf("PayPayカード ゴールド（Visa）利用速報\n\n%s\n%s\n%d円\n",
		merchant, jst.Format("2006年1月2日 15:04"), yen)
	subject := base64.StdEncoding.EncodeToString([]byte("PayPayカード ゴールド（Visa）利用速報"))
	return []byte("From: PayPay Card <paypaycard-info@mail.paypay-card.co.jp>\r\n" +
		"To: user@example.com\r\n" +
		"Subject: =?UTF-8?B?" + subject + "?=\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=UTF-8\r\n" +
		"Content-Transfer-Encoding: base64\r\n" +
		"Message-ID: <" + id + "@ap-northeast-1.amazonses.com>\r\n" +
		"Date: " + when.UTC().Format(time.RFC1123Z) + "\r\n\r\n" +
		base64.StdEncoding.EncodeToString([]byte(body)))
}

// pay uploads a usage alert and returns the record it made.
func pay(t *testing.T, c *client, id, merchant string, yen int, when time.Time) wallet.Transaction {
	t.Helper()
	var e inbox.Email
	if code := c.send("/api/inbox/emails", "message/rfc822", payPayEmail(id, merchant, yen, when), nil, &e); code != 200 || e.TransactionID == nil {
		t.Fatalf("upload %s: %d %+v", id, code, e)
	}
	var all []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &all)
	for _, x := range all {
		if x.ID == *e.TransactionID {
			return x
		}
	}
	t.Fatalf("no record %d", *e.TransactionID)
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
	confirm(t, alice, pay(t, alice, "r1", "ローソン", 550, day), food, nil)
	var rules wallet.Rules
	if code := alice.do("GET", "/api/rules", nil, &rules); code != 200 {
		t.Fatalf("rules: %d", code)
	}
	if len(rules.Payees) != 1 || rules.Payees[0].Payee != "ローソン" || rules.Payees[0].CategoryID != food || rules.Payees[0].Records != 1 {
		t.Errorf("payee rules %+v", rules.Payees)
	}
	if len(rules.Cards) != 1 || rules.Cards[0].Source != "PayPayカード ゴールド（Visa）" || rules.Cards[0].Records != 1 {
		t.Errorf("card rules %+v", rules.Cards)
	}

	// A one-off category, confirmed without remembering, leaves the rule alone.
	r := pay(t, alice, "r2", "ローソン", 1200, day.Add(time.Hour))
	if r.CategoryID == nil || *r.CategoryID != food {
		t.Fatalf("rule not applied: %+v", r)
	}
	confirm(t, alice, r, shopping, map[string]any{"remember": false})
	if r := pay(t, alice, "r3", "ローソン", 300, day.Add(2*time.Hour)); r.CategoryID == nil || *r.CategoryID != food {
		t.Errorf("remember false changed the rule: %+v", r)
	}

	// Rules can be added, changed and forgotten by hand.
	var rule wallet.PayeeRule
	if code := alice.do("PUT", "/api/rules/payees", map[string]any{"payee": " ＡＭＡＺＯＮ ", "category_id": shopping}, &rule); code != 200 || rule.Key != "amazon" {
		t.Errorf("add rule: %d %+v", code, rule)
	}
	if r := pay(t, alice, "r4", "Amazon.co.jp", 2980, day.Add(3*time.Hour)); r.CategoryID == nil || *r.CategoryID != shopping {
		t.Errorf("added rule not applied: %+v", r)
	}
	if code := alice.do("DELETE", "/api/rules/payees?payee="+url.QueryEscape("ローソン"), nil, nil); code != 204 {
		t.Errorf("forget: %d", code)
	}
	if r := pay(t, alice, "r5", "ローソン", 410, day.Add(4*time.Hour)); r.CategoryID != nil {
		t.Errorf("forgotten rule still applied: %+v", r)
	}
	var card wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "PayPay Card", "kind": "credit_card"}, &card)
	if code := alice.do("PUT", "/api/rules/cards", map[string]any{"source": "PayPayカード ゴールド（Visa）", "account_id": card.ID}, nil); code != 204 {
		t.Errorf("move card: %d", code)
	}
	if r := pay(t, alice, "r6", "ファミリーマート", 180, day.Add(5*time.Hour)); r.AccountID != card.ID {
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
	if code := bob.do("PUT", "/api/rules/cards", map[string]any{"source": "PayPayカード ゴールド（Visa）", "account_id": overview(t, bob).Accounts[0].ID}, nil); code != 404 {
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

	known := pay(t, c, "a1", "ローソン", 550, day)
	newShop := pay(t, c, "a2", "無印良品", 1990, day.Add(time.Hour))
	unsorted := pay(t, c, "a3", "謎の店", 800, day.Add(2*time.Hour))
	// The new shop gets a category, as the review page does before confirming.
	c.do("POST", "/api/transactions/bulk", map[string]any{"ids": []int64{newShop.ID}, "action": "categorize", "category_id": shopping}, nil)
	// A hand-typed record of the same amount makes this one a possible duplicate.
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 640, "account_id": o.Accounts[0].ID, "occurred_at": day, "payee": "Lawson"}, nil)
	dup := pay(t, c, "a4", "ローソン", 640, day.Add(10*time.Minute))
	if dup.DuplicateOfID == nil {
		t.Fatalf("want a possible duplicate: %+v", dup)
	}

	var res wallet.BulkResult
	if code := c.do("POST", "/api/transactions/bulk", map[string]any{
		"ids": []int64{known.ID, newShop.ID, unsorted.ID, dup.ID}, "action": "confirm",
	}, &res); code != 200 || res.Changed != 2 || res.Skipped != 2 {
		t.Fatalf("confirm all: %d %+v", code, res)
	}
	var review []wallet.Transaction
	c.do("GET", "/api/transactions?status=review", nil, &review)
	if len(review) != 2 {
		t.Errorf("left to review %+v", review)
	}
	// The new shop was learned; the existing rule stayed.
	if r := pay(t, c, "a5", "無印良品", 590, day.Add(3*time.Hour)); r.CategoryID == nil || *r.CategoryID != shopping {
		t.Errorf("new shop not learned: %+v", r)
	}
	if r := pay(t, c, "a6", "ローソン", 120, day.Add(4*time.Hour)); r.CategoryID == nil || *r.CategoryID != food {
		t.Errorf("rule changed: %+v", r)
	}
}
