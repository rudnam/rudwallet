package server_test

import (
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/config"
	"github.com/rudnam/wallet-app/internal/widget"
)

// The Android app runs the web app from https://localhost and signs in with
// a bearer token instead of a cookie (internal/account/app.go).

const appOrigin = "https://localhost"

// appClient is the Android app: no cookie jar, its own origin, and its
// session token once it has one.
func appClient(t *testing.T, base string) *client {
	return &client{t: t, base: base, http: &http.Client{}, headers: map[string]string{
		"Origin": appOrigin, "X-Wallet-Client": "android",
	}}
}

// signInApp signs the app in with a password and keeps the token.
func signInApp(t *testing.T, c *client, email string) {
	t.Helper()
	var me struct{ Email, Token string }
	if code := c.do("POST", "/api/login", map[string]string{"email": email, "password": password}, &me); code != 200 || me.Token == "" {
		t.Fatalf("app login: %d %+v", code, me)
	}
	c.headers["Authorization"] = "Bearer " + me.Token
}

func TestAppSignIn(t *testing.T) {
	ts := newTestServer(t)
	newClient(t, ts).signup("alice@example.com")

	// Signing in answers with a token and sets no cookie.
	app := appClient(t, ts.URL)
	signInApp(t, app, "alice@example.com")
	var me struct{ Email, Token string }
	if code := app.do("GET", "/api/me", nil, &me); code != 200 || me.Email != "alice@example.com" || me.Token != "" {
		t.Fatalf("me with the token: %d %+v", code, me)
	}
	// It can change data: the token isn't a cookie, so no CSRF check applies.
	if code := app.do("POST", "/api/accounts", map[string]any{"name": "Card", "kind": "credit_card"}, nil); code != 201 {
		t.Errorf("create account from the app: %d", code)
	}

	// A browser signing in still gets a cookie and no token.
	web := newClient(t, ts)
	if code := web.do("POST", "/api/login", map[string]string{"email": "alice@example.com", "password": password}, &me); code != 200 || me.Token != "" {
		t.Errorf("web login: %d token %q", code, me.Token)
	}

	// Signing out ends the app's session.
	if code := app.do("POST", "/api/logout", nil, nil); code != 204 {
		t.Errorf("logout: %d", code)
	}
	if code := app.do("GET", "/api/me", nil, nil); code != 401 {
		t.Errorf("me after logout: %d", code)
	}
	if code := web.do("GET", "/api/me", nil, nil); code != 200 {
		t.Errorf("the browser's session ended too: %d", code)
	}
}

func TestAppCORS(t *testing.T) {
	ts := newTestServer(t)
	preflight := func(origin string) *http.Response {
		req, _ := http.NewRequest("OPTIONS", ts.URL+"/api/login", nil)
		req.Header.Set("Origin", origin)
		req.Header.Set("Access-Control-Request-Method", "POST")
		req.Header.Set("Access-Control-Request-Headers", "content-type, x-wallet-client")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp
	}
	resp := preflight(appOrigin)
	h := resp.Header
	if resp.StatusCode != 204 || h.Get("Access-Control-Allow-Origin") != appOrigin ||
		h.Get("Access-Control-Allow-Credentials") != "" || h.Get("Access-Control-Allow-Headers") == "" {
		t.Errorf("app preflight: %d %v", resp.StatusCode, h)
	}
	// Any other site gets no CORS headers, and can't send cookies with data changes.
	if resp := preflight("https://evil.example"); resp.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("other site preflight: %v", resp.Header)
	}
	evil := newClient(t, ts)
	evil.signup("bob@example.com")
	evil.headers["Origin"] = "https://evil.example"
	if code := evil.do("POST", "/api/accounts", map[string]any{"name": "X", "kind": "cash"}, nil); code != 403 {
		t.Errorf("cross-site request with a cookie: %d", code)
	}
}

func challengeFor(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func withAndroid(c *config.Config) {
	c.AndroidPackage = "com.example.wallet"
	c.AndroidCertFingerprints = []string{"AA:BB"}
}

// appGoogleSignIn runs Google sign-in as the app does, in the phone's
// browser, and returns where the browser is sent at the end.
func appGoogleSignIn(t *testing.T, ts string, g *fakeGoogle, verifier string) *url.URL {
	t.Helper()
	browser := appClient(t, ts)
	browser.headers = map[string]string{}
	jar, _ := cookiejar.New(nil)
	browser.http.Jar = jar
	browser.http.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := browser.http.Get(ts + "/api/auth/google?app_challenge=" + challengeFor(verifier))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	to, _ := url.Parse(resp.Header.Get("Location"))
	g.claims = map[string]any{
		"iss": "https://accounts.google.com", "aud": "client-id", "exp": time.Now().Add(time.Hour).Unix(),
		"sub": "sub-app", "email": "grace@gmail.com", "email_verified": true, "nonce": to.Query().Get("nonce"),
	}
	resp, err = browser.http.Get(ts + "/api/auth/google/callback?code=the-code&state=" + url.QueryEscape(to.Query().Get("state")))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	back, _ := url.Parse(resp.Header.Get("Location"))
	// The phone's browser got no session of its own.
	if code := browser.do("GET", "/api/me", nil, nil); code != 401 {
		t.Errorf("browser signed in by the app's Google sign-in: %d", code)
	}
	return back
}

func TestAppGoogleSignIn(t *testing.T) {
	ts, g := newGoogleServer(t, withAndroid)
	back := appGoogleSignIn(t, ts.URL, g, "the app's secret")
	code := back.Query().Get("code")
	if back.Scheme != "com.example.wallet" || back.Host != "auth" || code == "" {
		t.Fatalf("callback sent the browser to %s", back)
	}

	// The code needs the secret behind the challenge, and works once.
	app := appClient(t, ts.URL)
	if status := app.do("POST", "/api/auth/app-token", map[string]string{"code": code, "verifier": "wrong"}, nil); status != 400 {
		t.Errorf("wrong secret: %d", status)
	}
	if status := app.do("POST", "/api/auth/app-token", map[string]string{"code": code, "verifier": "the app's secret"}, nil); status != 400 {
		t.Errorf("code reused after a wrong secret: %d", status)
	}
	code = appGoogleSignIn(t, ts.URL, g, "the app's secret").Query().Get("code")
	var me struct{ Email, Token string }
	if status := app.do("POST", "/api/auth/app-token", map[string]string{"code": code, "verifier": "the app's secret"}, &me); status != 200 || me.Token == "" || me.Email != "grace@gmail.com" {
		t.Fatalf("app token: %d %+v", status, me)
	}
	app.headers["Authorization"] = "Bearer " + me.Token
	if status := app.do("GET", "/api/me", nil, nil); status != 200 {
		t.Errorf("me with the token: %d", status)
	}

	// A refusal goes back to the app too, with the reason.
	g.claims = nil
	browser := newClient(t, ts)
	browser.http.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, _ := browser.http.Get(ts.URL + "/api/auth/google?app_challenge=" + challengeFor("x"))
	resp.Body.Close()
	resp, _ = browser.http.Get(ts.URL + "/api/auth/google/callback?error=access_denied&state=whatever")
	resp.Body.Close()
	if loc := resp.Header.Get("Location"); loc != "com.example.wallet://auth?signin_error=cancelled" {
		t.Errorf("cancelled sign-in sent the browser to %q", loc)
	}

	// Without ANDROID_PACKAGE there is no app to send it back to.
	ts, _ = newGoogleServer(t)
	resp, _ = http.Get(ts.URL + "/api/auth/google?app_challenge=" + challengeFor("x"))
	resp.Body.Close()
	if resp.StatusCode != 400 {
		t.Errorf("app sign-in without ANDROID_PACKAGE: %d", resp.StatusCode)
	}
}

func TestWidgetSummary(t *testing.T) {
	ts := newTestServer(t)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")
	newClient(t, ts).signup("bob@example.com")
	o := overview(t, alice)
	cat := o.Categories[0].ID
	cash := o.Accounts[0].ID

	month := time.Date(2026, 10, 1, 0, 0, 0, 0, time.FixedZone("JST", 9*3600))
	next := month.AddDate(0, 1, 0)
	add := func(kind string, amount int64, at time.Time, category *int64) {
		t.Helper()
		body := map[string]any{"kind": kind, "amount": amount, "account_id": cash, "category_id": category, "occurred_at": at}
		if code := alice.do("POST", "/api/transactions", body, nil); code != 201 {
			t.Fatalf("add record: %d", code)
		}
	}
	add("expense", 1200, month.Add(time.Hour), &cat)
	add("expense", 800, month.AddDate(0, 0, 10), nil)
	add("income", 5000, month.AddDate(0, 0, 2), nil)
	add("expense", 999, month.Add(-time.Minute), &cat) // last month, in Japan
	alice.do("POST", "/api/budgets", map[string]any{"name": "Food", "category_id": cat, "amount": 3000}, nil)
	alice.do("POST", "/api/budgets", map[string]any{"name": "Everything", "amount": 10000}, nil)

	summary := func(c *client) (widget.Summary, int) {
		var s widget.Summary
		q := url.Values{"from": {month.Format(time.RFC3339)}, "to": {next.Format(time.RFC3339)}}
		return s, c.do("GET", "/api/widget?"+q.Encode(), nil, &s)
	}

	// The widgets use the app's token.
	app := appClient(t, ts.URL)
	signInApp(t, app, "alice@example.com")
	s, code := summary(app)
	if code != 200 {
		t.Fatalf("summary: %d", code)
	}
	want := widget.Budget{Name: "Everything", Amount: 10000, Spent: 2000}
	if s.Currency != "JPY" || s.Spent != 2000 || s.Balance != 5000-2000-999 || s.Budget == nil || *s.Budget != want || s.ReviewCount != 0 {
		t.Errorf("summary %+v, budget %+v", s, s.Budget)
	}
	if fmt.Sprint(s.Budgets) != fmt.Sprint([]widget.Budget{{Name: "Food", Amount: 3000, Spent: 1200}, want}) {
		t.Errorf("budgets %+v", s.Budgets)
	}

	// Bob sees only his own month.
	bob := appClient(t, ts.URL)
	signInApp(t, bob, "bob@example.com")
	if s, code := summary(bob); code != 200 || s.Spent != 0 || s.Budget != nil || len(s.Budgets) != 0 {
		t.Errorf("bob's summary: %d %+v", code, s)
	}
	if _, code := summary(appClient(t, ts.URL)); code != 401 {
		t.Errorf("summary signed out: %d", code)
	}
}
