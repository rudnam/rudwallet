package server_test

import (
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/config"
	"github.com/rudnam/wallet-app/internal/inbox"
	"github.com/rudnam/wallet-app/internal/wallet"
)

const inboundSecret = "test-inbound-secret-0123456789"

func withInbound(c *config.Config) {
	c.InboundDomain = "in.example.com"
	c.InboundSecret = inboundSecret
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../inbox/parse/testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

type inboxView struct {
	Address   string        `json:"address"`
	AIEnabled bool          `json:"ai_enabled"`
	Emails    []inbox.Email `json:"emails"`
}

// deliver sends an email to the webhook, as the mail provider would.
func deliver(c *client, to string, raw []byte, out any) int {
	return c.send("/api/inbound/email", "message/rfc822", raw,
		map[string]string{"Authorization": "Bearer " + inboundSecret, "X-Inbound-To": to}, out)
}

func TestInboundPaymentEmail(t *testing.T) {
	ts := newTestServer(t, withInbound)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	var v inboxView
	if code := alice.do("GET", "/api/inbox", nil, &v); code != 200 || !strings.HasPrefix(v.Address, "pay-") || !strings.HasSuffix(v.Address, "@in.example.com") {
		t.Fatalf("inbox: %d %+v", code, v)
	}
	if v.AIEnabled {
		t.Error("AI reader on without an API key")
	}
	var e inbox.Email
	if code := deliver(alice, "<"+strings.ToUpper(v.Address)+">", fixture(t, "paypay-usage.eml"), &e); code != 200 {
		t.Fatalf("deliver: %d", code)
	}
	if e.Status != "payment" || e.Parser != "paypay-card" || e.TransactionID == nil || e.BodyText != "" {
		t.Fatalf("email %+v", e)
	}

	// The record waits for review in the default (first) account.
	o := overview(t, alice)
	if o.ReviewCount != 1 {
		t.Errorf("review count %d", o.ReviewCount)
	}
	var review []wallet.Transaction
	alice.do("GET", "/api/transactions?status=review", nil, &review)
	if len(review) != 1 {
		t.Fatalf("review list %+v", review)
	}
	r := review[0]
	if r.Amount != 3280 || r.Payee != "テスト書店オンライン" || r.Source != "email" || r.SourceLabel != "PayPayカード ゴールド（Visa）" ||
		r.AccountID != o.Accounts[0].ID || r.CategoryID != nil || !r.OccurredAt.Equal(time.Date(2026, 10, 11, 4, 21, 0, 0, time.UTC)) {
		t.Errorf("record %+v", r)
	}
	// Records to review don't count towards balances yet.
	if o.Accounts[0].Balance != 0 {
		t.Errorf("balance %d before confirming", o.Accounts[0].Balance)
	}

	// The same email again (a retried delivery) is not recorded twice.
	deliver(alice, v.Address, fixture(t, "paypay-usage.eml"), &e)
	if !e.Duplicate {
		t.Errorf("redelivery not marked duplicate: %+v", e)
	}
	if overview(t, alice).ReviewCount != 1 {
		t.Error("redelivery made a second record")
	}

	// Bob's view is unaffected.
	if overview(t, bob).ReviewCount != 0 {
		t.Error("bob sees alice's email")
	}

	// Confirming with a category and a card account teaches both.
	var card wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "PayPay Card", "kind": "credit_card"}, &card)
	books := categoryID(t, o, "Shopping")
	if code := alice.do("PUT", fmt.Sprintf("/api/transactions/%d", r.ID), map[string]any{
		"kind": "expense", "amount": r.Amount, "account_id": card.ID, "category_id": books,
		"occurred_at": r.OccurredAt, "payee": r.Payee, "status": "confirmed",
	}, nil); code != 200 {
		t.Fatalf("confirm: %d", code)
	}
	o = overview(t, alice)
	for _, a := range o.Accounts {
		if a.ID == card.ID && a.Balance != -3280 {
			t.Errorf("card balance %d after confirming", a.Balance)
		}
	}

	// Next time the same card and shop are filled in, and with auto-confirm
	// the record goes straight in.
	alice.do("PUT", "/api/settings", map[string]any{"currency": "JPY", "auto_confirm": true}, nil)
	next := strings.Replace(string(fixture(t, "paypay-usage.eml")), "usage-1@", "usage-9@", 1)
	next = strings.Replace(next, "Sun, 11 Oct 2026", "Mon, 12 Oct 2026", 1)
	deliver(alice, v.Address, []byte(next), &e)
	var t2 wallet.Transaction
	var all []wallet.Transaction
	alice.do("GET", "/api/transactions", nil, &all)
	for _, x := range all {
		if e.TransactionID != nil && x.ID == *e.TransactionID {
			t2 = x
		}
	}
	if t2.AccountID != card.ID || t2.CategoryID == nil || *t2.CategoryID != books {
		t.Errorf("rules not applied: %+v", t2)
	}
	// Same card, same amount, within a day: a second purchase, not a duplicate.
	if t2.DuplicateOfID != nil || t2.Status != "confirmed" {
		t.Errorf("second purchase: status %s, duplicate_of %v", t2.Status, t2.DuplicateOfID)
	}
}

func TestInboundDuplicateOfManualRecord(t *testing.T) {
	ts := newTestServer(t, withInbound)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	var manual wallet.Transaction
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1820, "account_id": o.Accounts[0].ID,
		"occurred_at": time.Date(2026, 10, 5, 23, 0, 0, 0, time.UTC), "payee": "lunch"}, &manual)
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)
	var e inbox.Email
	deliver(c, v.Address, fixture(t, "paypay-nfc.eml"), &e)
	var review []wallet.Transaction
	c.do("GET", "/api/transactions?status=review", nil, &review)
	if len(review) != 1 || review[0].DuplicateOfID == nil || *review[0].DuplicateOfID != manual.ID || review[0].Note != "Tap to pay" {
		t.Errorf("want flagged as duplicate of %d: %+v", manual.ID, review)
	}
}

func TestInboundNonPaymentsAndForwarding(t *testing.T) {
	ts := newTestServer(t, withInbound)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)

	var e inbox.Email
	deliver(c, v.Address, fixture(t, "paypay-statement.eml"), &e)
	if e.Status != "ignored" || e.Detail == "" {
		t.Errorf("statement: %+v", e)
	}
	deliver(c, v.Address, fixture(t, "gmail-forwarding.eml"), &e)
	if e.Status != "forwarding" || e.Detail != "518432907" || !strings.HasPrefix(e.ConfirmURL, "https://mail-settings.google.com/") {
		t.Errorf("forwarding: %+v", e)
	}
	// Without an AI key, an unknown sender waits for the user.
	deliver(c, v.Address, fixture(t, "unknown-receipt.eml"), &e)
	if e.Status != "unparsed" || !strings.Contains(e.BodyText, "2,500 JPY") {
		t.Errorf("unknown: %+v", e)
	}
	if code := c.do("POST", fmt.Sprintf("/api/inbox/emails/%d/dismiss", e.ID), nil, nil); code != 204 {
		t.Errorf("dismiss: %d", code)
	}
	c.do("GET", "/api/inbox", nil, &v)
	if len(v.Emails) != 3 || !v.Emails[0].Dismissed {
		t.Errorf("emails %+v", v.Emails)
	}
	if overview(t, c).ReviewCount != 0 {
		t.Error("non-payments made records")
	}
}

func TestInboundRejects(t *testing.T) {
	ts := newTestServer(t, withInbound)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)
	raw := fixture(t, "paypay-usage.eml")
	if code := c.send("/api/inbound/email", "message/rfc822", raw, map[string]string{"Authorization": "Bearer wrong", "X-Inbound-To": v.Address}, nil); code != 401 {
		t.Errorf("wrong secret: %d", code)
	}
	if code := deliver(c, "pay-nobody@in.example.com", raw, nil); code != 404 {
		t.Errorf("unknown address: %d", code)
	}
	if code := deliver(c, v.Address, []byte("not an email"), nil); code != 400 {
		t.Errorf("garbage: %d", code)
	}
	// A new address replaces the old one.
	old := v.Address
	c.do("POST", "/api/inbox/address", nil, &v)
	if v.Address == old {
		t.Fatal("address not rotated")
	}
	if code := deliver(c, old, raw, nil); code != 404 {
		t.Errorf("old address still works: %d", code)
	}
	// Plus-addressing still reaches the user.
	if code := deliver(c, strings.Replace(v.Address, "@", "+gmail@", 1), raw, nil); code != 200 {
		t.Errorf("plus address: %d", code)
	}
}

func TestInboundDisabledWithoutSecret(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)
	if v.Address != "" {
		t.Errorf("address shown without an inbound domain: %q", v.Address)
	}
	if code := c.send("/api/inbound/email", "message/rfc822", fixture(t, "paypay-usage.eml"),
		map[string]string{"Authorization": "Bearer ", "X-Inbound-To": "pay-x@y"}, nil); code != 404 {
		t.Errorf("webhook without secret: %d, want 404", code)
	}
	// Uploading an email yourself works regardless.
	var e inbox.Email
	if code := c.send("/api/inbox/emails", "message/rfc822", fixture(t, "forwarded-by-hand.eml"), nil, &e); code != 200 || e.Status != "payment" {
		t.Errorf("upload: %d %+v", code, e)
	}
}

func TestRestoreEmailedRecord(t *testing.T) {
	ts := newTestServer(t, withInbound)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	var manual wallet.Transaction
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1820, "account_id": o.Accounts[0].ID,
		"occurred_at": time.Date(2026, 10, 5, 23, 0, 0, 0, time.UTC), "payee": "lunch"}, &manual)
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)
	var e inbox.Email
	deliver(c, v.Address, fixture(t, "paypay-nfc.eml"), &e)
	if e.TransactionID == nil {
		t.Fatalf("email made no record: %+v", e)
	}
	emailed := *e.TransactionID
	emailLink := func() *int64 {
		c.do("GET", "/api/inbox", nil, &v)
		for _, x := range v.Emails {
			if x.ID == e.ID {
				return x.TransactionID
			}
		}
		t.Fatal("email gone from the inbox")
		return nil
	}
	review := func() wallet.Transaction {
		var l []wallet.Transaction
		c.do("GET", "/api/transactions?status=review", nil, &l)
		if len(l) != 1 {
			t.Fatalf("%d records to review, want 1", len(l))
		}
		return l[0]
	}
	before := review()

	// The emailed record keeps where it came from and its email.
	c.do("DELETE", fmt.Sprintf("/api/transactions/%d", emailed), nil, nil)
	if emailLink() != nil {
		t.Fatal("email still linked to a deleted record")
	}
	if code := c.do("POST", "/api/transactions/restore", map[string]any{"ids": []int64{emailed}}, nil); code != 200 {
		t.Fatalf("restore: %d", code)
	}
	after := review()
	if after.ID != emailed || after.Source != before.Source || after.SourceLabel != before.SourceLabel || after.Status != "review" {
		t.Errorf("restored %+v, want %+v", after, before)
	}
	if l := emailLink(); l == nil || *l != emailed {
		t.Errorf("email links to %v, want %d", l, emailed)
	}

	// The record it duplicates: deleting it clears the flag, restoring it puts it back.
	c.do("DELETE", fmt.Sprintf("/api/transactions/%d", manual.ID), nil, nil)
	if review().DuplicateOfID != nil {
		t.Fatal("still flagged as a duplicate of a deleted record")
	}
	c.do("POST", "/api/transactions/restore", map[string]any{"ids": []int64{manual.ID}}, nil)
	if d := review().DuplicateOfID; d == nil || *d != manual.ID {
		t.Errorf("duplicate of %v, want %d", d, manual.ID)
	}
}
