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
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Cafés", "kind": "expense", "icon": "coffee", "parent_id": food}, &cafes); code != 201 ||
		cafes.ParentID == nil || *cafes.ParentID != food {
		t.Fatalf("create a subcategory: %d %+v", code, cafes)
	}
	var top wallet.Category
	if code := c.do("POST", "/api/categories", map[string]any{"name": "Hobbies", "kind": "expense"}, &top); code != 201 || top.ParentID != nil {
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
		{"under itself", "PUT", fmt.Sprintf("/api/categories/%d", top.ID), map[string]any{"name": "Hobbies", "kind": "expense", "parent_id": top.ID}},
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
	if code := alice.do("POST", "/api/categories", map[string]any{"name": "Cafés", "kind": "expense", "parent_id": food}, &cafes); code != 201 {
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
	alice.do("POST", "/api/budgets", map[string]any{"name": "Cafés", "category_id": cafes.ID, "amount": 1000}, nil)

	app := appClient(t, ts.URL)
	signInApp(t, app, "alice@example.com")
	var s widget.Summary
	q := url.Values{"from": {now.Add(-time.Hour).Format(time.RFC3339)}, "to": {now.Add(time.Hour).Format(time.RFC3339)}}
	if code := app.do("GET", "/api/widget?"+q.Encode(), nil, &s); code != 200 {
		t.Fatalf("summary: %d", code)
	}
	want := []widget.Budget{{Name: "Food", Amount: 3000, Spent: 1650}, {Name: "Cafés", Amount: 1000, Spent: 450}}
	if fmt.Sprint(s.Budgets) != fmt.Sprint(want) {
		t.Errorf("budgets %+v, want %+v", s.Budgets, want)
	}
}
