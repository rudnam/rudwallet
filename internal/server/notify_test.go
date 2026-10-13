package server_test

import (
	"fmt"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/inbox"
	"github.com/rudnam/wallet-app/internal/notify"
	"github.com/rudnam/wallet-app/internal/wallet"
)

const googleWallet = "com.google.android.apps.walletnfcrel"

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

// The paypay-usage.eml fixture's payment: ¥3,280 at 2026-10-11 13:21 JST.
var emailedPayment = time.Date(2026, 10, 11, 4, 21, 0, 0, time.UTC)

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

	// Confirming teaches the card's account and the shop's category, like an email.
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

// The phone's notification and the card's email are one payment, whichever
// arrives first.
func TestNotificationAndEmailAreOnePayment(t *testing.T) {
	ts := newTestServer(t, withInbound)
	c := newClient(t, ts)
	c.signup("a@example.com")
	var v inboxView
	c.do("GET", "/api/inbox", nil, &v)
	tap := func(at time.Time) notify.Incoming {
		return notify.Incoming{Package: googleWallet, When: at.UnixMilli(), Title: "TEST BOOKS", Text: "¥3,280 with Visa •••• 1234"}
	}

	// Notification first, email a few minutes later.
	n := post(t, c, tap(emailedPayment.Add(20*time.Second)))[0]
	var e inbox.Email
	deliver(c, v.Address, fixture(t, "paypay-usage.eml"), &e)
	if e.Status != "payment" || e.TransactionID == nil || *e.TransactionID != *n.TransactionID {
		t.Fatalf("email %+v not matched to notification %+v", e, n)
	}
	all := records(t, c)
	if len(all) != 1 || all[0].DuplicateOfID != nil {
		t.Fatalf("records %+v", all)
	}

	// Another user, the email first, then the notification.
	b := newClient(t, ts)
	b.signup("b@example.com")
	b.do("GET", "/api/inbox", nil, &v)
	deliver(b, v.Address, fixture(t, "paypay-usage.eml"), &e)
	if e.TransactionID == nil {
		t.Fatalf("email %+v", e)
	}
	n = post(t, b, tap(emailedPayment.Add(time.Minute)))[0]
	if n.Status != "matched" || n.TransactionID == nil || *n.TransactionID != *e.TransactionID {
		t.Errorf("notification %+v not matched to email %+v", n, e)
	}
	if got := len(records(t, b)); got != 1 {
		t.Errorf("%d records, want 1", got)
	}

	// Another tap of the same amount, with nothing left to match: a new
	// record, flagged since it's within a day of the other.
	n = post(t, b, tap(emailedPayment.Add(30*time.Minute)))[0]
	if n.Status != "payment" {
		t.Errorf("another payment %+v", n)
	}
	for _, x := range records(t, b) {
		if x.ID == *n.TransactionID && x.DuplicateOfID == nil {
			t.Errorf("another payment not flagged: %+v", x)
		}
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
