package server_test

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// The tools that finish a cleanup: finding duplicates, deleting records,
// confirming records to review, and correcting a balance.
func TestMCPCleanup(t *testing.T) {
	ts, conn := newTestServerDB(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	var tok, bobTok mcpToken
	alice.do("POST", "/api/mcp/tokens", map[string]string{"name": "Claude"}, &tok)
	bob.do("POST", "/api/mcp/tokens", map[string]string{"name": "Claude"}, &bobTok)
	call := func(name string, args any) (map[string]any, string) {
		t.Helper()
		return tool(t, ts.URL, tok.Token, name, args)
	}

	o := overview(t, alice)
	cash := o.Accounts[0].ID
	var card wallet.Account
	if code := alice.do("POST", "/api/accounts", map[string]any{"name": "Card", "kind": "credit_card"}, &card); code != 201 {
		t.Fatalf("add card: %d", code)
	}
	day := func(d, h int) time.Time { return time.Date(2026, 10, d, h, 0, 0, 0, time.UTC) }
	add := func(c *client, r map[string]any) int64 {
		t.Helper()
		if r["kind"] == nil {
			r["kind"] = "expense"
		}
		var rec wallet.Transaction
		if code := c.do("POST", "/api/transactions", r, &rec); code != 201 {
			t.Fatalf("add %v: %d", r, code)
		}
		return rec.ID
	}
	coffee := add(alice, map[string]any{"account_id": cash, "amount": 1200, "payee": "Starbucks", "occurred_at": day(5, 12)})
	coffeeCard := add(alice, map[string]any{"account_id": card.ID, "amount": 1200, "payee": "STARBUCKS COFFEE", "occurred_at": day(6, 3)})
	gym := add(alice, map[string]any{"account_id": cash, "amount": 3000, "payee": "Gym", "occurred_at": day(5, 9)})
	gymCard := add(alice, map[string]any{"account_id": card.ID, "amount": 3090, "payee": "Gym", "occurred_at": day(7, 9)})
	rent := add(alice, map[string]any{"account_id": cash, "amount": 50000, "payee": "Rent", "occurred_at": day(20, 0)})
	transfer := add(alice, map[string]any{"kind": "transfer", "account_id": cash, "to_account_id": card.ID, "amount": 10000, "occurred_at": day(10, 0)})
	bobsCoffee := add(bob, map[string]any{"account_id": overview(t, bob).Accounts[0].ID, "amount": 1200, "payee": "Starbucks", "occurred_at": day(5, 12)})

	count := func() int {
		t.Helper()
		r, _ := call("list_records", nil)
		return len(r["records"].([]any))
	}
	balances := func() (cashBal, cardBal any) {
		t.Helper()
		ov, _ := call("get_overview", nil)
		for _, a := range ov["accounts"].([]any) {
			a := a.(map[string]any)
			switch a["id"] {
			case float64(cash):
				cashBal = a["balance"]
			case float64(card.ID):
				cardBal = a["balance"]
			}
		}
		return
	}
	pairIDs := func(r map[string]any) [][2]float64 {
		var out [][2]float64
		for _, p := range r["pairs"].([]any) {
			recs := p.(map[string]any)["records"].([]any)
			out = append(out, [2]float64{recs[0].(map[string]any)["id"].(float64), recs[1].(map[string]any)["id"].(float64)})
		}
		return out
	}

	// Duplicates: across accounts, equal amounts first, never another
	// user's records, and nothing changes.
	d, msg := call("find_duplicates", map[string]any{})
	if got := pairIDs(d); fmt.Sprint(got) != fmt.Sprint([][2]float64{{float64(coffee), float64(coffeeCard)}, {float64(gym), float64(gymCard)}}) {
		t.Fatalf("duplicates %v %q", d, msg)
	}
	first := d["pairs"].([]any)[0].(map[string]any)
	if first["same_payee"] != true || first["same_account"] != false || first["days_apart"] != 0.6 || first["amount_difference"] != "0" || first["flagged"] != false {
		t.Errorf("first pair %v", first)
	}
	if second := d["pairs"].([]any)[1].(map[string]any); second["amount_difference"] != "90" || second["days_apart"] != float64(2) {
		t.Errorf("second pair %v", second)
	}
	for args, want := range map[string]int{
		`{"amount_tolerance_percent": 0}`: 1, // equal amounts only
		`{"max_days_apart": 0}`:           0, // within 12 hours: the coffees are 15 hours apart
		`{"max_days_apart": 1}`:           1,
		`{"from": "2026-10-06"}`:          0, // both records must be in the period
		`{"limit": 1}`:                    1,
	} {
		var a map[string]any
		if err := json.Unmarshal([]byte(args), &a); err != nil {
			t.Fatal(err)
		}
		if r, msg := call("find_duplicates", a); len(pairIDs(r)) != want {
			t.Errorf("find_duplicates %s: %v %q", args, r, msg)
		}
	}
	if _, msg := call("find_duplicates", map[string]any{"amount_tolerance_percent": 80}); msg == "" {
		t.Error("tolerance of 80% accepted")
	}
	if r, _ := tool(t, ts.URL, bobTok.Token, "find_duplicates", map[string]any{}); len(pairIDs(r)) != 0 {
		t.Errorf("bob's duplicates %v", r)
	}
	if n := count(); n != 6 {
		t.Fatalf("%d records after finding duplicates", n)
	}

	// Deleting is all or none: one wrong ID, or another user's record, and
	// nothing is deleted.
	if _, msg := call("delete_records", map[string]any{"record_ids": []int64{coffeeCard, 999999}}); !strings.Contains(msg, "999999") || !strings.Contains(msg, "nothing was deleted") {
		t.Errorf("delete with a wrong ID: %q", msg)
	}
	if _, msg := call("delete_records", map[string]any{"record_ids": []int64{coffeeCard, bobsCoffee}}); !strings.Contains(msg, "no such record") {
		t.Errorf("delete bob's record: %q", msg)
	}
	if _, msg := tool(t, ts.URL, bobTok.Token, "delete_records", map[string]any{"record_ids": []int64{coffee}}); !strings.Contains(msg, "no such record") {
		t.Errorf("bob deleting alice's record: %q", msg)
	}
	if _, msg := call("delete_records", map[string]any{"record_ids": []int64{}}); msg == "" {
		t.Error("deleted no records")
	}
	if n := count(); n != 6 {
		t.Fatalf("%d records after refused deletes", n)
	}
	if n := len(records(t, bob)); n != 1 {
		t.Fatalf("bob has %d records", n)
	}
	cashBal, cardBal := balances()
	if cashBal != "-64200" || cardBal != "5710" {
		t.Fatalf("balances before deleting: %v %v", cashBal, cardBal)
	}
	// A transfer is one record: deleting it takes it out of both accounts.
	del, msg := call("delete_records", map[string]any{"record_ids": []int64{transfer, coffeeCard, transfer}})
	if del["deleted"] != float64(2) || msg != "" {
		t.Fatalf("delete %v %q", del, msg)
	}
	if recs := del["records"].([]any); recs[0].(map[string]any)["payee"] != "STARBUCKS COFFEE" || recs[1].(map[string]any)["to_account"] != "Card" {
		t.Errorf("deleted records %v", recs)
	}
	if cashBal, cardBal = balances(); cashBal != "-54200" || cardBal != "-3090" {
		t.Errorf("balances after deleting: %v %v", cashBal, cardBal)
	}
	if _, msg := call("delete_records", map[string]any{"record_ids": []int64{coffeeCard}}); !strings.Contains(msg, "no such record") {
		t.Errorf("delete again: %q", msg)
	}

	// Confirming follows the review page's rules; a possible duplicate
	// needs keep_duplicates.
	health := categoryID(t, o, "Health")
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`UPDATE transactions SET status = 'review' WHERE id = $1`, []any{coffee}},
		{`UPDATE transactions SET status = 'review', category_id = $2 WHERE id = $1`, []any{gym, health}},
		{`UPDATE transactions SET status = 'review', category_id = $2, duplicate_of_id = $3 WHERE id = $1`, []any{rent, health, gym}},
	} {
		if _, err := conn.Exec(q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}
	if r, _ := call("list_records", map[string]any{"status": "review"}); len(r["records"].([]any)) != 3 {
		t.Errorf("records to review %v", r)
	}
	conf, msg := call("confirm_records", map[string]any{"record_ids": []int64{coffee, gym, rent, gymCard, bobsCoffee}})
	if fmt.Sprint(conf["confirmed"]) != fmt.Sprint([]any{float64(gym)}) {
		t.Fatalf("confirm %v %q", conf, msg)
	}
	reasons := map[float64]string{}
	for _, s := range conf["not_confirmed"].([]any) {
		s := s.(map[string]any)
		reasons[s["id"].(float64)] = s["reason"].(string)
	}
	for id, want := range map[int64]string{coffee: "no category", rent: fmt.Sprintf("possible duplicate of record %d", gym), gymCard: "already confirmed", bobsCoffee: "no such record"} {
		if !strings.Contains(reasons[float64(id)], want) {
			t.Errorf("record %d not confirmed because %q, want %q", id, reasons[float64(id)], want)
		}
	}
	if conf, _ = call("confirm_records", map[string]any{"record_ids": []int64{rent}, "keep_duplicates": true}); fmt.Sprint(conf["confirmed"]) != fmt.Sprint([]any{float64(rent)}) {
		t.Errorf("confirm with keep_duplicates %v", conf)
	}
	r, _ := call("list_records", map[string]any{"status": "review"})
	if recs := r["records"].([]any); len(recs) != 1 || recs[0].(map[string]any)["id"] != float64(coffee) {
		t.Errorf("still to review %v", r)
	}
	r, _ = call("list_records", map[string]any{"query": "Rent"})
	if rec := r["records"].([]any)[0].(map[string]any); rec["status"] != "confirmed" || rec["duplicate_of_id"] != nil {
		t.Errorf("rent after confirming %v", rec)
	}
	if bobsRec := records(t, bob)[0]; bobsRec.Status != "confirmed" {
		t.Errorf("bob's record %+v", bobsRec)
	}

	// Correcting a balance changes the starting balance, never a record.
	// Cash's confirmed records: gym -3000 (5 Oct) and rent -50000 (20 Oct);
	// the coffee waits for review, so it doesn't count.
	bal, msg := call("set_account_balance", map[string]any{"account_id": cash, "balance": "-4000", "as_of": "2026-10-10"})
	want := map[string]any{"previous_balance": "-53000", "previous_starting_balance": "0", "starting_balance": "-1000",
		"balance": "-54000", "records_to_review": float64(1), "account": o.Accounts[0].Name}
	for k, v := range want {
		if bal[k] != v {
			t.Errorf("set cash balance: %s = %v, want %v (%v %q)", k, bal[k], v, bal, msg)
		}
	}
	if bal, msg = call("set_account_balance", map[string]any{"account_id": card.ID, "balance": "-5000"}); bal["balance"] != "-5000" || bal["starting_balance"] != "-1910" || bal["previous_balance"] != "-3090" {
		t.Errorf("set card balance %v %q", bal, msg)
	}
	if cashBal, cardBal = balances(); cashBal != "-54000" || cardBal != "-5000" {
		t.Errorf("balances after correcting: %v %v", cashBal, cardBal)
	}
	if n := count(); n != 4 {
		t.Errorf("%d records after correcting balances", n)
	}
	for _, bad := range []map[string]any{
		{"account_id": cash, "balance": "12.5"}, // yen have no decimals
		{"account_id": cash, "balance": "1,000"},
		{"account_id": cash, "balance": "100", "as_of": "last week"},
		{"account_id": 999999, "balance": "100"},
	} {
		if _, msg := call("set_account_balance", bad); msg == "" {
			t.Errorf("set_account_balance %v accepted", bad)
		}
	}
	if _, msg := tool(t, ts.URL, bobTok.Token, "set_account_balance", map[string]any{"account_id": card.ID, "balance": "0"}); !strings.Contains(msg, "no such account") {
		t.Errorf("bob set alice's balance: %q", msg)
	}
	if _, cardBal = balances(); cardBal != "-5000" {
		t.Errorf("card balance after bob: %v", cardBal)
	}
}
