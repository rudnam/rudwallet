package server_test

import (
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// A client that asks for scope=read (RudDash) gets a connection that can
// call read tools only, and the Connect page and Settings say so.
func TestMCPReadOnly(t *testing.T) {
	ts := newTestServer(t)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")
	const callback = "https://dashboard.example/api/wallet/callback"

	_, as := oauthPost(t, ts.URL+"/oauth/register", map[string]any{"client_name": "RudDash", "redirect_uris": []string{callback}})
	clientID := as["client_id"].(string)
	verifier := strings.Repeat("r", 50)

	// The scope reaches the Connect page, which is told it's read-only.
	resp, err := noRedirects.Get(ts.URL + "/oauth/authorize?" + url.Values{"response_type": {"code"}, "client_id": {clientID},
		"redirect_uri": {callback}, "code_challenge": {pkce(verifier)}, "code_challenge_method": {"S256"}, "scope": {"read"}}.Encode())
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	page, _ := url.ParseQuery(strings.TrimPrefix(resp.Header.Get("Location"), "/app/#/connect?"))
	if page.Get("scope") != "read" {
		t.Fatalf("connect page params %v", page)
	}
	var info map[string]any
	q := "?" + url.Values{"client_id": {clientID}, "redirect_uri": {callback}, "scope": {"read"}}.Encode()
	if code := alice.do("GET", "/api/mcp/authorize"+q, nil, &info); code != 200 || info["read_only"] != true {
		t.Fatalf("describe: %d %v", code, info)
	}

	var decided map[string]string
	alice.do("POST", "/api/mcp/authorize", map[string]any{"client_id": clientID, "redirect_uri": callback,
		"code_challenge": pkce(verifier), "scope": "read", "allow": true}, &decided)
	back, _ := url.Parse(decided["redirect_to"])
	code, tokens := oauthPost(t, ts.URL+"/oauth/token", url.Values{"grant_type": {"authorization_code"}, "client_id": {clientID},
		"code": {back.Query().Get("code")}, "code_verifier": {verifier}, "redirect_uri": {callback}})
	access, _ := tokens["access_token"].(string)
	if code != 200 || access == "" || tokens["scope"] != "read" {
		t.Fatalf("exchange: %d %v", code, tokens)
	}

	// It lists and calls read tools only.
	_, out := mcpCall(t, ts.URL, access, "tools/list", nil)
	names := map[string]bool{}
	for _, tl := range out["result"].(map[string]any)["tools"].([]any) {
		names[tl.(map[string]any)["name"].(string)] = true
	}
	if !names["get_overview"] || !names["list_budgets"] || names["create_category"] || names["categorize_payees"] {
		t.Errorf("read-only tools: %v", names)
	}
	if _, msg := tool(t, ts.URL, access, "create_category", map[string]any{"name": "Sneaky", "kind": "expense"}); !strings.Contains(msg, "read-only") {
		t.Errorf("write through a read-only token: %q", msg)
	}
	if res, msg := tool(t, ts.URL, access, "get_overview", map[string]any{}); msg != "" || res == nil {
		t.Errorf("read: %v %q", res, msg)
	}
	o := overview(t, alice)
	for _, cat := range o.Categories {
		if cat.Name == "Sneaky" {
			t.Fatal("the read-only token created a category")
		}
	}

	// Still read-only after a refresh, and listed as such.
	code, renewed := oauthPost(t, ts.URL+"/oauth/token", url.Values{"grant_type": {"refresh_token"}, "client_id": {clientID}, "refresh_token": {tokens["refresh_token"].(string)}})
	if code != 200 || renewed["scope"] != "read" {
		t.Fatalf("refresh: %d %v", code, renewed)
	}
	if _, msg := tool(t, ts.URL, renewed["access_token"].(string), "create_category", map[string]any{"name": "Sneaky", "kind": "expense"}); !strings.Contains(msg, "read-only") {
		t.Errorf("write after refresh: %q", msg)
	}
	var list []struct {
		Name     string `json:"name"`
		ReadOnly bool   `json:"read_only"`
	}
	if alice.do("GET", "/api/mcp/tokens", nil, &list); len(list) != 1 || !list[0].ReadOnly {
		t.Errorf("tokens %+v", list)
	}

	// A token made by hand keeps full access.
	var tok mcpToken
	alice.do("POST", "/api/mcp/tokens", map[string]string{"name": "Claude Desktop"}, &tok)
	if _, msg := tool(t, ts.URL, tok.Token, "create_category", map[string]any{"name": "Allowed", "kind": "expense"}); msg != "" {
		t.Errorf("full token: %q", msg)
	}
}

// list_budgets counts spending the way the Budgets page does: a category's
// budget includes its subcategories; one without a category, everything.
func TestListBudgets(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	food, shopping := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Shopping")
	var cafes wallet.Category
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Cafes", "kind": "expense", "parent_id": food}, &cafes); code != 201 {
		t.Fatalf("subcategory: %d", code)
	}
	for _, b := range []map[string]any{
		{"name": "Food", "category_id": food, "amount": 50000},
		{"name": "Everything", "amount": 200000},
	} {
		if code := c.do("POST", "/api/budgets", b, nil); code != 201 {
			t.Fatalf("budget %v: %d", b, code)
		}
	}
	zone, _ := time.LoadLocation("Asia/Manila")
	now := time.Now().In(zone)
	thisMonth := time.Date(now.Year(), now.Month(), 1, 12, 0, 0, 0, zone)
	lastMonth := thisMonth.AddDate(0, -1, 0)
	acct := o.Accounts[0].ID
	for _, r := range []struct {
		cat    int64
		amount int64
		at     time.Time
	}{
		{food, 10000, thisMonth},
		{cafes.ID, 2500, thisMonth}, // counts for Food through its parent
		{shopping, 30000, thisMonth},
		{food, 7000, lastMonth},
	} {
		body := map[string]any{"kind": "expense", "amount": r.amount, "account_id": acct, "category_id": r.cat, "occurred_at": r.at}
		if code := c.do("POST", "/api/transactions", body, nil); code != 201 {
			t.Fatalf("record: %d", code)
		}
	}

	var tok mcpToken
	c.do("POST", "/api/mcp/tokens", map[string]string{"name": "test"}, &tok)
	res, msg := tool(t, ts.URL, tok.Token, "list_budgets", map[string]any{"time_zone": "Asia/Manila", "months": 2})
	if msg != "" {
		t.Fatal(msg)
	}
	if res["month"] != thisMonth.Format("2006-01") || res["currency"] == "" {
		t.Errorf("list_budgets: %v", res)
	}
	bs := res["budgets"].([]any)
	if len(bs) != 2 {
		t.Fatalf("budgets: %v", bs)
	}
	f, all := bs[0].(map[string]any), bs[1].(map[string]any)
	hist := f["history"].([]any)
	if f["name"] != "Food" || f["category"] != "Food & Drinks" || len(hist) != 2 ||
		hist[0].(map[string]any)["month"] != lastMonth.Format("2006-01") {
		t.Errorf("food budget: %v", f)
	}
	// Amounts are decimal strings in the user's currency.
	cur := res["currency"].(string)
	money := func(minor int64) string { return wallet.FormatMinor(minor, cur) }
	if f["spent"] != money(12500) || f["limit"] != money(50000) || hist[0].(map[string]any)["spent"] != money(7000) {
		t.Errorf("food: spent %v limit %v history %v", f["spent"], f["limit"], hist)
	}
	if all["spent"] != money(42500) || all["category_id"] != nil {
		t.Errorf("everything: %v", all)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "list_budgets", map[string]any{"month": "November"}); msg == "" {
		t.Error("bad month accepted")
	}
}
