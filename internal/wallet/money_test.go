package wallet

import "testing"

// Amounts are read and written exactly: a balance set from a statement
// must not be rounded, and a negative one must keep its sign.
func TestMinorUnits(t *testing.T) {
	for _, c := range []struct {
		text     string
		currency string
		minor    int64
	}{
		{"12.50", "USD", 1250},
		{"-0.50", "USD", -50},
		{"-1234.05", "USD", -123405},
		{"0.00", "USD", 0},
		{"1025", "JPY", 1025},
		{"-7000", "JPY", -7000},
		{"1.234", "KWD", 1234},
	} {
		if got := FormatMinor(c.minor, c.currency); got != c.text {
			t.Errorf("FormatMinor(%d, %s) = %q, want %q", c.minor, c.currency, got, c.text)
		}
		if got, err := ParseMinor(c.text, c.currency); err != nil || got != c.minor {
			t.Errorf("ParseMinor(%q, %s) = %d, %v; want %d", c.text, c.currency, got, err, c.minor)
		}
	}
	// Shorter forms are fine; anything that would need rounding or guessing is not.
	for text, want := range map[string]int64{"12.5": 1250, "12": 1200, " 3 ": 300} {
		if got, err := ParseMinor(text, "USD"); err != nil || got != want {
			t.Errorf("ParseMinor(%q) = %d, %v; want %d", text, got, err, want)
		}
	}
	for _, bad := range []string{"", "-", "12.505", "12.", ".5", "1,000.00", "1e3", "+5", "--1", "12.-5", "99999999999999999"} {
		if _, err := ParseMinor(bad, "USD"); err == nil {
			t.Errorf("ParseMinor(%q) accepted", bad)
		}
	}
	if _, err := ParseMinor("10.5", "JPY"); err == nil {
		t.Error("ParseMinor accepted decimals for yen")
	}
}
