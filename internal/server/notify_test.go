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
	vpass        = "com.smbc_card.vpass"
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

// A billed PayPay Card payment gives only its date. Notified days later, it
// is recorded at noon that day; notified the same day, at the notification's
// time.
func TestPayPayBilledNotification(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	billed := func(when time.Time, day string) wallet.Transaction {
		t.Helper()
		n := post(t, c, notify.Incoming{Package: payPay, When: when.UnixMilli(),
			Title: "支払いが完了しました", Text: "お店：ソフトバンクM\n金額：7,065円\n日時：" + day})[0]
		if n.Status != "payment" || n.TransactionID == nil {
			t.Fatalf("notification %+v", n)
		}
		for _, r := range records(t, c) {
			if r.ID == *n.TransactionID {
				return r
			}
		}
		t.Fatalf("no record %d", *n.TransactionID)
		return wallet.Transaction{}
	}
	late := billed(time.Date(2026, 10, 17, 22, 25, 0, 0, time.UTC), "2026年10月15日")
	if late.Payee != "ソフトバンクM" || late.Amount != 7065 || late.SourceLabel != "PayPay Card" ||
		!late.OccurredAt.Equal(time.Date(2026, 10, 15, 3, 0, 0, 0, time.UTC)) {
		t.Errorf("billed days later %+v", late)
	}
	when := time.Date(2026, 9, 15, 1, 30, 0, 0, time.UTC) // 10:30 in Japan
	if r := billed(when, "2026年9月15日"); !r.OccurredAt.Equal(when) {
		t.Errorf("billed the same day %+v", r)
	}
}

// PayPay can report a card payment twice: 支払いを受け付けました as it is
// made, then 支払いが完了しました, with only the date, hours or a day later.
// The second is the same payment, not a new one.
func TestPayPayCompletedAfterAccepted(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	accepted := post(t, c, notify.Incoming{Package: payPay, When: time.Date(2026, 10, 15, 1, 23, 0, 0, time.UTC).UnixMilli(),
		Title: "支払いを受け付けました", Text: "お店：ソフトバンクM\n金額：7,065円\n日時：2026年10月15日 10:23"})[0]
	completed := post(t, c, notify.Incoming{Package: payPay, When: time.Date(2026, 10, 16, 0, 40, 0, 0, time.UTC).UnixMilli(),
		Title: "支払いが完了しました", Text: "お店：ソフトバンクM\n金額：7,065円\n日時：2026年10月15日"})[0]
	if accepted.Status != "payment" || completed.Status != "matched" || *completed.TransactionID != *accepted.TransactionID {
		t.Fatalf("accepted %+v, completed %+v", accepted, completed)
	}
	if n := len(records(t, c)); n != 1 {
		t.Errorf("%d records, want 1", n)
	}
	// Another payment of the same amount that day is a payment of its own.
	again := post(t, c, notify.Incoming{Package: payPay, When: time.Date(2026, 10, 16, 0, 41, 0, 0, time.UTC).UnixMilli(),
		Title: "支払いが完了しました", Text: "お店：ソフトバンクM\n金額：7,065円\n日時：2026年10月15日"})[0]
	if again.Status != "payment" || *again.TransactionID == *accepted.TransactionID {
		t.Errorf("second completed %+v", again)
	}
}

// A PayPay Card payment abroad gives the amount in yen and what was charged.
// It is recorded in yen, with the foreign amount in the note.
func TestPayPayCardAbroad(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	n := post(t, c, notify.Incoming{Package: payPay, When: time.Date(2026, 11, 2, 23, 9, 0, 0, time.UTC).UnixMilli(),
		Title: "支払いを受け付けました", Text: "お店：Visa海外加盟店\n金額：3,477円（22 USD）\n日時：2026年11月3日 08:09"})[0]
	if n.Status != "payment" || n.TransactionID == nil {
		t.Fatalf("notification %+v", n)
	}
	r := records(t, c)[0]
	if r.ID != *n.TransactionID || r.Amount != 3477 || r.Payee != "Visa海外加盟店" || r.Note != "Charged 22 USD." {
		t.Errorf("record %+v", r)
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

// A restored record from a notification keeps where it came from, its
// notification, and the flag on the record it duplicates.
func TestRestoreNotifiedRecord(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	when := time.Date(2026, 10, 5, 23, 0, 0, 0, time.UTC)
	var manual wallet.Transaction
	c.do("POST", "/api/transactions", map[string]any{"kind": "expense", "amount": 1820, "account_id": o.Accounts[0].ID,
		"occurred_at": when.Add(-time.Hour), "payee": "lunch"}, &manual)
	n := post(t, c, notify.Incoming{Package: payPay, When: when.UnixMilli(), Title: "支払いを受け付けました",
		Text: "お店：大戸屋\n金額：1,820円\n日時：2026年10月6日 08:00"})[0]
	if n.Status != "payment" || n.TransactionID == nil {
		t.Fatalf("notification %+v", n)
	}
	notified := *n.TransactionID
	link := func() *int64 {
		var list notifications
		c.do("GET", "/api/notifications", nil, &list)
		return list.Notifications[0].TransactionID
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
	if before.DuplicateOfID == nil || *before.DuplicateOfID != manual.ID {
		t.Fatalf("want a possible duplicate of %d: %+v", manual.ID, before)
	}

	c.do("DELETE", fmt.Sprintf("/api/transactions/%d", notified), nil, nil)
	if link() != nil {
		t.Fatal("notification still linked to a deleted record")
	}
	if code := c.do("POST", "/api/transactions/restore", map[string]any{"ids": []int64{notified}}, nil); code != 200 {
		t.Fatalf("restore: %d", code)
	}
	after := review()
	if after.ID != notified || after.Source != "notification" || after.SourceLabel != before.SourceLabel || after.Status != "review" {
		t.Errorf("restored %+v, want %+v", after, before)
	}
	if l := link(); l == nil || *l != notified {
		t.Errorf("notification links to %v, want %d", l, notified)
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

// A Vpass alert (三井住友カード) is recorded at the time in its text, with
// the card's name, so the card can be given its own account.
func TestVpassNotification(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	got := post(t, c,
		notify.Incoming{Package: vpass, When: time.Date(2026, 11, 8, 1, 23, 10, 0, time.UTC).UnixMilli(),
			Title: "ご利用のお知らせ", Text: "◇ご利用カード：Ａｍａｚｏｎマスター　◇日時：2026/11/08 10:22　◇利用先：ＡＭＡＺＯＮ．ＣＯ．ＪＰ　◇金額：1,798円"},
		notify.Incoming{Package: vpass, When: time.Date(2026, 11, 8, 2, 0, 0, 0, time.UTC).UnixMilli(),
			Title: "キャンペーン", Text: "最大10,000円キャッシュバック！"},
	)
	if got[0].Status != "payment" || got[1].Status != "ignored" || got[0].App != "Vpass" {
		t.Fatalf("notifications %+v", got)
	}
	var r wallet.Transaction
	for _, x := range records(t, c) {
		if x.ID == *got[0].TransactionID {
			r = x
		}
	}
	if r.Payee != "AMAZON.CO.JP" || r.Amount != 1798 || r.Kind != "expense" || r.SourceLabel != "Vpass · Amazonマスター" ||
		!r.OccurredAt.Equal(time.Date(2026, 11, 8, 1, 22, 0, 0, time.UTC)) {
		t.Errorf("record %+v", r)
	}
}
