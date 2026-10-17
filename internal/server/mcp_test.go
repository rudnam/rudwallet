package server_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// mcpCall sends one JSON-RPC request to /mcp the way an MCP client does: a
// bearer token, no cookies, no Origin.
func mcpCall(t *testing.T, base, token, method string, params any) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	req, _ := http.NewRequest("POST", base+"/mcp", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// tool calls an MCP tool and returns its structured result, or its error text.
func tool(t *testing.T, base, token, name string, args any) (map[string]any, string) {
	t.Helper()
	code, out := mcpCall(t, base, token, "tools/call", map[string]any{"name": name, "arguments": args})
	if code != 200 || out["error"] != nil {
		t.Fatalf("%s: %d %v", name, code, out)
	}
	res := out["result"].(map[string]any)
	if res["isError"] == true {
		return nil, res["content"].([]any)[0].(map[string]any)["text"].(string)
	}
	return res["structuredContent"].(map[string]any), ""
}

type mcpToken struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Token string `json:"token"`
}

func TestMCP(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")

	// Tokens are made in the app, and shown once.
	var tok mcpToken
	if code := alice.do("POST", "/api/mcp/tokens", map[string]string{"name": "Claude Desktop"}, &tok); code != 201 || !strings.HasPrefix(tok.Token, "wmcp_") {
		t.Fatalf("create token: %d %+v", code, tok)
	}
	var list []mcpToken
	if alice.do("GET", "/api/mcp/tokens", nil, &list); len(list) != 1 || list[0].Name != "Claude Desktop" || list[0].Token != "" {
		t.Fatalf("tokens %+v", list)
	}

	// Without a valid token: 401, with a hint for OAuth-less clients.
	for _, bad := range []string{"", "wmcp_nope", "not-a-token"} {
		if code, _ := mcpCall(t, ts.URL, bad, "initialize", nil); code != 401 {
			t.Errorf("token %q: %d", bad, code)
		}
	}
	// A browser on another site can't use a token (DNS rebinding, CSRF).
	req, _ := http.NewRequest("POST", ts.URL+"/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	req.Header.Set("Authorization", "Bearer "+tok.Token)
	req.Header.Set("Origin", "https://evil.example")
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 403 {
		t.Errorf("cross-origin: %v %v", resp.StatusCode, err)
	}
	// There is no event stream to GET.
	if resp, _ := http.Get(ts.URL + "/mcp"); resp.StatusCode != 405 {
		t.Errorf("GET /mcp: %d", resp.StatusCode)
	}

	code, out := mcpCall(t, ts.URL, tok.Token, "initialize", map[string]any{
		"protocolVersion": "2025-06-18", "capabilities": map[string]any{}, "clientInfo": map[string]any{"name": "test", "version": "1"}})
	res, _ := out["result"].(map[string]any)
	if code != 200 || res["protocolVersion"] != "2025-06-18" || res["capabilities"].(map[string]any)["tools"] == nil {
		t.Fatalf("initialize: %d %v", code, out)
	}
	// A notification gets 202 and no body.
	body := `{"jsonrpc":"2.0","method":"notifications/initialized"}`
	req, _ = http.NewRequest("POST", ts.URL+"/mcp", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tok.Token)
	if resp, _ := http.DefaultClient.Do(req); resp.StatusCode != 202 {
		t.Errorf("notification: %d", resp.StatusCode)
	}
	_, out = mcpCall(t, ts.URL, tok.Token, "tools/list", nil)
	names := []string{}
	for _, tl := range out["result"].(map[string]any)["tools"].([]any) {
		names = append(names, tl.(map[string]any)["name"].(string))
	}
	if !strings.Contains(strings.Join(names, ","), "list_uncategorized_payees,categorize_payees") {
		t.Errorf("tools %v", names)
	}
	if _, out = mcpCall(t, ts.URL, tok.Token, "nope", nil); out["error"].(map[string]any)["code"] != float64(-32601) {
		t.Errorf("unknown method: %v", out)
	}

	// Alice's records, seen and tidied through the tools.
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	for _, r := range []map[string]any{
		{"payee": "スギ薬局", "amount": 1025},
		{"payee": "スギ薬局", "amount": 500},
		{"payee": "Gym", "amount": 7000},
	} {
		r["kind"], r["account_id"], r["occurred_at"] = "expense", cash, time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)
		if code := alice.do("POST", "/api/transactions", r, nil); code != 201 {
			t.Fatalf("add: %d", code)
		}
	}
	ov, _ := tool(t, ts.URL, tok.Token, "get_overview", nil)
	if ov["currency"] != "JPY" || ov["uncategorized_records"] != float64(3) {
		t.Errorf("overview %v", ov)
	}
	p, _ := tool(t, ts.URL, tok.Token, "list_uncategorized_payees", map[string]any{})
	payees := p["payees"].([]any)
	if len(payees) != 2 || payees[0].(map[string]any)["payee"] != "スギ薬局" || payees[0].(map[string]any)["total"] != "1525" {
		t.Fatalf("payees %v", p)
	}
	health := categoryID(t, o, "Health")
	if r, _ := tool(t, ts.URL, tok.Token, "categorize_payees", map[string]any{
		"items": []map[string]any{{"payee": "スギ薬局", "kind": "expense", "category_id": health}}}); r["updated_records"] != float64(2) {
		t.Errorf("categorize %v", r)
	}
	cat, _ := tool(t, ts.URL, tok.Token, "create_category", map[string]any{"name": "Fitness", "kind": "expense"})
	if _, msg := tool(t, ts.URL, tok.Token, "create_category", map[string]any{"name": "Fitness", "kind": "expense"}); !strings.Contains(msg, "already") {
		t.Errorf("duplicate category: %q", msg)
	}
	recs, _ := tool(t, ts.URL, tok.Token, "list_records", map[string]any{"uncategorized": true})
	gym := recs["records"].([]any)
	if len(gym) != 1 || gym[0].(map[string]any)["payee"] != "Gym" || gym[0].(map[string]any)["amount"] != "7000" {
		t.Fatalf("uncategorized records %v", recs)
	}
	gymID := gym[0].(map[string]any)["id"]
	if r, _ := tool(t, ts.URL, tok.Token, "set_record_category", map[string]any{"record_id": gymID, "category_id": cat["id"]}); r["category_id"] != cat["id"] {
		t.Errorf("set category %v", r)
	}
	// Records move between accounts; a transfer keeps two different ones.
	var card struct{ ID int64 }
	if code := alice.do("POST", "/api/accounts", map[string]any{"name": "Card", "kind": "credit_card"}, &card); code != 201 {
		t.Fatalf("add account: %d", code)
	}
	if r, _ := tool(t, ts.URL, tok.Token, "set_record_account", map[string]any{"record_id": gymID, "account_id": card.ID}); r["account_id"] != float64(card.ID) {
		t.Errorf("set account %v", r)
	}
	if recs, _ := tool(t, ts.URL, tok.Token, "list_records", map[string]any{"account_id": card.ID}); len(recs["records"].([]any)) != 1 {
		t.Errorf("records on the card %v", recs)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "set_record_account", map[string]any{"record_id": gymID, "account_id": cash, "to_account_id": card.ID}); msg == "" {
		t.Error("an expense got a second account")
	}
	var transfer struct{ ID int64 }
	alice.do("POST", "/api/transactions", map[string]any{"kind": "transfer", "amount": 1000, "account_id": cash, "to_account_id": card.ID,
		"occurred_at": time.Date(2026, 10, 6, 12, 0, 0, 0, time.UTC)}, &transfer)
	if _, msg := tool(t, ts.URL, tok.Token, "set_record_account", map[string]any{"record_id": transfer.ID, "account_id": card.ID}); !strings.Contains(msg, "two different") {
		t.Errorf("transfer into itself: %q", msg)
	}
	if r, _ := tool(t, ts.URL, tok.Token, "set_record_account", map[string]any{"record_id": transfer.ID, "account_id": card.ID, "to_account_id": cash}); r["account_id"] != float64(card.ID) || r["to_account_id"] != float64(cash) {
		t.Errorf("reverse transfer %v", r)
	}
	// Moving an emailed payment teaches its card's account; a manual record
	// teaches nothing.
	var rules wallet.Rules
	if alice.do("GET", "/api/rules", nil, &rules); len(rules.Cards) != 0 {
		t.Errorf("a manual record taught a card %+v", rules.Cards)
	}
	day := time.Date(2026, 9, 20, 4, 0, 0, 0, time.UTC)
	emailed := pay(t, alice, "m1", "ローソン", 550, day)
	if emailed.AccountID == card.ID {
		t.Fatalf("payment already on the card %+v", emailed)
	}
	tool(t, ts.URL, tok.Token, "set_record_account", map[string]any{"record_id": emailed.ID, "account_id": card.ID})
	if alice.do("GET", "/api/rules", nil, &rules); len(rules.Cards) != 1 || rules.Cards[0].AccountID != card.ID {
		t.Errorf("card rules %+v", rules.Cards)
	}
	if next := pay(t, alice, "m2", "ローソン", 300, day.Add(time.Hour)); next.AccountID != card.ID {
		t.Errorf("the card's next payment went to %d, not the card", next.AccountID)
	}
	sp, _ := tool(t, ts.URL, tok.Token, "spending_by_category", map[string]any{"from": "2026-10-01", "to": "2026-11-01"})
	if sp["total"] != "8525" || sp["categories"].([]any)[0].(map[string]any)["category"] != "Fitness" {
		t.Errorf("spending %v", sp)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "spending_by_category", map[string]any{"from": "October", "to": "2026-11-01"}); msg == "" {
		t.Error("bad date accepted")
	}
	if r, msg := tool(t, ts.URL, tok.Token, "delete_category", map[string]any{"category_id": cat["id"]}); r["deleted_category_id"] != cat["id"] {
		t.Errorf("delete category %v %q", r, msg)
	}
	if recs, _ := tool(t, ts.URL, tok.Token, "list_records", map[string]any{"uncategorized": true, "query": "Gym"}); len(recs["records"].([]any)) != 1 {
		t.Errorf("gym not uncategorised after delete: %v", recs)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "delete_category", map[string]any{"category_id": cat["id"]}); !strings.Contains(msg, "no such category") {
		t.Errorf("delete again: %q", msg)
	}

	// Bob's token sees only Bob's wallet, and can't change Alice's records.
	var bobTok mcpToken
	bob.do("POST", "/api/mcp/tokens", map[string]string{}, &bobTok)
	if r, _ := tool(t, ts.URL, bobTok.Token, "list_records", nil); len(r["records"].([]any)) != 0 {
		t.Errorf("bob sees %v", r)
	}
	bobHealth := categoryID(t, overview(t, bob), "Health")
	if _, msg := tool(t, ts.URL, bobTok.Token, "set_record_category", map[string]any{"record_id": gymID, "category_id": bobHealth}); msg == "" {
		t.Error("bob changed alice's record")
	}
	bobCash := overview(t, bob).Accounts[0].ID
	if _, msg := tool(t, ts.URL, bobTok.Token, "set_record_account", map[string]any{"record_id": gymID, "account_id": bobCash}); msg == "" {
		t.Error("bob moved alice's record")
	}
	if _, msg := tool(t, ts.URL, bobTok.Token, "delete_category", map[string]any{"category_id": health}); !strings.Contains(msg, "no such category") {
		t.Errorf("bob deleted alice's category: %q", msg)
	}
	if code := bob.do("DELETE", "/api/mcp/tokens/"+itoa(tok.ID), nil, nil); code != 404 {
		t.Errorf("bob deleted alice's token: %d", code)
	}

	// A removed token stops working.
	if alice.do("GET", "/api/mcp/tokens", nil, &list); list[0].Name != "Claude Desktop" {
		t.Errorf("tokens %+v", list)
	}
	if code := alice.do("DELETE", "/api/mcp/tokens/"+itoa(tok.ID), nil, nil); code != 204 {
		t.Fatalf("delete token: %d", code)
	}
	if code, _ := mcpCall(t, ts.URL, tok.Token, "ping", nil); code != 401 {
		t.Errorf("removed token: %d", code)
	}
}

func itoa(n int64) string { b, _ := json.Marshal(n); return string(b) }
