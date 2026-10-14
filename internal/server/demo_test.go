package server_test

import (
	"testing"

	"github.com/rudnam/wallet-app/internal/config"
)

func withDemo(c *config.Config) {
	c.Demo = true
	c.InboundDomain, c.InboundSecret = "in.example.com", "an inbound secret of 24+ chars"
}

type meJSON struct {
	Email string `json:"email"`
	Demo  bool   `json:"demo"`
}

func startDemo(t *testing.T, c *client, lang string) meJSON {
	t.Helper()
	c.headers["Accept-Language"] = lang
	var me meJSON
	if code := c.do("POST", "/api/demo", map[string]string{"tz": "Asia/Tokyo"}, &me); code != 201 {
		t.Fatalf("POST /api/demo: %d", code)
	}
	if !me.Demo {
		t.Fatalf("not a demo: %+v", me)
	}
	return me
}

func TestDemoInJapanese(t *testing.T) {
	ts := newTestServer(t, withDemo)
	c := newClient(t, ts)
	var opts struct{ Demo bool }
	if c.do("GET", "/api/auth/options", nil, &opts); !opts.Demo {
		t.Fatal("options don't offer the demo")
	}
	startDemo(t, c, "ja")

	o := overview(t, c)
	if len(o.Accounts) != 4 || o.Accounts[0].Name != "現金" {
		t.Fatalf("accounts %+v", o.Accounts)
	}
	categoryID(t, o, "食費")
	if o.ReviewCount != 2 {
		t.Errorf("review count %d, want 2", o.ReviewCount)
	}
	// The emailed payments are on the card, as real ones would be.
	var review []struct {
		AccountID int64 `json:"account_id"`
	}
	c.do("GET", "/api/transactions?status=review", nil, &review)
	for _, r := range review {
		if r.AccountID != *o.Settings.DefaultAccountID || r.AccountID == o.Accounts[0].ID {
			t.Errorf("a payment to review is in account %d, not the card", r.AccountID)
		}
	}
	var records []struct{ Payee string }
	c.do("GET", "/api/transactions", nil, &records)
	if len(records) < 200 {
		t.Errorf("%d records; want months of them", len(records))
	}
	var budgets []struct{ Name string }
	if c.do("GET", "/api/budgets", nil, &budgets); len(budgets) != 3 || budgets[0].Name != "全体" {
		t.Errorf("budgets %+v", budgets)
	}
}

func TestDemoInEnglish(t *testing.T) {
	ts := newTestServer(t, withDemo)
	c := newClient(t, ts)
	startDemo(t, c, "en-US,en;q=0.9")
	o := overview(t, c)
	names := map[string]bool{}
	for _, a := range o.Accounts {
		names[a.Name] = true
	}
	if !names["Cash"] || !names["MUFG"] || !names["PayPay Card"] {
		t.Errorf("accounts %v", names)
	}
	categoryID(t, o, "Food & Drinks")
}

// A demo can't reach outside the app: no forwarding address, API keys,
// access tokens or test emails (which could use the server's Claude key).
func TestDemoCantReachOutside(t *testing.T) {
	ts := newTestServer(t, withDemo)
	c := newClient(t, ts)
	startDemo(t, c, "en")
	var inbox struct{ Address string }
	if c.do("GET", "/api/inbox", nil, &inbox); inbox.Address != "" {
		t.Errorf("a demo got the address %q", inbox.Address)
	}
	for _, call := range []struct {
		method, path string
		body         any
	}{
		{"POST", "/api/inbox/address", nil},
		{"PUT", "/api/ai/key", map[string]string{"key": "sk-ant-demo-0000"}},
		{"POST", "/api/mcp/tokens", map[string]string{"name": "Claude"}},
	} {
		if code := c.do(call.method, call.path, call.body, nil); code != 403 {
			t.Errorf("%s %s in a demo: %d, want 403", call.method, call.path, code)
		}
	}
	if code := c.send("/api/inbox/emails", "message/rfc822", []byte("Subject: hi\r\n\r\nhello"), nil, nil); code != 403 {
		t.Errorf("uploading an email in a demo: %d, want 403", code)
	}

	// A real account can do these.
	real := newClient(t, ts)
	real.signup("real@example.com")
	if real.do("GET", "/api/inbox", nil, &inbox); inbox.Address == "" {
		t.Error("a real account got no address")
	}
}

func TestDemoIsDeleted(t *testing.T) {
	ts, conn := newTestServerDB(t, withDemo)
	demos := func() (n int) {
		t.Helper()
		if err := conn.QueryRow(`SELECT count(*) FROM users WHERE demo_expires_at IS NOT NULL`).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}

	// Ending a demo deletes it.
	c := newClient(t, ts)
	startDemo(t, c, "en")
	if c.do("POST", "/api/logout", nil, nil); demos() != 0 {
		t.Errorf("%d demos left after signing out", demos())
	}

	// One whose day is up goes when the next demo starts.
	startDemo(t, newClient(t, ts), "en")
	if _, err := conn.Exec(`UPDATE users SET demo_expires_at = now() - interval '1 minute'`); err != nil {
		t.Fatal(err)
	}
	startDemo(t, newClient(t, ts), "en")
	if n := demos(); n != 1 {
		t.Errorf("%d demos; the expired one should be gone", n)
	}
	var orphans int
	conn.QueryRow(`SELECT count(*) FROM transactions WHERE user_id NOT IN (SELECT id FROM users)`).Scan(&orphans)
	if orphans != 0 {
		t.Errorf("%d records outlived their demo", orphans)
	}
}

func TestDemoCanBeOff(t *testing.T) {
	ts := newTestServer(t) // Demo false
	c := newClient(t, ts)
	var opts struct{ Demo bool }
	if c.do("GET", "/api/auth/options", nil, &opts); opts.Demo {
		t.Error("options offer a demo that is off")
	}
	if code := c.do("POST", "/api/demo", map[string]string{}, nil); code != 404 {
		t.Errorf("POST /api/demo when off: %d, want 404", code)
	}
	// Nobody can sign up with a demo's made-up address.
	if code := c.do("POST", "/api/signup", map[string]string{"email": "demo-1@demo.invalid", "password": password}, nil); code != 400 {
		t.Errorf("sign-up as a demo address: %d, want 400", code)
	}
}
