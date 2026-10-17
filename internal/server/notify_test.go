package server_test

import (
	"fmt"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/notify"
	"github.com/rudnam/wallet-app/internal/wallet"
)

const (
	googleWallet = "com.google.android.apps.walletnfcrel"
	payPay       = "jp.ne.paypay.android.app"
)

type notifications struct {
	Notifications []notify.Notification `json:"notifications"`
}

// post sends notifications as the phone would and returns what each became.
func post(t *testing.T, c *client, ns ...notify.Incoming) []notify.Notification {
	t.Helper()
	var out notifications
	if code := c.do("POST", "/api/notifications", map[string]any{"notifications": ns}, &out); code != 200 {
		t.Fatalf("post notifications: %d", code)
	}
	if len(out.Notifications) != len(ns) {
		t.Fatalf("got %d results for %d notifications", len(out.Notifications), len(ns))
	}
	return out.Notifications
}

func records(t *testing.T, c *client) []wallet.Transaction {
	t.Helper()
	var all []wallet.Transaction
	c.do("GET", "/api/transactions", nil, &all)
	return all
}

func TestNotificationPayment(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)

	when := time.Date(2026, 10, 12, 3, 0, 0, 0, time.UTC)
	tap := notify.Incoming{Package: googleWallet, When: when.UnixMilli(), Title: "Lawson", Text: "¥550 with Visa •••• 1234"}
	got := post(t, alice, tap, notify.Incoming{Package: googleWallet, When: when.UnixMilli() + 1, Title: "Card added", Text: "Visa •••• 1234 is ready"})
	if got[0].Status != "payment" || got[0].TransactionID == nil || got[0].App != "Google Wallet" {
		t.Fatalf("payment notification %+v", got[0])
	}
	if got[1].Status != "ignored" || got[1].TransactionID != nil {
		t.Errorf("non-payment notification %+v", got[1])
	}
	all := records(t, alice)
	if len(all) != 1 {
		t.Fatalf("records %+v", all)
	}
	r := all[0]
	if r.Amount != 550 || r.Payee != "Lawson" || r.Status != "review" || r.Source != "notification" ||
		r.SourceLabel != "Google Wallet · Visa ••1234" || r.AccountID != o.Accounts[0].ID || !r.OccurredAt.Equal(when) {
		t.Errorf("record %+v", r)
	}

	// The phone sends it again (a retry, or the app updated the
	// notification): still one record.
	again := post(t, alice, tap)
	if !again[0].Duplicate || again[0].ID != got[0].ID {
		t.Errorf("resent notification %+v", again[0])
	}
	if n := len(records(t, alice)); n != 1 {
		t.Errorf("%d records after resending", n)
	}

	// A second payment of the same amount a minute later is a second record.
	second := post(t, alice, notify.Incoming{Package: googleWallet, When: when.Add(time.Minute).UnixMilli(), Title: "Lawson", Text: "¥550 with Visa •••• 1234"})
	if second[0].Status != "payment" || *second[0].TransactionID == *got[0].TransactionID {
		t.Errorf("second payment %+v", second[0])
	}

	// Confirming teaches the card's account and the shop's category.
	var card wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "PayPay Card", "kind": "credit_card"}, &card)
	food := categoryID(t, o, "Food & Drinks")
	if code := alice.do("PUT", fmt.Sprintf("/api/transactions/%d", r.ID), map[string]any{
		"kind": "expense", "amount": r.Amount, "account_id": card.ID, "category_id": food,
		"occurred_at": r.OccurredAt, "payee": r.Payee, "status": "confirmed",
	}, nil); code != 200 {
		t.Fatalf("confirm: %d", code)
	}
	third := post(t, alice, notify.Incoming{Package: googleWallet, When: when.Add(48 * time.Hour).UnixMilli(), Title: "Lawson", Text: "¥300 with Visa •••• 1234"})
	for _, x := range records(t, alice) {
		if x.ID == *third[0].TransactionID && (x.AccountID != card.ID || x.CategoryID == nil || *x.CategoryID != food) {
			t.Errorf("rules not applied: %+v", x)
		}
	}

	// The list is the user's own.
	var list notifications
	alice.do("GET", "/api/notifications", nil, &list)
	if len(list.Notifications) != 4 || list.Notifications[0].ID != third[0].ID {
		t.Errorf("alice's notifications %+v", list.Notifications)
	}
	bob.do("GET", "/api/notifications", nil, &list)
	if len(list.Notifications) != 0 {
		t.Errorf("bob sees %+v", list.Notifications)
	}
	// Bob sending the same notification gets his own record.
	if b := post(t, bob, tap); b[0].Duplicate || b[0].Status != "payment" {
		t.Errorf("bob's notification %+v", b[0])
	}
	if overview(t, alice).ReviewCount != 2 { // the second and third; the first is confirmed
		t.Error("bob's notification reached alice")
	}
}

// PayPay's notifications: a card payment and a balance payment learn their
// accounts apart, and an offer is not a payment.
func TestPayPayNotifications(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	got := post(t, c,
		notify.Incoming{Package: payPay, When: time.Date(2026, 10, 16, 11, 19, 30, 0, time.UTC).UnixMilli(),
			Title: "支払いを受け付けました", Text: "お店：ベルマート豊橋店\n金額：1,291円\n日時：2026年10月16日 20:19"},
		notify.Incoming{Package: payPay, When: time.Date(2026, 10, 16, 12, 0, 0, 0, time.UTC).UnixMilli(),
			Title: "PayPay", Text: "Uber Eatsへの支払いが完了しました。\n金額: 3,994円\n取引番号: 05093583565634363396"},
		notify.Incoming{Package: payPay, When: time.Date(2026, 10, 16, 13, 0, 0, 0, time.UTC).UnixMilli(),
			Title: "PayPay", Text: "今だけ最大10%戻ってくる！"},
	)
	if got[0].Status != "payment" || got[1].Status != "payment" || got[2].Status != "ignored" || got[0].App != "PayPay" {
		t.Fatalf("notifications %+v", got)
	}
	byID := map[int64]wallet.Transaction{}
	for _, r := range records(t, c) {
		byID[r.ID] = r
	}
	card, balance := byID[*got[0].TransactionID], byID[*got[1].TransactionID]
	// The card payment's time is the one in its text, in Japan time.
	if card.Payee != "ベルマート豊橋店" || card.Amount != 1291 || card.SourceLabel != "PayPay Card" ||
		!card.OccurredAt.Equal(time.Date(2026, 10, 16, 11, 19, 0, 0, time.UTC)) {
		t.Errorf("card payment %+v", card)
	}
	if balance.Payee != "Uber Eats" || balance.Amount != 3994 || balance.SourceLabel != "PayPay" {
		t.Errorf("balance payment %+v", balance)
	}
}

func TestNotificationMatchesManualRecord(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	when := time.Date(2026, 10, 12, 3, 0, 0, 0, time.UTC)
	var manual wallet.Transaction
	c.do("POST", "/api/transactions", map[string]any{
		"kind": "expense", "amount": 550, "account_id": o.Accounts[0].ID, "occurred_at": when, "payee": "Lawson",
	}, &manual)
	n := post(t, c, notify.Incoming{Package: googleWallet, When: when.Add(2 * time.Minute).UnixMilli(), Title: "Lawson", Text: "¥550"})[0]
	if n.Status != "matched" || *n.TransactionID != manual.ID {
		t.Errorf("notification %+v", n)
	}
	// Each record matches one notification: a second tap is a second payment.
	n = post(t, c, notify.Incoming{Package: googleWallet, When: when.Add(3 * time.Minute).UnixMilli(), Title: "Lawson", Text: "¥550"})[0]
	if n.Status != "payment" || *n.TransactionID == manual.ID {
		t.Errorf("second notification %+v", n)
	}
}

func TestNotificationRejects(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	if code := c.do("POST", "/api/notifications", map[string]any{"notifications": []notify.Incoming{}}, nil); code != 401 {
		t.Errorf("signed out: %d", code)
	}
	c.signup("a@example.com")
	if code := c.do("POST", "/api/notifications", map[string]any{"notifications": []notify.Incoming{{Title: "x"}}}, nil); code != 400 {
		t.Errorf("no package: %d", code)
	}
	many := make([]notify.Incoming, 51)
	for i := range many {
		many[i] = notify.Incoming{Package: googleWallet, When: int64(i + 1)}
	}
	if code := c.do("POST", "/api/notifications", map[string]any{"notifications": many}, nil); code != 413 {
		t.Errorf("too many: %d", code)
	}
}

// Email automation and users' own Claude keys are gone; older Android builds
// still ask about them, and get an empty answer rather than an error.
func TestRemovedFeaturesAnswerEmpty(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	if code := c.do("GET", "/api/inbox", nil, nil); code != 401 {
		t.Errorf("signed out: %d", code)
	}
	c.signup("a@example.com")
	var inbox struct {
		Address string `json:"address"`
		Emails  []any  `json:"emails"`
	}
	if code := c.do("GET", "/api/inbox", nil, &inbox); code != 200 || inbox.Address != "" || inbox.Emails == nil || len(inbox.Emails) != 0 {
		t.Errorf("inbox: %d %+v", code, inbox)
	}
	var ai struct {
		CanSave bool `json:"can_save"`
		HasKey  bool `json:"has_key"`
	}
	if code := c.do("GET", "/api/ai", nil, &ai); code != 200 || ai.CanSave || ai.HasKey {
		t.Errorf("ai: %d %+v", code, ai)
	}
	if code := c.send("/api/inbound/email", "message/rfc822", []byte("Subject: hi\r\n\r\nhello"), nil, nil); code != 404 {
		t.Errorf("inbound email: %d, want 404", code)
	}
}
