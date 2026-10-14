package wallet

import "testing"

// Every default category has a Japanese name, so a wallet set up in
// Japanese never gets an English one, and a new default can't be missed.
func TestJapaneseNames(t *testing.T) {
	seen := map[string]bool{}
	for _, c := range defaultCategories {
		ja, ok := japaneseNames[c.Name]
		if !ok || ja == "" {
			t.Errorf("no Japanese name for %q", c.Name)
		}
		if seen[ja] {
			t.Errorf("%q is the Japanese name of two defaults", ja)
		}
		seen[ja] = true
	}
	if len(japaneseNames) != len(defaultCategories) {
		t.Errorf("%d Japanese names for %d defaults", len(japaneseNames), len(defaultCategories))
	}
}

func TestDefaultsInEitherLanguage(t *testing.T) {
	ja := []Category{{ID: 1, Name: "食費", Kind: "expense"}, {ID: 2, Name: "給与", Kind: "income"}}
	if id := findCategory(ja, "expense", "Food & Drinks"); id == nil || *id != 1 {
		t.Error("Food & Drinks didn't find 食費")
	}
	missing := missingDefaults(ja)
	for _, c := range missing {
		if c.Name == "食費" || c.Name == "Food & Drinks" || c.Name == "給与" {
			t.Errorf("%q is offered though the wallet has it", c.Name)
		}
		if _, english := japaneseNames[c.Name]; english {
			t.Errorf("%q is offered in English to a Japanese wallet", c.Name)
		}
	}
	if len(missing) != len(defaultCategories)-2 {
		t.Errorf("%d missing, want %d", len(missing), len(defaultCategories)-2)
	}
}
