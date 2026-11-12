package server_test

import (
	"fmt"
	"net/url"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
	"github.com/rudnam/wallet-app/internal/widget"
)

// A category can go under a main category of the same kind, one level deep;
// an app that doesn't send parent_id leaves it where it is.
func TestSubcategories(t *testing.T) {
	ts := newTestServer(t)
	c, bob := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	bob.signup("b@example.com")
	o := overview(t, c)
	food, shopping, salary := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Shopping"), categoryID(t, o, "Salary")
	bobs := categoryID(t, overview(t, bob), "Food & Drinks")

	var cafes wallet.Category
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Tea houses", "kind": "expense", "icon": "coffee", "parent_id": food}, &cafes); code != 201 ||
		cafes.ParentID == nil || *cafes.ParentID != food {
		t.Fatalf("create a subcategory: %d %+v", code, cafes)
	}
	var top wallet.Category
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Crafts", "kind": "expense"}, &top); code != 201 || top.ParentID != nil {
		t.Fatalf("create a main category: %d %+v", code, top)
	}
	for _, cat := range overview(t, c).Categories {
		if cat.ID == cafes.ID && (cat.ParentID == nil || *cat.ParentID != food) {
			t.Errorf("the wallet's categories: %+v", cat)
		}
	}

	refused := []struct {
		why    string
		method string
		path   string
		body   map[string]any
	}{
		{"under an income category", "POST", "/api/categories", map[string]any{"name": "Tips", "kind": "expense", "parent_id": salary}},
		{"under a subcategory", "POST", "/api/categories", map[string]any{"name": "Beans", "kind": "expense", "parent_id": cafes.ID}},
		{"under another user's category", "POST", "/api/categories", map[string]any{"name": "Snacks", "kind": "expense", "parent_id": bobs}},
		{"under a missing category", "POST", "/api/categories", map[string]any{"name": "Snacks", "kind": "expense", "parent_id": 1 << 40}},
		{"not an id", "POST", "/api/categories", map[string]any{"name": "Snacks", "kind": "expense", "parent_id": "food"}},
		{"under itself", "PUT", fmt.Sprintf("/api/categories/%d", top.ID), map[string]any{"name": "Crafts", "kind": "expense", "parent_id": top.ID}},
		{"a main category with subcategories, under another", "PUT", fmt.Sprintf("/api/categories/%d", food),
			map[string]any{"name": "Food & Drinks", "kind": "expense", "parent_id": shopping}},
	}
	for _, r := range refused {
		if code := c.do(r.method, r.path, r.body, nil); code != 400 {
			t.Errorf("%s: %d, want 400", r.why, code)
		}
	}

	// Renamed by an app that doesn't know subcategories: it stays under Food & Drinks.
	var got wallet.Category
	path := fmt.Sprintf("/api/categories/%d", cafes.ID)
	if code := c.do("PUT", path, map[string]any{"name": "Coffee shops", "kind": "expense", "icon": "coffee", "color": "#8a5a3c"}, &got); code != 200 ||
		got.Name != "Coffee shops" || got.ParentID == nil || *got.ParentID != food {
		t.Errorf("rename: %d %+v", code, got)
	}
	// Moved under Shopping, then made a main category.
	if code := c.do("PUT", path, map[string]any{"name": "Coffee shops", "kind": "expense", "parent_id": shopping}, &got); code != 200 ||
		got.ParentID == nil || *got.ParentID != shopping {
		t.Errorf("move: %d %+v", code, got)
	}
	if code := c.do("PUT", path, map[string]any{"name": "Coffee shops", "kind": "expense", "parent_id": nil}, &got); code != 200 || got.ParentID != nil {
		t.Errorf("make it a main category: %d %+v", code, got)
	}
	// Bob can't move Alice's categories.
	if code := bob.do("PUT", path, map[string]any{"name": "Mine", "kind": "expense", "parent_id": nil}, nil); code != 404 {
		t.Errorf("another user's category: %d", code)
	}

	// Deleting a main category makes its subcategories main ones.
	if code := c.do("PUT", path, map[string]any{"name": "Coffee shops", "kind": "expense", "parent_id": top.ID}, nil); code != 200 {
		t.Fatalf("move under Hobbies: %d", code)
	}
	if code := c.do("DELETE", fmt.Sprintf("/api/categories/%d", top.ID), nil, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	for _, cat := range overview(t, c).Categories {
		if cat.ID == cafes.ID && cat.ParentID != nil {
			t.Errorf("after deleting its main category: %+v", cat)
		}
	}
}

// A main category's budget counts what its subcategories spent.
func TestSubcategoryBudget(t *testing.T) {
	ts := newTestServer(t)
	alice := newClient(t, ts)
	alice.signup("alice@example.com")
	o := overview(t, alice)
	cash, food := o.Accounts[0].ID, categoryID(t, o, "Food & Drinks")
	var cafes wallet.Category
	if code := alice.do("POST", "/api/categories", map[string]any{"name": "Tea houses", "kind": "expense", "parent_id": food}, &cafes); code != 201 {
		t.Fatalf("create: %d", code)
	}
	now := time.Now()
	for _, r := range []struct {
		amount   int64
		category int64
	}{{1200, food}, {450, cafes.ID}} {
		body := map[string]any{"kind": "expense", "amount": r.amount, "account_id": cash, "category_id": r.category, "occurred_at": now}
		if code := alice.do("POST", "/api/transactions", body, nil); code != 201 {
			t.Fatalf("add record: %d", code)
		}
	}
	alice.do("POST", "/api/budgets", map[string]any{"name": "Food", "category_id": food, "amount": 3000}, nil)
	alice.do("POST", "/api/budgets", map[string]any{"name": "Tea houses", "category_id": cafes.ID, "amount": 1000}, nil)

	app := appClient(t, ts.URL)
	signInApp(t, app, "alice@example.com")
	var s widget.Summary
	q := url.Values{"from": {now.Add(-time.Hour).Format(time.RFC3339)}, "to": {now.Add(time.Hour).Format(time.RFC3339)}}
	if code := app.do("GET", "/api/widget?"+q.Encode(), nil, &s); code != 200 {
		t.Fatalf("summary: %d", code)
	}
	want := []widget.Budget{{Name: "Food", Amount: 3000, Spent: 1650}, {Name: "Tea houses", Amount: 1000, Spent: 450}}
	if fmt.Sprint(s.Budgets) != fmt.Sprint(want) {
		t.Errorf("budgets %+v, want %+v", s.Budgets, want)
	}
}

// with_subcategories lists a main category's records with its subcategories'.
func TestRecordsWithSubcategories(t *testing.T) {
	ts := newTestServer(t)
	c := newClient(t, ts)
	c.signup("a@example.com")
	o := overview(t, c)
	cash, food := o.Accounts[0].ID, categoryID(t, o, "Food & Drinks")
	var cafes wallet.Category
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Tea houses", "kind": "expense", "parent_id": food}, &cafes); code != 201 {
		t.Fatalf("create: %d", code)
	}
	for _, cat := range []int64{food, cafes.ID} {
		body := map[string]any{"kind": "expense", "amount": 500, "account_id": cash, "category_id": cat, "occurred_at": time.Now()}
		if code := c.do("POST", "/api/transactions", body, nil); code != 201 {
			t.Fatalf("add record: %d", code)
		}
	}
	for q, want := range map[string]int{
		fmt.Sprintf("category_id=%d", food):                             1,
		fmt.Sprintf("category_id=%d&with_subcategories=true", food):     2,
		fmt.Sprintf("category_id=%d&with_subcategories=true", cafes.ID): 1,
	} {
		var list []wallet.Transaction
		if code := c.do("GET", "/api/transactions?"+q, nil, &list); code != 200 || len(list) != want {
			t.Errorf("%s: %d, %d records, want %d", q, code, len(list), want)
		}
	}
}

// A wallet from before the defaults had subcategories is offered to group
// them like a new wallet's, and only the moves it agrees to are made.
func TestRegroup(t *testing.T) {
	ts := newTestServer(t)
	c, bob := newClient(t, ts), newClient(t, ts)
	c.signup("a@example.com")
	bob.signup("b@example.com")
	o := overview(t, c)
	food, bills, fun := categoryID(t, o, "Food & Drinks"), categoryID(t, o, "Bills & Utilities"), categoryID(t, o, "Entertainment")
	groceries, subs, other := categoryID(t, o, "Groceries"), categoryID(t, o, "Subscriptions"), categoryID(t, o, "Other")
	const main = -1 // a move that makes a category a main one
	offer := func(cl *client, query string) map[int64]int64 {
		t.Helper()
		var cats []wallet.Category
		if code := cl.do("GET", "/api/categories/regroup"+query, nil, &cats); code != 200 {
			t.Fatalf("offer: %d", code)
		}
		out := map[int64]int64{}
		for _, cat := range cats {
			out[cat.ID] = main
			if cat.ParentID != nil {
				out[cat.ID] = *cat.ParentID
			}
		}
		return out
	}
	if got := offer(c, "?with_main=1"); len(got) != 0 {
		t.Errorf("a new wallet is offered %v", got)
	}
	// As an old wallet has them: Groceries a main category, Subscriptions under
	// Entertainment, and (made up) Other under Food & Drinks.
	move := func(id int64, parent any) {
		t.Helper()
		for _, cat := range o.Categories {
			if cat.ID == id {
				if code := c.do("PUT", fmt.Sprintf("/api/categories/%d", id), map[string]any{"name": cat.Name, "kind": cat.Kind, "parent_id": parent}, nil); code != 200 {
					t.Fatalf("move %s: %d", cat.Name, code)
				}
			}
		}
	}
	move(groceries, nil)
	move(subs, fun)
	move(other, food)
	// An older app is offered moves under a main category only.
	if got, want := offer(c, ""), map[int64]int64{groceries: food, subs: bills}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("offer %v, want %v", got, want)
	}
	if got, want := offer(c, "?with_main=1"), map[int64]int64{groceries: food, subs: bills, other: main}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("offer with main %v, want %v", got, want)
	}

	// Bob can't move Alice's categories.
	var moved []wallet.Category
	if code := bob.do("POST", "/api/categories/regroup", map[string]any{"ids": []int64{groceries}}, &moved); code != 200 || len(moved) != 0 {
		t.Errorf("another user's: %d %+v", code, moved)
	}
	// Groceries and Other; Food & Drinks, which isn't offered, stays put.
	if code := c.do("POST", "/api/categories/regroup", map[string]any{"ids": []int64{groceries, other, food}}, &moved); code != 200 || len(moved) != 2 {
		t.Fatalf("regroup: %d %+v", code, moved)
	}
	for _, cat := range overview(t, c).Categories {
		if (cat.ID == groceries && (cat.ParentID == nil || *cat.ParentID != food)) || (cat.ID == other && cat.ParentID != nil) {
			t.Errorf("after regrouping: %+v", cat)
		}
	}
	if got, want := offer(c, "?with_main=1"), map[int64]int64{subs: bills}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("offer afterwards %v, want %v", got, want)
	}
}
