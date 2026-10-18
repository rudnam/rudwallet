package server_test

import (
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

func TestTags(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	food := categoryID(t, o, "Food & Drinks")
	when := time.Now().Add(-time.Hour).UTC()

	record := func(payee string, tags any) wallet.Transaction {
		t.Helper()
		body := map[string]any{"kind": "expense", "amount": 1000, "account_id": cash, "category_id": food,
			"occurred_at": when, "payee": payee}
		if tags != nil {
			body["tags"] = tags
		}
		var tx wallet.Transaction
		if code := alice.do("POST", "/api/transactions", body, &tx); code != 201 {
			t.Fatalf("create %s: %d", payee, code)
		}
		return tx
	}

	// Tags are tidied: no "#", single spaces, the same one twice kept once.
	ramen := record("Ramen", []string{" #Trip  to Osaka ", "trip to osaka", "", "Work"})
	if !slices.Equal(ramen.Tags, []string{"Trip to Osaka", "Work"}) {
		t.Errorf("tags %q", ramen.Tags)
	}
	// A record without tags has an empty list, not null.
	plain := record("Coffee", nil)
	if plain.Tags == nil || len(plain.Tags) != 0 {
		t.Errorf("no tags: %#v", plain.Tags)
	}
	// A tag used before keeps its spelling.
	if sushi := record("Sushi", []string{"TRIP TO OSAKA"}); !slices.Equal(sushi.Tags, []string{"Trip to Osaka"}) {
		t.Errorf("respelled %q", sushi.Tags)
	}

	// Too long, or too many, is refused.
	long := map[string]any{"kind": "expense", "amount": 1, "account_id": cash, "occurred_at": when,
		"tags": []string{"a tag that is far too long to be a tag"}}
	if code := alice.do("POST", "/api/transactions", long, nil); code != 400 {
		t.Errorf("long tag: %d", code)
	}
	many := []string{}
	for i := range 11 {
		many = append(many, fmt.Sprint("t", i))
	}
	long["tags"] = many
	if code := alice.do("POST", "/api/transactions", long, nil); code != 400 {
		t.Errorf("11 tags: %d", code)
	}

	// The tags in use, the most used first.
	var tags []wallet.Tag
	if code := alice.do("GET", "/api/tags", nil, &tags); code != 200 {
		t.Fatalf("tags: %d", code)
	}
	if want := []wallet.Tag{{Name: "Trip to Osaka", Count: 2}, {Name: "Work", Count: 1}}; !slices.Equal(tags, want) {
		t.Errorf("tags %+v, want %+v", tags, want)
	}

	// Filter by a tag, ignoring case; search finds tags too.
	list := func(c *client, query string) []string {
		t.Helper()
		var l []wallet.Transaction
		if code := c.do("GET", "/api/transactions?"+query, nil, &l); code != 200 {
			t.Fatalf("list %s: %d", query, code)
		}
		var payees []string
		for _, tx := range l {
			payees = append(payees, tx.Payee)
		}
		slices.Sort(payees)
		return payees
	}
	if got := list(alice, "tag=trip+to+osaka"); !slices.Equal(got, []string{"Ramen", "Sushi"}) {
		t.Errorf("tag filter: %q", got)
	}
	if got := list(alice, "q=osaka"); !slices.Equal(got, []string{"Ramen", "Sushi"}) {
		t.Errorf("search: %q", got)
	}

	// An update without tags (an older app) keeps them; an empty list clears them.
	edit := map[string]any{"kind": "expense", "amount": 1200, "account_id": cash, "category_id": food,
		"occurred_at": when, "payee": "Ramen"}
	var updated wallet.Transaction
	if code := alice.do("PUT", fmt.Sprintf("/api/transactions/%d", ramen.ID), edit, &updated); code != 200 {
		t.Fatalf("update: %d", code)
	}
	if !slices.Equal(updated.Tags, ramen.Tags) {
		t.Errorf("update without tags: %q", updated.Tags)
	}
	edit["tags"] = []string{}
	alice.do("PUT", fmt.Sprintf("/api/transactions/%d", ramen.ID), edit, &updated)
	if len(updated.Tags) != 0 {
		t.Errorf("cleared: %q", updated.Tags)
	}

	// Bulk: add a tag to records without it; take it off again.
	ids := []int64{ramen.ID, plain.ID}
	var res wallet.BulkResult
	alice.do("POST", "/api/transactions/bulk", map[string]any{"ids": ids, "action": "tag", "tag": "#Lunch"}, &res)
	if res.Changed != 2 {
		t.Errorf("tag: %+v", res)
	}
	alice.do("POST", "/api/transactions/bulk", map[string]any{"ids": ids, "action": "tag", "tag": "lunch"}, &res)
	if res.Changed != 0 || res.Skipped != 2 {
		t.Errorf("tag again: %+v", res)
	}
	if got := list(alice, "tag=Lunch"); !slices.Equal(got, []string{"Coffee", "Ramen"}) {
		t.Errorf("after bulk tag: %q", got)
	}
	alice.do("POST", "/api/transactions/bulk", map[string]any{"ids": ids, "action": "untag", "tag": "LUNCH"}, &res)
	if res.Changed != 2 || len(list(alice, "tag=Lunch")) != 0 {
		t.Errorf("untag: %+v", res)
	}
	if code := alice.do("POST", "/api/transactions/bulk", map[string]any{"ids": ids, "action": "tag", "tag": " # "}, nil); code != 400 {
		t.Errorf("empty tag: %d", code)
	}

	// Deleting and restoring a record keeps its tags.
	sushi := list(alice, "tag=Trip+to+Osaka")
	if len(sushi) != 1 {
		t.Fatalf("before delete: %q", sushi)
	}
	var l []wallet.Transaction
	alice.do("GET", "/api/transactions?tag=Trip+to+Osaka", nil, &l)
	alice.do("DELETE", fmt.Sprintf("/api/transactions/%d", l[0].ID), nil, nil)
	alice.do("POST", "/api/transactions/restore", map[string]any{"ids": []int64{l[0].ID}}, nil)
	if got := list(alice, "tag=Trip+to+Osaka"); !slices.Equal(got, []string{"Sushi"}) {
		t.Errorf("after restore: %q", got)
	}

	// Another user sees none of it, and can't tag Alice's records.
	if code := bob.do("GET", "/api/tags", nil, &tags); code != 200 || len(tags) != 0 {
		t.Errorf("bob's tags: %d %+v", code, tags)
	}
	bob.do("POST", "/api/transactions/bulk", map[string]any{"ids": ids, "action": "tag", "tag": "Mine"}, &res)
	if res.Changed != 0 {
		t.Errorf("bob tagged alice's records: %+v", res)
	}
}
