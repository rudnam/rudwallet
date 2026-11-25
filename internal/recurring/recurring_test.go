package recurring

import (
	"slices"
	"testing"

	"github.com/rudnam/wallet-app/internal/wallet"
)

func day(s string) Date {
	d, err := parseDate(s)
	if err != nil {
		panic(err)
	}
	return d
}

// pay is an expense record on a day, newest-first order left to the caller.
func pay(id int64, date string, amount int64, payee string) Payment {
	return Payment{TransactionID: id, Date: day(date), Amount: amount, Label: payee, Confirmed: true,
		kind: "expense", key: wallet.PayeeKey(payee), accountID: 1}
}

func newestFirst(ps []Payment) []Payment {
	slices.SortStableFunc(ps, func(a, b Payment) int { return b.Date.Compare(a.Date.Time) })
	return ps
}

func TestAddMonthsKeepsTheDay(t *testing.T) {
	cases := []struct {
		from     string
		n, day   int
		expected string
	}{
		{"2026-01-31", 1, 31, "2026-02-28"},
		{"2026-02-28", 1, 31, "2026-03-31"},
		{"2026-11-15", 2, 15, "2027-01-15"},
		{"2026-08-29", 12, 29, "2027-08-29"},
	}
	for _, c := range cases {
		if got := day(c.from).addMonths(c.n, c.day).String(); got != c.expected {
			t.Errorf("%s + %d months on the %d = %s, want %s", c.from, c.n, c.day, got, c.expected)
		}
	}
}

func TestNextDue(t *testing.T) {
	last := day("2026-11-02")
	if got := nextDue(1, day("2026-01-01"), &last, day("2026-11-26")); got != day("2026-12-02") {
		t.Errorf("a month after the last: %s", got)
	}
	// Late, but not yet half a month: still the one expected.
	last = day("2026-10-13")
	if got := nextDue(1, day("2026-01-01"), &last, day("2026-11-26")); got != day("2026-11-13") {
		t.Errorf("late: %s", got)
	}
	// More than half a month late: taken as skipped.
	if got := nextDue(1, day("2026-01-01"), &last, day("2026-12-05")); got != day("2026-12-13") {
		t.Errorf("skipped: %s", got)
	}
	// No payments yet: the start date, or the next one after it.
	if got := nextDue(12, day("2026-08-29"), nil, day("2026-11-26")); got != day("2026-08-29") {
		t.Errorf("yearly, three months late: %s", got)
	}
	if got := nextDue(12, day("2026-08-29"), nil, day("2027-03-01")); got != day("2027-08-29") {
		t.Errorf("yearly, skipped: %s", got)
	}
	if got := nextDue(1, day("2026-12-10"), nil, day("2026-11-26")); got != day("2026-12-10") {
		t.Errorf("starting later: %s", got)
	}
}

func TestStatusMatchesTextAndAmount(t *testing.T) {
	r := Recurring{Kind: "subscription", Matches: []string{"netflix"}, Amount: 1590, Months: 1, StartDate: day("2026-01-06"), Active: true}
	records := newestFirst([]Payment{
		pay(1, "2026-09-06", 1590, "Netflix - ネットフリックス"),
		pay(2, "2026-10-06", 1590, "ｎｅｔｆｌｉｘ．ｃｏｍ"),
		pay(3, "2026-10-20", 9000, "Netflix gift card"), // far from its amount
		pay(4, "2026-11-06", 1490, "NETFLIX"),
		pay(5, "2026-11-07", 1590, "Spotify"),
	})
	income := pay(6, "2026-11-06", 15, "Netflix")
	income.kind = "income" // points back: not a payment
	records = append(records, income)

	r.status(records, day("2026-11-26"))
	var ids []int64
	for _, p := range r.Payments {
		ids = append(ids, p.TransactionID)
	}
	if !slices.Equal(ids, []int64{4, 2, 1}) {
		t.Fatalf("payments %v, want [4 2 1]", ids)
	}
	if r.Estimate != 1490 || *r.NextDue != day("2026-12-06") || r.Late {
		t.Errorf("estimate %d, next %s, late %v", r.Estimate, r.NextDue, r.Late)
	}

	// A bill is expected to cost the average of its last three.
	b := Recurring{Kind: "bill", Matches: []string{"中部電力"}, Amount: 3000, Months: 1, StartDate: day("2026-01-01"), Active: true}
	b.status(newestFirst([]Payment{
		pay(1, "2026-08-01", 4617, "中部電力ミライズ"),
		pay(2, "2026-09-03", 6433, "中部電力ミライズ"),
		pay(3, "2026-09-03", 26, "中部電力ミライズ"), // the points part: too small to be the bill
		pay(4, "2026-10-28", 2798, "中部電力ミライズ"),
	}), day("2026-11-10"))
	if len(b.Payments) != 3 || b.Estimate != 4616 || *b.NextDue != day("2026-11-28") {
		t.Errorf("bill: %d payments, estimate %d, next %s", len(b.Payments), b.Estimate, b.NextDue)
	}
}

func TestMonth(t *testing.T) {
	today := day("2026-11-20")
	records := newestFirst([]Payment{
		pay(1, "2026-10-02", 600, "Amazon Prime"),
		pay(2, "2026-11-02", 600, "Amazon Prime"),
		pay(3, "2026-10-28", 2800, "中部電力"),
		pay(4, "2026-10-13", 1280, "YouTube Premium"),
		pay(5, "2026-09-30", 7000, "SoftBank"),
		pay(6, "2026-10-30", 7100, "SoftBank"),
	})
	review := pay(7, "2026-11-19", 7050, "SoftBank")
	review.Confirmed = false // waiting to be reviewed: not counted in spending yet
	records = newestFirst(append(records, review))

	list := []Recurring{
		{ID: 1, Name: "Prime", Kind: "subscription", Matches: []string{"amazon prime"}, Amount: 600, Months: 1, Active: true},
		{ID: 2, Name: "Electricity", Kind: "bill", Matches: []string{"中部電力"}, Amount: 3000, Months: 1, Active: true},
		{ID: 3, Name: "YouTube", Kind: "subscription", Matches: []string{"youtube"}, Amount: 1280, Months: 1, Active: true},
		{ID: 4, Name: "SoftBank", Kind: "bill", Matches: []string{"softbank"}, Amount: 7000, Months: 1, Active: true},
		{ID: 5, Name: "Paused", Kind: "subscription", Matches: []string{"gym"}, Amount: 7000, Months: 1, StartDate: day("2026-11-25"), Active: false},
	}
	for i := range list {
		list[i].status(records, today)
	}
	m := month(list, today)
	if m.Start != day("2026-11-01") || m.End != day("2026-12-01") {
		t.Errorf("month %s to %s", m.Start, m.End)
	}
	if m.Paid != 600 {
		t.Errorf("paid %d, want 600 (Prime)", m.Paid)
	}
	var got []string
	for _, d := range m.Due {
		got = append(got, d.Date.String()+" "+d.Name)
	}
	want := []string{"2026-11-13 YouTube", "2026-11-19 SoftBank", "2026-11-28 Electricity"}
	if !slices.Equal(got, want) {
		t.Errorf("due %q, want %q", got, want)
	}
	if !m.Due[0].Late || m.Upcoming != 1280+7050+2800 {
		t.Errorf("late %v, upcoming %d", m.Due[0].Late, m.Upcoming)
	}
}

// monthly makes a payment on the given day of each month from 2026-01 to
// 2026-11, newest first.
func monthly(id int64, dom int, amounts func(m int) int64, payee string) []Payment {
	var out []Payment
	for m := 1; m <= 11; m++ {
		d := day("2026-01-01").addMonths(m-1, dom)
		out = append(out, pay(id*100+int64(m), d.String(), amounts(m), payee))
	}
	return newestFirst(out)
}

func TestSuggest(t *testing.T) {
	today := day("2026-11-26")
	var records []Payment
	// A subscription named two ways: an import until September, then a
	// notification.
	for _, p := range monthly(1, 2, func(int) int64 { return 600 }, "Amazon Prime") {
		if p.Date.Month() >= 10 {
			p.Label, p.key = "Amazon.co.jp - Ａｍａｚｏｎプライム会費", wallet.PayeeKey("Amazon.co.jp - Ａｍａｚｏｎプライム会費")
		}
		records = append(records, p)
	}
	// A bill around the month's end, its amount moving with the season.
	records = append(records, monthly(2, 30, func(m int) int64 { return 2500 + int64(m%5)*700 }, "中部電力ミライズ")...)
	// Bus fares: often, but not monthly.
	for i, d := range []string{"2026-08-12", "2026-08-16", "2026-08-24", "2026-09-21", "2026-10-13", "2026-11-01"} {
		records = append(records, pay(int64(300+i), d, 450, "ENTETSU BUS"))
	}
	// A café visited now and then.
	for i, d := range []string{"2026-05-01", "2026-07-27", "2026-10-10"} {
		records = append(records, pay(int64(400+i), d, int64(900+i*300), "mayone"))
	}
	// A cancelled subscription.
	for i, d := range []string{"2026-04-06", "2026-05-06", "2026-06-06", "2026-07-06"} {
		records = append(records, pay(int64(500+i), d, 1590, "Netflix"))
	}
	// Points back each month: too small.
	for i := 1; i <= 6; i++ {
		p := pay(int64(600+i), day("2026-05-10").addMonths(i-1, 10).String(), 50, "ワイモバイル")
		p.kind = "income"
		records = append(records, p)
	}
	records = slices.DeleteFunc(newestFirst(records), func(p Payment) bool { return today.before(p.Date) })

	got := Suggest(records, nil, nil, today)
	if len(got) != 2 {
		t.Fatalf("suggested %d: %+v", len(got), got)
	}
	prime, power := got[0], got[1]
	if prime.Name != "Amazon Prime" || prime.Kind != "subscription" || prime.Amount != 600 || prime.Months != 1 ||
		prime.StartDate != day("2026-12-02") || len(prime.Matches) != 2 || len(prime.Payments) != 11 {
		t.Errorf("prime: %+v", prime)
	}
	if power.Name != "中部電力ミライズ" || power.Kind != "bill" || power.StartDate != day("2026-11-30") {
		t.Errorf("electricity: %+v", power)
	}

	// Tracked or dismissed, it isn't suggested again.
	r := Recurring{Kind: "subscription", Matches: prime.Matches, Amount: 600, Months: 1, Active: true}
	got = Suggest(records, []Recurring{r}, map[string]bool{power.Key: true}, today)
	if len(got) != 0 {
		t.Errorf("suggested again: %+v", got)
	}
}
