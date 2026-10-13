package server_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
	"time"

	"github.com/rudnam/wallet-app/internal/config"
	"github.com/rudnam/wallet-app/internal/inbox"
	"github.com/rudnam/wallet-app/internal/server"
	"github.com/rudnam/wallet-app/internal/testdb"
	"github.com/rudnam/wallet-app/internal/wallet"
)

const goodKey = "sk-ant-api03-good-test-key-1234"

// fakeClaude stands in for the Anthropic API: it accepts goodKey only,
// sorts payees by keyword into the categories it is sent, and reads any
// email as a ¥980 payment. It keeps the keys it saw.
type fakeClaude struct {
	mu   sync.Mutex
	keys []string
}

var sortRules = map[string]string{"セブン": "Food & Drinks", "スギ薬局": "Health", "JR": "Transport"}

func (f *fakeClaude) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	key := r.Header.Get("X-Api-Key")
	f.mu.Lock()
	f.keys = append(f.keys, key)
	f.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	if key != goodKey || r.Header.Get("Authorization") != "" {
		w.WriteHeader(401)
		io.WriteString(w, `{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}`)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/models/") {
		json.NewEncoder(w).Encode(map[string]any{"type": "model", "id": strings.TrimPrefix(r.URL.Path, "/v1/models/"),
			"display_name": "Claude", "created_at": "2026-01-01T00:00:00Z"})
		return
	}
	var req struct {
		System   []struct{ Text string }
		Messages []struct{ Content []struct{ Text string } }
	}
	json.NewDecoder(r.Body).Decode(&req)
	text := req.Messages[0].Content[0].Text
	var answer any
	if strings.Contains(req.System[0].Text, "categor") {
		var cats []struct {
			ID   int64
			Name string
		}
		var items []struct {
			Item  int
			Payee string
		}
		json.Unmarshal([]byte(between(text, "<categories>", "</categories>")), &cats)
		json.Unmarshal([]byte(between(text, "<items>", "</items>")), &items)
		answers := []map[string]any{}
		for _, it := range items {
			var id int64
			for word, name := range sortRules {
				for _, c := range cats {
					if strings.Contains(it.Payee, word) && c.Name == name {
						id = c.ID
					}
				}
			}
			answers = append(answers, map[string]any{"item": it.Item, "category_id": id})
		}
		answer = map[string]any{"answers": answers}
	} else {
		answer = map[string]any{"is_payment": true, "refund": false, "amount": "980", "currency": "JPY",
			"merchant": "Example Books", "occurred_at": "", "payment_method": ""}
	}
	out, _ := json.Marshal(answer)
	json.NewEncoder(w).Encode(map[string]any{
		"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5", "stop_reason": "end_turn",
		"content": []map[string]any{{"type": "text", "text": string(out)}},
		"usage":   map[string]any{"input_tokens": 10, "output_tokens": 10},
	})
}

func between(s, start, end string) string {
	_, s, _ = strings.Cut(s, start)
	s, _, _ = strings.Cut(s, end)
	return s
}

const secretsKey = "test-secrets-key-0123456789abcdefghij"

func withSecrets(c *config.Config) { c.SecretsKey = secretsKey }

func withFakeClaude(t *testing.T) *fakeClaude {
	f := &fakeClaude{}
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	t.Setenv("ANTHROPIC_BASE_URL", ts.URL)
	// The server's own credentials must never reach Claude on a user's behalf.
	t.Setenv("ANTHROPIC_AUTH_TOKEN", "server-token")
	return f
}

type aiStatus struct {
	CanSave       bool   `json:"can_save"`
	HasKey        bool   `json:"has_key"`
	KeyUnreadable bool   `json:"key_unreadable"`
	KeyHint       string `json:"key_hint"`
	Uncategorized int    `json:"uncategorized"`
}

type suggestion struct {
	wallet.PayeeGroup
	CategoryID *int64 `json:"category_id"`
}

type suggestions struct {
	Suggestions []suggestion `json:"suggestions"`
	Remaining   int          `json:"remaining"`
}

func TestClaudeKey(t *testing.T) {
	fake := withFakeClaude(t)
	ts := newTestServer(t, withSecrets)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")

	var st aiStatus
	if code := alice.do("GET", "/api/ai", nil, &st); code != 200 || st.HasKey {
		t.Fatalf("status %d %+v", code, st)
	}
	for _, bad := range []string{"", "hello", "sk-ant-has space"} {
		if code := alice.do("PUT", "/api/ai/key", map[string]string{"key": bad}, nil); code != 400 {
			t.Errorf("key %q: %d", bad, code)
		}
	}
	var e struct{ Error string }
	if code := alice.do("PUT", "/api/ai/key", map[string]string{"key": "sk-ant-api03-revoked"}, &e); code != 400 || !strings.Contains(e.Error, "didn't accept") {
		t.Errorf("rejected key: %d %q", code, e.Error)
	}
	if code := alice.do("PUT", "/api/ai/key", map[string]string{"key": " " + goodKey + " "}, &st); code != 200 || !st.HasKey || st.KeyHint != "1234" {
		t.Fatalf("save: %d %+v", code, st)
	}
	// The key itself is never sent back.
	var raw map[string]any
	alice.do("GET", "/api/ai", nil, &raw)
	if b, _ := json.Marshal(raw); strings.Contains(string(b), "good-test-key") {
		t.Errorf("key leaked: %s", b)
	}
	if code := alice.do("DELETE", "/api/ai/key", nil, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	if alice.do("GET", "/api/ai", nil, &st); st.HasKey {
		t.Error("key still there")
	}
	if len(fake.keys) == 0 {
		t.Error("the key was not checked with Claude")
	}
}

func TestClaudeSortsUncategorizedRecords(t *testing.T) {
	withFakeClaude(t)
	ts := newTestServer(t, withSecrets)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	add := func(payee, note string, amount int64, cat *int64) {
		t.Helper()
		in := map[string]any{"kind": "expense", "amount": amount, "account_id": cash, "category_id": cat,
			"occurred_at": time.Now(), "payee": payee, "note": note}
		if code := alice.do("POST", "/api/transactions", in, nil); code != 201 {
			t.Fatalf("add %s: %d", payee, code)
		}
	}
	add("セブン－イレブン 知立店", "", 429, nil)
	add("ｾﾌﾞﾝ－ｲﾚﾌﾞﾝ 知立店", "", 300, nil) // a different spelling isn't the same payee
	add("セブン－イレブン 知立店 ", "", 500, nil)
	add("スギ薬局", "", 1025, nil)
	add("", "JR 定期券", 12000, nil)
	add("Mystery Ltd", "", 100, nil)
	add("", "", 50, nil) // nothing to go on: left out
	shopping := categoryID(t, o, "Shopping")
	add("Amazon", "", 2000, &shopping)

	// Without a key, nothing is sent to Claude.
	if code := alice.do("POST", "/api/ai/categorize", nil, nil); code != 409 {
		t.Fatalf("no key: %d", code)
	}
	alice.do("PUT", "/api/ai/key", map[string]string{"key": goodKey}, nil)
	var st aiStatus
	if alice.do("GET", "/api/ai", nil, &st); st.Uncategorized != 6 {
		t.Errorf("uncategorized %d, want 6", st.Uncategorized)
	}

	var s suggestions
	if code := alice.do("POST", "/api/ai/categorize", nil, &s); code != 200 {
		t.Fatalf("categorize: %d", code)
	}
	got := map[string]*int64{}
	for _, g := range s.Suggestions {
		got[g.Payee] = g.CategoryID
	}
	food, health, transport := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Health"), categoryID(t, o, "Transport")
	if len(s.Suggestions) != 5 || s.Suggestions[0].Payee != "セブン－イレブン 知立店" || s.Suggestions[0].Count != 2 || s.Suggestions[0].Total != 929 {
		t.Fatalf("groups %+v", s.Suggestions)
	}
	if *got["セブン－イレブン 知立店"] != food || *got["スギ薬局"] != health || *got["JR 定期券"] != transport || got["Mystery Ltd"] != nil {
		t.Errorf("suggestions %+v", s.Suggestions)
	}
	// Skipping the first payee.
	var rest suggestions
	alice.do("POST", "/api/ai/categorize?offset=1", nil, &rest)
	if len(rest.Suggestions) != 4 || rest.Suggestions[0].Payee == "セブン－イレブン 知立店" {
		t.Errorf("offset: %+v", rest.Suggestions)
	}

	// Nothing has changed yet; applying files the records.
	apply := func(c *client, items []wallet.PayeeCategory) (int, int) {
		var res struct{ Updated int }
		code := c.do("POST", "/api/ai/categorize/apply", map[string]any{"items": items}, &res)
		return code, res.Updated
	}
	if code, n := apply(alice, []wallet.PayeeCategory{
		{Payee: "セブン－イレブン 知立店", Kind: "expense", CategoryID: food},
		{Payee: "JR 定期券", Kind: "expense", CategoryID: transport},
	}); code != 200 || n != 3 {
		t.Fatalf("apply: %d, %d records", code, n)
	}
	if alice.do("GET", "/api/ai", nil, &st); st.Uncategorized != 3 {
		t.Errorf("uncategorized after apply %d, want 3", st.Uncategorized)
	}
	// A wrong kind of category, or someone else's, is refused.
	salary := categoryID(t, o, "Salary")
	if code, _ := apply(alice, []wallet.PayeeCategory{{Payee: "スギ薬局", Kind: "expense", CategoryID: salary}}); code != 400 {
		t.Errorf("income category for an expense: %d", code)
	}
	bobFood := categoryID(t, overview(t, bob), "Food & Drinks")
	if code, _ := apply(alice, []wallet.PayeeCategory{{Payee: "スギ薬局", Kind: "expense", CategoryID: bobFood}}); code != 404 {
		t.Errorf("bob's category: %d", code)
	}
	// Bob can't touch Alice's records with his own category either.
	if code, n := apply(bob, []wallet.PayeeCategory{{Payee: "スギ薬局", Kind: "expense", CategoryID: bobFood}}); code != 200 || n != 0 {
		t.Errorf("bob applied: %d, %d", code, n)
	}

	// Records grouped by their note were filed too.
	var list []wallet.Transaction
	alice.do("GET", "/api/transactions?q=JR", nil, &list)
	if len(list) != 1 || list[0].CategoryID == nil || *list[0].CategoryID != transport {
		t.Errorf("JR record %+v", list)
	}
}

func TestEmailsReadWithUsersKey(t *testing.T) {
	fake := withFakeClaude(t)
	ts := newTestServer(t, withInbound, withSecrets)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")
	var v inboxView
	alice.do("GET", "/api/inbox", nil, &v)
	if v.AIEnabled {
		t.Fatal("AI reader on without a key")
	}
	alice.do("PUT", "/api/ai/key", map[string]string{"key": goodKey}, nil)
	if alice.do("GET", "/api/inbox", nil, &v); !v.AIEnabled {
		t.Fatal("AI reader off with the user's key")
	}
	raw := "From: Example Books <orders@books.example>\r\nTo: me@example.com\r\nSubject: Your receipt\r\n" +
		"Message-ID: <r1@books.example>\r\nDate: Sun, 11 Oct 2026 07:00:00 +0000\r\n\r\nTotal charged: 980 JPY\r\n"
	var e inbox.Email
	if code := deliver(alice, v.Address, []byte(raw), &e); code != 200 || e.Status != "payment" || e.Parser != "ai" {
		t.Fatalf("deliver: %d %+v", code, e)
	}
	if last := fake.keys[len(fake.keys)-1]; last != goodKey {
		t.Errorf("read with key %q", last)
	}
}

func TestClaudeKeyNeedsSecretsKey(t *testing.T) {
	withFakeClaude(t)
	ts := newTestServer(t)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")
	var st aiStatus
	if alice.do("GET", "/api/ai", nil, &st); st.CanSave {
		t.Error("can_save without SECRETS_KEY")
	}
	var e struct{ Error string }
	if code := alice.do("PUT", "/api/ai/key", map[string]string{"key": goodKey}, &e); code != 409 || !strings.Contains(e.Error, "SECRETS_KEY") {
		t.Errorf("save without SECRETS_KEY: %d %q", code, e.Error)
	}
}

func TestClaudeKeyEncryptedAtRest(t *testing.T) {
	withFakeClaude(t)
	conn := testdb.Open(t)
	start := func(secret string) *httptest.Server {
		cfg := config.Config{AllowSignup: true, AuthRateLimit: 1000, SecretsKey: secret}
		ts := httptest.NewServer(server.New(cfg, conn, fstest.MapFS{}, "test"))
		t.Cleanup(ts.Close)
		return ts
	}
	alice := newClient(t, start(secretsKey))
	alice.signup("alice@example.com")
	if code := alice.do("PUT", "/api/ai/key", map[string]string{"key": goodKey}, nil); code != 200 {
		t.Fatalf("save: %d", code)
	}
	// What a database dump shows: no key.
	var sealed []byte
	var hint string
	if err := conn.QueryRow(`SELECT sealed_key, key_hint FROM ai_keys`).Scan(&sealed, &hint); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(sealed), "good-test-key") || hint != "1234" {
		t.Errorf("stored %q, hint %q", sealed, hint)
	}

	// After a restart with another SECRETS_KEY, the key can't be read, and
	// the user is asked to add it again rather than shown an error.
	again := newClient(t, start("another-secrets-key-0123456789abcdef"))
	if code := again.do("POST", "/api/login", map[string]string{"email": "alice@example.com", "password": password}, nil); code != 200 {
		t.Fatalf("login: %d", code)
	}
	var st aiStatus
	if code := again.do("GET", "/api/ai", nil, &st); code != 200 || !st.HasKey || !st.KeyUnreadable {
		t.Fatalf("status %d %+v", code, st)
	}
	var e struct{ Error string }
	if code := again.do("POST", "/api/ai/categorize", nil, &e); code != 409 || !strings.Contains(e.Error, "add it again") {
		t.Errorf("categorize: %d %q", code, e.Error)
	}
	if code := again.do("PUT", "/api/ai/key", map[string]string{"key": goodKey}, &st); code != 200 || st.KeyUnreadable {
		t.Errorf("re-add: %d %+v", code, st)
	}
}
