package sample

import (
	"testing"
	"time"
)

// The home page hides "Spending by category" when this month has no spending,
// so the demo needs some from the very start of the month.
func TestSpendingFromTheFirstMinuteOfTheMonth(t *testing.T) {
	for _, now := range []time.Time{
		time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC), // a Sunday
		time.Date(2026, 11, 1, 2, 22, 0, 0, time.UTC),
		time.Date(2027, 1, 1, 0, 30, 0, 0, time.FixedZone("JST", 9*60*60)),
	} {
		month := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, now.Location())
		spent := false
		for _, r := range New(now, 6, "en").Records {
			if r.Kind == "expense" && !r.At.Before(month) {
				spent = true
			}
		}
		if !spent {
			t.Errorf("no spending this month at %v", now)
		}
	}
}
