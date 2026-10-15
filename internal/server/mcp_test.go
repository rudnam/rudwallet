package server_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/notify"
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
	if recs, _ := tool(t, ts.URL, tok.Token, "list_records", map[string]any{"uncategorized": true}); len(recs["records"].([]any)) != 1 {
		t.Errorf("gym not uncategorised after delete: %v", recs)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "delete_category", map[string]any{"category_id": cat["id"]}); !strings.Contains(msg, "no such category") {
		t.Errorf("delete again: %q", msg)
	}

	// Tidying up: a payment recorded twice, in two accounts.
	var card wallet.Account
	alice.do("POST", "/api/accounts", map[string]any{"name": "Card", "kind": "credit_card"}, &card)
	var gymCard, transfer wallet.Transaction
	alice.do("POST", "/api/transactions", map[string]any{"kind": "expense", "account_id": card.ID, "amount": 7000, "payee": "GYM",
		"occurred_at": time.Date(2026, 10, 6, 9, 0, 0, 0, time.UTC)}, &gymCard)
	alice.do("POST", "/api/transactions", map[string]any{"kind": "transfer", "account_id": cash, "to_account_id": card.ID, "amount": 2000,
		"occurred_at": time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)}, &transfer)
	d, _ := tool(t, ts.URL, tok.Token, "find_duplicates", map[string]any{})
	if pairs := d["pairs"].([]any); len(pairs) != 1 || pairs[0].([]any)[0].(map[string]any)["id"] != gymID ||
		pairs[0].([]any)[1].(map[string]any)["id"] != float64(gymCard.ID) {
		t.Fatalf("duplicates %v", d)
	}
	// Deleting is all or none; a transfer leaves both accounts.
	if _, msg := tool(t, ts.URL, tok.Token, "delete_records", map[string]any{"record_ids": []int64{gymCard.ID, 999999}}); !strings.Contains(msg, "nothing was deleted") {
		t.Errorf("delete with a wrong ID: %q", msg)
	}
	if recs, _ := tool(t, ts.URL, tok.Token, "list_records", nil); len(recs["records"].([]any)) != 5 {
		t.Errorf("records after a refused delete: %v", recs)
	}
	if r, msg := tool(t, ts.URL, tok.Token, "delete_records", map[string]any{"record_ids": []int64{gymCard.ID, transfer.ID, gymCard.ID}}); r["deleted_records"] != float64(2) {
		t.Errorf("delete records %v %q", r, msg)
	}
	if a := overview(t, alice).Accounts[1]; a.ID != card.ID || a.Balance != 0 {
		t.Errorf("card after deleting %+v", a)
	}
	// A payment from the phone waits for review until it has a category.
	lawson := *post(t, alice, notify.Incoming{Package: googleWallet, When: time.Date(2026, 10, 12, 3, 0, 0, 0, time.UTC).UnixMilli(),
		Title: "Lawson", Text: "¥550 with Visa •••• 1234"})[0].TransactionID
	if r, _ := tool(t, ts.URL, tok.Token, "list_records", map[string]any{"status": "review"}); len(r["records"].([]any)) != 1 {
		t.Errorf("records to review %v", r)
	}
	if r, _ := tool(t, ts.URL, tok.Token, "confirm_records", map[string]any{"record_ids": []int64{lawson}}); r["confirmed_records"] != float64(0) {
		t.Errorf("confirmed without a category: %v", r)
	}
	tool(t, ts.URL, tok.Token, "set_record_category", map[string]any{"record_id": lawson, "category_id": health})
	if r, _ := tool(t, ts.URL, tok.Token, "confirm_records", map[string]any{"record_ids": []int64{lawson}}); r["confirmed_records"] != float64(1) {
		t.Errorf("confirm %v", r)
	}
	// A balance is corrected through the starting balance, not the records.
	if a, msg := tool(t, ts.URL, tok.Token, "set_account_balance", map[string]any{"account_id": card.ID, "balance": "-5000"}); a["balance"] != "-5000" {
		t.Errorf("set balance %v %q", a, msg)
	}
	if a := overview(t, alice).Accounts[1]; a.Balance != -5000 || a.InitialBalance != -5000 {
		t.Errorf("card after correcting %+v", a)
	}
	if _, msg := tool(t, ts.URL, tok.Token, "set_account_balance", map[string]any{"account_id": card.ID, "balance": "12.5"}); msg == "" {
		t.Error("yen with decimals accepted")
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
	if _, msg := tool(t, ts.URL, bobTok.Token, "delete_category", map[string]any{"category_id": health}); !strings.Contains(msg, "no such category") {
		t.Errorf("bob deleted alice's category: %q", msg)
	}
	if _, msg := tool(t, ts.URL, bobTok.Token, "delete_records", map[string]any{"record_ids": []any{gymID}}); !strings.Contains(msg, "no such record") {
		t.Errorf("bob deleted alice's record: %q", msg)
	}
	if _, msg := tool(t, ts.URL, bobTok.Token, "set_account_balance", map[string]any{"account_id": card.ID, "balance": "0"}); !strings.Contains(msg, "no such account") {
		t.Errorf("bob set alice's balance: %q", msg)
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
