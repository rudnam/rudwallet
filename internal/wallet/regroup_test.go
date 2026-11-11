package wallet

import (
	"fmt"
	"testing"
)

// An old wallet's default subcategories are offered under their main
// category; nothing else is.
func TestRegroupMoves(t *testing.T) {
	id := func(n int64) *int64 { return &n }
	cats := []Category{
		{ID: 1, Name: "Food & Drinks", Kind: "expense"},
		{ID: 2, Name: "groceries", Kind: "expense"},                // any case
		{ID: 3, Name: "Housing", Kind: "expense"},                  //
		{ID: 4, Name: "水道光熱費", Kind: "expense"},                    // Japanese: Bills & Utilities
		{ID: 5, Name: "Shopping", Kind: "expense"},                 //
		{ID: 6, Name: "Clothing", Kind: "expense"},                 // has a subcategory of its own: stays
		{ID: 7, Name: "Shoes", Kind: "expense", ParentID: id(6)},   //
		{ID: 8, Name: "Taxi", Kind: "expense"},                     // no Transport: stays
		{ID: 9, Name: "Rent", Kind: "expense", ParentID: id(5)},    // already a subcategory: stays
		{ID: 10, Name: "Pets", Kind: "income"},                     // another kind: stays
		{ID: 11, Name: "Family", Kind: "expense", ParentID: id(5)}, // not a main category: Kids stay
		{ID: 12, Name: "Kids", Kind: "expense"},
	}
	got := map[int64]int64{}
	for _, c := range regroupMoves(cats) {
		got[c.ID] = *c.ParentID
	}
	if want := map[int64]int64{2: 1, 4: 3}; fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("moves %v, want %v", got, want)
	}
}
