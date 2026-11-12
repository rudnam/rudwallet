package wallet

import (
	"fmt"
	"testing"
)

// An old wallet's categories are offered where a new wallet has them; what's
// there already, and what has subcategories of its own, isn't.
func TestRegroupMoves(t *testing.T) {
	id := func(n int64) *int64 { return &n }
	cats := []Category{
		{ID: 1, Name: "Food & Drinks", Kind: "expense"},
		{ID: 2, Name: "groceries", Kind: "expense"},                         // any case: under Food & Drinks
		{ID: 3, Name: "Housing", Kind: "expense"},                           //
		{ID: 4, Name: "水道光熱費", Kind: "expense", ParentID: id(3)},            // Bills & Utilities: out of Housing
		{ID: 5, Name: "Phone & Internet", Kind: "expense", ParentID: id(3)}, // under Bills & Utilities, once it's a main one
		{ID: 6, Name: "Shopping", Kind: "expense"},                          //
		{ID: 7, Name: "Clothing", Kind: "expense"},                          // has a subcategory of its own: stays
		{ID: 8, Name: "Shoes", Kind: "expense", ParentID: id(7)},            //
		{ID: 9, Name: "Taxi", Kind: "expense"},                              // no Transport: stays
		{ID: 10, Name: "Rent", Kind: "expense", ParentID: id(3)},            // already there: stays
		{ID: 11, Name: "Pets", Kind: "income"},                              // another kind: stays
		{ID: 12, Name: "Entertainment", Kind: "expense"},                    //
		{ID: 13, Name: "Subscriptions", Kind: "expense", ParentID: id(12)},  // to Bills & Utilities
		{ID: 14, Name: "Interest & Dividends", Kind: "income"},              // Investments by its former name: stays
		{ID: 15, Name: "Dividends", Kind: "income"},                         // under it
		{ID: 16, Name: "My own", Kind: "expense", ParentID: id(6)},          // not a default: stays
	}
	moves := func(list []Category) map[int64]string {
		out := map[int64]string{}
		for _, c := range list {
			out[c.ID] = "main"
			if c.ParentID != nil {
				out[c.ID] = fmt.Sprint(*c.ParentID)
			}
		}
		return out
	}
	// Older apps: only moves under a main category the wallet has as one.
	if got, want := moves(nestMoves(cats)), map[int64]string{2: "1", 15: "14"}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("nest moves %v, want %v", got, want)
	}
	// All of them: Bills & Utilities becomes a main category, and Phone & Internet
	// and Subscriptions go under it.
	all := unnestMoves(cats)
	all = append(all, nestMoves(applyMoves(cats, all))...)
	if got, want := moves(all), map[int64]string{2: "1", 4: "main", 5: "4", 13: "4", 15: "14"}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("all moves %v, want %v", got, want)
	}
}

// Every main category has a few subcategories, except the catch-alls; and
// what imports file things under is a default.
func TestDefaultTree(t *testing.T) {
	subs := map[string]int{}
	for _, p := range defaultParents {
		subs[p]++
	}
	for _, c := range defaultCategories {
		if _, sub := defaultParents[c.Name]; sub || c.Name == "Other" || c.Name == "Other income" {
			continue
		}
		if subs[c.Name] < 3 || subs[c.Name] > 4 {
			t.Errorf("%q has %d subcategories, want 3 or 4", c.Name, subs[c.Name])
		}
	}
	isDefault := func(kind, name string) bool {
		for _, c := range defaultCategories {
			if c.Name == name && c.Kind == kind {
				return true
			}
		}
		return false
	}
	for kind, m := range budgetBakersCategories {
		for from, to := range m {
			if !isDefault(kind, to) {
				t.Errorf("BudgetBakers %q -> %q: not a default %s category", from, to, kind)
			}
		}
	}
	for _, s := range shopCategories {
		if !isDefault("expense", s.category) {
			t.Errorf("shop words -> %q: not a default expense category", s.category)
		}
	}
	for _, s := range incomeWords {
		if !isDefault("income", s.category) {
			t.Errorf("income words -> %q: not a default income category", s.category)
		}
	}
	for old, now := range formerNames {
		if defaultName(old) != now || japaneseNames[now] == "" {
			t.Errorf("former name %q -> %q", old, now)
		}
	}
}
