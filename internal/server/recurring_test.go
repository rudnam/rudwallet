package server_test

import (
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/recurring"
)

func TestRecurring(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	o := overview(t, alice)
	cash := o.Accounts[0].ID
	subs := categoryID(t, o, "Entertainment")
	salary := categoryID(t, o, "Salary")
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	now := time.Now().Add(-time.Minute)

	record := func(payee string, amount int64, at time.Time) {
		t.Helper()
		body := map[string]any{"kind": "expense", "amount": amount, "account_id": cash, "category_id": subs,
			"occurred_at": at, "payee": payee}
		if code := alice.do("POST", "/api/transactions", body, nil); code != 201 {
			t.Fatalf("record %s: %d", payee, code)
		}
	}
	// Netflix every month for five months, the latest a minute ago; a café now and then.
	for k := range 5 {
		record("Netflix - ネットフリックス", 1590, now.AddDate(0, -k, 0))
	}
	record("Café", 800, now.AddDate(0, 0, -3))
	record("Café", 650, now.AddDate(0, 0, -10))

	list := func(c *client) recurring.Overview {
		t.Helper()
		var o recurring.Overview
		if code := c.do("GET", "/api/recurring?tz=Asia/Tokyo", nil, &o); code != 200 {
			t.Fatalf("list: %d", code)
		}
		return o
	}

	// It's suggested, with what it costs and when it's next due.
	got := list(alice)
	if len(got.Recurring) != 0 || len(got.Suggestions) != 1 {
		t.Fatalf("recurring %d, suggestions %+v", len(got.Recurring), got.Suggestions)
	}
	s := got.Suggestions[0]
	// A month on, on the same day, or the month's last if it has fewer.
	today := now.In(tokyo)
	first := time.Date(today.Year(), today.Month()+1, 1, 0, 0, 0, 0, time.UTC)
	nextMonth := first.AddDate(0, 0, min(today.Day(), first.AddDate(0, 1, -1).Day())-1).Format(time.DateOnly)
	if s.Name != "Netflix" || s.Kind != "subscription" || s.Amount != 1590 || s.Months != 1 ||
		s.StartDate.String() != nextMonth || *s.CategoryID != subs || len(s.Payments) != 5 {
		t.Errorf("suggestion %+v", s)
	}

	// Added as suggested, it has the payments and isn't suggested again.
	var added recurring.Recurring
	body := map[string]any{"name": s.Name, "kind": s.Kind, "matches": s.Matches, "amount": s.Amount,
		"months": s.Months, "start_date": s.StartDate, "category_id": s.CategoryID, "account_id": s.AccountID}
	if code := alice.do("POST", "/api/recurring", body, &added); code != 201 || added.ID == 0 || !added.Active {
		t.Fatalf("add: %d %+v", code, added)
	}
	got = list(alice)
	if len(got.Suggestions) != 0 || len(got.Recurring) != 1 {
		t.Fatalf("after adding: %+v", got)
	}
	r := got.Recurring[0]
	if len(r.Payments) != 5 || r.Estimate != 1590 || r.NextDue.String() != nextMonth || r.Late {
		t.Errorf("recurring %+v", r)
	}
	// Paid this month already, so nothing more is due this month.
	if got.Month.Paid != 1590 || got.Month.Upcoming != 0 || len(got.Month.Due) != 0 {
		t.Errorf("month %+v", got.Month)
	}

	// A bill with no payments yet, due later this month (or early next, near
	// the month's end), named by hand; its name is what it matches.
	due := now.In(tokyo).AddDate(0, 0, 1)
	var water recurring.Recurring
	if code := alice.do("POST", "/api/recurring", map[string]any{"name": "  Water ", "kind": "bill", "amount": 2000,
		"months": 2, "start_date": due.Format(time.DateOnly)}, &water); code != 201 {
		t.Fatalf("water: %d", code)
	}
	if water.Name != "Water" || len(water.Matches) != 1 || water.Matches[0] != "water" {
		t.Errorf("water %+v", water)
	}
	got = list(alice)
	if due.Month() == now.In(tokyo).Month() && (got.Month.Upcoming != 2000 || len(got.Month.Due) != 1 || got.Month.Due[0].Name != "Water") {
		t.Errorf("water due this month: %+v", got.Month)
	}

	// Paused, it's no longer expected.
	paused := map[string]any{"name": water.Name, "kind": water.Kind, "matches": water.Matches, "amount": water.Amount,
		"months": water.Months, "start_date": water.StartDate, "active": false}
	if code := alice.do("PUT", "/api/recurring/"+itoa(water.ID), paused, nil); code != 200 {
		t.Fatalf("pause: %d", code)
	}
	if got = list(alice); got.Month.Upcoming != 0 {
		t.Errorf("paused, still due: %+v", got.Month)
	}

	// Refused: a bad kind, a category of the wrong kind, too often.
	bad := []map[string]any{
		{"name": "X", "kind": "weekly", "amount": 1},
		{"name": "X", "kind": "income", "amount": 1, "category_id": subs},
		{"name": "X", "kind": "bill", "amount": 1, "months": 4},
		{"name": "", "kind": "bill", "amount": 1},
		{"name": "X", "kind": "bill", "amount": 0},
	}
	for _, b := range bad {
		if code := alice.do("POST", "/api/recurring", b, nil); code != 400 {
			t.Errorf("%v: %d", b, code)
		}
	}
	if code := alice.do("POST", "/api/recurring", map[string]any{"name": "Pay", "kind": "income", "amount": 200000, "category_id": salary}, nil); code != 201 {
		t.Errorf("income: %d", code)
	}

	// Another user sees none of it and can't change it.
	if got := list(bob); len(got.Recurring) != 0 || len(got.Suggestions) != 0 {
		t.Errorf("bob sees %+v", got)
	}
	if code := bob.do("PUT", "/api/recurring/"+itoa(added.ID), body, nil); code != 404 {
		t.Errorf("bob's update: %d", code)
	}
	if code := bob.do("DELETE", "/api/recurring/"+itoa(added.ID), nil, nil); code != 404 {
		t.Errorf("bob's delete: %d", code)
	}

	// Deleted, it's suggested again; dismissed, it isn't.
	if code := alice.do("DELETE", "/api/recurring/"+itoa(added.ID), nil, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	got = list(alice)
	if len(got.Suggestions) != 1 {
		t.Fatalf("not suggested again: %+v", got.Suggestions)
	}
	if code := alice.do("POST", "/api/recurring/dismiss", map[string]string{"key": got.Suggestions[0].Key}, nil); code != 204 {
		t.Fatalf("dismiss: %d", code)
	}
	if got = list(alice); len(got.Suggestions) != 0 {
		t.Errorf("dismissed, still suggested: %+v", got.Suggestions)
	}

	if code := alice.do("GET", "/api/recurring?tz=Mars/Olympus", nil, nil); code != 400 {
		t.Errorf("unknown zone: %d", code)
	}
}
