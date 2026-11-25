package recurring

import (
	"slices"
	"strings"
	"time"
)

// Date is a calendar day in the user's time zone, as YYYY-MM-DD in JSON.
type Date struct{ time.Time }

func dateOf(t time.Time) Date {
	return Date{time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)}
}

func parseDate(s string) (Date, error) {
	t, err := time.Parse(time.DateOnly, s)
	return Date{t}, err
}

func (d Date) String() string { return d.Format(time.DateOnly) }

func (d Date) MarshalJSON() ([]byte, error) { return []byte(`"` + d.String() + `"`), nil }

func (d *Date) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	t, err := parseDate(s)
	*d = t
	return err
}

func (d Date) before(o Date) bool { return d.Before(o.Time) }

func (d Date) addDays(n int) Date { return Date{d.AddDate(0, 0, n)} }

// addMonths moves n months on, on the given day of the month, or the
// month's last day if it has fewer days (a bill on the 31st is due on the
// 30th in November).
func (d Date) addMonths(n, day int) Date {
	first := time.Date(d.Year(), d.Month()+time.Month(n), 1, 0, 0, 0, 0, time.UTC)
	last := first.AddDate(0, 1, -1).Day()
	return Date{first.AddDate(0, 0, min(day, last)-1)}
}

// Payment is a record that is, or could be, a recurring payment's.
type Payment struct {
	TransactionID int64  `json:"transaction_id"`
	Date          Date   `json:"date"`
	Amount        int64  `json:"amount"`
	Label         string `json:"label"`
	// Confirmed is false for a record still to review: not yet in the
	// month's spending.
	Confirmed bool `json:"confirmed"`

	kind       string // expense or income
	key        string // wallet.PayeeKey(Label)
	categoryID *int64
	accountID  int64
}

// label is what a record is matched by: its payee, or its note when it has
// none (records imported from BudgetBakers name the shop in the note).
func label(payee, note string) string {
	if p := strings.TrimSpace(payee); p != "" {
		return p
	}
	return strings.TrimSpace(note)
}

// recordKind is the kind of record a recurring payment's payments are.
func recordKind(kind string) string {
	if kind == "income" {
		return "income"
	}
	return "expense"
}

// fits reports whether the record is one of r's payments: the same kind,
// a text r matches in its label, and an amount near r's. A bill's amount
// moves with the season, a subscription's hardly at all (a price change, or
// one charged in another currency).
func (r *Recurring) fits(p Payment) bool {
	if p.kind != recordKind(r.Kind) || p.key == "" {
		return false
	}
	lo, hi := r.Amount/2, r.Amount*2
	if r.Kind == "bill" {
		lo, hi = r.Amount/5, r.Amount*5
	}
	if p.Amount < lo || p.Amount > hi {
		return false
	}
	for _, m := range r.Matches {
		if m != "" && strings.Contains(p.key, m) {
			return true
		}
	}
	return false
}

// estimate is what the next payment should be: for a bill, the average of
// its last three; else its last; with no payments, the amount given.
func estimate(kind string, amount int64, payments []Payment) int64 {
	if len(payments) == 0 {
		return amount
	}
	if kind != "bill" {
		return payments[0].Amount
	}
	n := min(3, len(payments))
	var sum int64
	for _, p := range payments[:n] {
		sum += p.Amount
	}
	return (sum + int64(n)/2) / int64(n)
}

// halfPeriod is how late a payment can be before it's taken as skipped.
func halfPeriod(months int) int { return months * 15 }

// nextDue is when the next payment is expected: a period after the last
// one, or from the start date with none. One more than half a period
// overdue is taken as skipped (a month without the bill, a cancelled
// subscription) and the next one after it is given.
func nextDue(months int, start Date, last *Date, today Date) Date {
	next, day := start, start.Day()
	if last != nil {
		day = last.Day()
		next = last.addMonths(months, day)
	}
	for next.addDays(halfPeriod(months)).before(today) {
		next = next.addMonths(months, day)
	}
	return next
}

// Status works out r's payments, estimate and next due date from the
// records, newest first.
func (r *Recurring) status(records []Payment, today Date) {
	r.Payments = []Payment{}
	for _, p := range records {
		if r.fits(p) {
			r.Payments = append(r.Payments, p)
		}
	}
	r.Estimate = estimate(r.Kind, r.Amount, r.Payments)
	var last *Date
	if len(r.Payments) > 0 {
		r.LastPaid = &r.Payments[0]
		last = &r.Payments[0].Date
	}
	next := nextDue(r.Months, r.StartDate, last, today)
	r.NextDue = &next
	r.Late = next.before(today)
	if len(r.Payments) > maxPayments {
		r.Payments = r.Payments[:maxPayments]
	}
}

// maxPayments is how many of its payments a recurring payment shows.
const maxPayments = 12

// Due is a payment expected in the month and not made yet.
type Due struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	Date   Date   `json:"date"`
	Amount int64  `json:"amount"`
	Late   bool   `json:"late"`
}

// Month is what the recurring expenses (subscriptions and bills) do to the
// month going on: what they've taken already and what they will take, so
// the rest of the month can be estimated from everything else.
type Month struct {
	Start Date `json:"start"` // the first day
	End   Date `json:"end"`   // the first day of the next month
	// Paid is their confirmed payments this month: spending already counted.
	Paid int64 `json:"paid"`
	// Upcoming is what's still expected this month: Due summed.
	Upcoming int64 `json:"upcoming"`
	Due      []Due `json:"due"`
}

// month totals the active recurring expenses for the month of today.
// They must have their status worked out.
func month(list []Recurring, today Date) Month {
	start := Date{time.Date(today.Year(), today.Month(), 1, 0, 0, 0, 0, time.UTC)}
	end := Date{start.AddDate(0, 1, 0)}
	m := Month{Start: start, End: end, Due: []Due{}}
	for i := range list {
		r := &list[i]
		if !r.Active || r.Kind == "income" || r.NextDue == nil {
			continue
		}
		for _, p := range r.Payments {
			if p.Confirmed && !p.Date.before(start) && p.Date.before(end) {
				m.Paid += p.Amount
			}
			// A payment still to review isn't spending yet: it's still to come.
			if !p.Confirmed && !p.Date.before(start) && p.Date.before(end) {
				m.Due = append(m.Due, Due{ID: r.ID, Name: r.Name, Date: p.Date, Amount: p.Amount})
				m.Upcoming += p.Amount
			}
		}
		// The next one, even if it's late from last month, and any after it
		// this month (none, unless it's very late).
		day := r.NextDue.Day()
		for d := *r.NextDue; d.before(end); d = d.addMonths(r.Months, day) {
			m.Due = append(m.Due, Due{ID: r.ID, Name: r.Name, Date: d, Amount: r.Estimate, Late: d.before(today)})
			m.Upcoming += r.Estimate
		}
	}
	slices.SortStableFunc(m.Due, func(a, b Due) int { return a.Date.Compare(b.Date.Time) })
	return m
}
