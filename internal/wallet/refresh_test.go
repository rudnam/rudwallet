package wallet

import "testing"

// An older wallet's defaults get the names and icons a new wallet has; a
// category of the user's own, or one whose new name is taken, keeps its name.
func TestRefreshes(t *testing.T) {
	cats := []Category{
		{ID: 1, Name: "Restaurants", Kind: "expense", Icon: "🍜"},          // former name, emoji icon
		{ID: 2, Name: "Eating out", Kind: "expense", Icon: "dining"},      // already as new
		{ID: 3, Name: "Water", Kind: "expense", Icon: "bills"},            // icon only
		{ID: 4, Name: "Subscriptions", Kind: "expense", Icon: "repeat"},   // the user's own
		{ID: 5, Name: "Rewards & Cashback", Kind: "income", Icon: "card"}, // former name, same icon
		{ID: 6, Name: "Gifts", Kind: "income", Icon: "gift"},              // an expense default's name
		{ID: 7, Name: "Going out", Kind: "expense", Icon: "tag"},          // former name
	}
	want := map[int64]Category{
		1: {Name: "Restaurants", Icon: "dining"}, // Eating out is taken
		3: {Name: "Water", Icon: "water"},
		5: {Name: "Points & cashback", Icon: "card"},
		7: {Name: "Outings & events", Icon: "ticket"},
	}
	got := refreshes(cats)
	if len(got) != len(want) {
		t.Fatalf("got %+v", got)
	}
	for _, c := range got {
		if w, ok := want[c.ID]; !ok || c.Name != w.Name || c.Icon != w.Icon {
			t.Errorf("%d: %q %q, want %+v", c.ID, c.Name, c.Icon, w)
		}
	}
	// A Japanese wallet's former name becomes the Japanese name.
	ja := []Category{{ID: 1, Name: "食費", Kind: "expense", Icon: "food"}, {ID: 2, Name: "交際費", Kind: "expense", Icon: "tag"}}
	if got := refreshes(ja); len(got) != 1 || got[0].Name != "お出かけ・イベント" || got[0].Icon != "ticket" {
		t.Errorf("Japanese: %+v", got)
	}
	// A new wallet has nothing to refresh.
	fresh := []Category{}
	for i, d := range defaultCategories {
		d.ID = int64(i + 1)
		fresh = append(fresh, d)
	}
	if got := refreshes(fresh); len(got) != 0 {
		t.Errorf("a new wallet is offered %+v", got)
	}
}
