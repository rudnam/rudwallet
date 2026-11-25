package recurring

import (
	"cmp"
	"slices"
	"strings"
	"unicode/utf8"
)

// Suggestion is a recurring payment found in the user's records, to add
// with one tap: POST /api/recurring with its fields.
type Suggestion struct {
	// Key names it for dismissing (POST /api/recurring/dismiss).
	Key        string    `json:"key"`
	Name       string    `json:"name"`
	Kind       string    `json:"kind"`
	Matches    []string  `json:"matches"`
	Amount     int64     `json:"amount"`
	Months     int       `json:"months"`
	StartDate  Date      `json:"start_date"` // the next one due
	CategoryID *int64    `json:"category_id"`
	AccountID  *int64    `json:"account_id"`
	Payments   []Payment `json:"payments"`
}

// Finding recurring payments. Records are grouped by what they're matched
// by (their payee, or note), and a group is a recurring payment when it
// comes back regularly: in most months (or every second or third month), on
// about the same day of the month, about once each time.
const (
	// minMonths is how many times a subscription (the same amount each
	// time) must have been paid; a bill, whose amount moves, once more.
	minMonths = 3
	// nearDays is how far from its usual day of the month a payment can be.
	nearDays = 5
	// sameAmount is how far apart, as a share, a subscription's amounts can be.
	sameAmount = 0.02
	// minAmount leaves out points and cashback (a few yen back each time).
	minAmount = 100
)

type group struct {
	kind    string
	keys    []string
	records []Payment // newest first
}

func (g *group) amounts() []int64 {
	out := make([]int64, len(g.records))
	for i, r := range g.records {
		out[i] = r.Amount
	}
	return out
}

// stable reports whether the amounts are all within sameAmount of each other.
func stable(amounts []int64) bool {
	if len(amounts) == 0 {
		return false
	}
	lo, hi := slices.Min(amounts), slices.Max(amounts)
	return float64(hi-lo) <= sameAmount*float64(hi)
}

func median(xs []int64) int64 {
	s := slices.Clone(xs)
	slices.Sort(s)
	return s[len(s)/2]
}

// dayDistance is how many days apart two days of the month are, going round
// the month's end: the 30th and the 2nd are 3 apart.
func dayDistance(a, b int) int {
	d := a - b
	if d < 0 {
		d = -d
	}
	return min(d, 31-d)
}

// usualDay is the day of the month most records are near, and how many are.
func usualDay(records []Payment) (day, near int) {
	for c := 1; c <= 31; c++ {
		n := 0
		for _, r := range records {
			if dayDistance(r.Date.Day(), c) <= nearDays {
				n++
			}
		}
		if n > near {
			day, near = c, n
		}
	}
	return day, near
}

// prefix is a key's first three characters, ignoring anything but letters
// and digits: names a service goes by in different sources start alike
// ("netflix - netflix.com", "netflix").
func prefix(key string) string {
	var b strings.Builder
	n := 0
	for _, r := range key {
		if r == ' ' || r == '-' || r == '*' || r == '.' {
			continue
		}
		b.WriteRune(r)
		if n++; n == 3 {
			break
		}
	}
	return b.String()
}

// Suggest finds recurring payments in records (newest first) that aren't
// tracked yet (in list) or dismissed.
func Suggest(records []Payment, list []Recurring, dismissed map[string]bool, today Date) []Suggestion {
	byKey := map[string]*group{}
	var groups []*group
	for _, r := range records {
		if r.key == "" {
			continue
		}
		k := r.kind + "\x00" + r.key
		g := byKey[k]
		if g == nil {
			g = &group{kind: r.kind, keys: []string{r.key}}
			byKey[k] = g
			groups = append(groups, g)
		}
		g.records = append(g.records, r)
	}

	// One subscription can come under several names (an import and a
	// notification name it differently, or the shop changes how it shows):
	// a group whose records are all a subscription's amount, on its day,
	// under a name like its own, joins it.
	slices.SortStableFunc(groups, func(a, b *group) int { return cmp.Compare(len(b.records), len(a.records)) })
	joined := map[*group]bool{}
	for _, core := range groups {
		if joined[core] || len(core.records) < 2 || !stable(core.amounts()) {
			continue
		}
		amount := median(core.amounts())
		day, _ := usualDay(core.records)
		for _, g := range groups {
			if g == core || joined[g] || g.kind != core.kind || len(g.records) > len(core.records) ||
				prefix(g.keys[0]) != prefix(core.keys[0]) {
				continue
			}
			fits := true
			for _, r := range g.records {
				if !stable([]int64{r.Amount, amount}) || dayDistance(r.Date.Day(), day) > nearDays {
					fits = false
					break
				}
			}
			if fits {
				joined[g] = true
				core.keys = append(core.keys, g.keys...)
				core.records = append(core.records, g.records...)
			}
		}
		slices.SortStableFunc(core.records, func(a, b Payment) int { return b.Date.Compare(a.Date.Time) })
	}

	out := []Suggestion{}
	for _, g := range groups {
		if joined[g] {
			continue
		}
		if s, ok := suggestion(g, today); ok && !dismissed[s.Key] && !tracked(list, g) {
			out = append(out, s)
		}
	}
	slices.SortStableFunc(out, func(a, b Suggestion) int {
		if a.Kind != b.Kind {
			return cmp.Compare(kindOrder(a.Kind), kindOrder(b.Kind))
		}
		return cmp.Compare(b.Amount, a.Amount)
	})
	return out
}

func kindOrder(kind string) int { return strings.Index("subscription bill income", kind) }

// tracked reports whether a recurring payment already takes the group's
// latest records.
func tracked(list []Recurring, g *group) bool {
	for i := range list {
		for _, r := range g.records[:min(3, len(g.records))] {
			if list[i].fits(r) {
				return true
			}
		}
	}
	return false
}

// occurrences are the dates a group was paid on, oldest first. Records a
// few days apart that are on the same day or for the same amount are one
// payment: one paid in two parts (points and card), or recorded twice.
func occurrences(records []Payment) []Date {
	var out []Date
	for i := len(records) - 1; i >= 0; i-- {
		r := records[i]
		if len(out) > 0 {
			prev := records[i+1]
			if !prev.Date.addDays(dupDays).before(r.Date) && (prev.Date == r.Date || prev.Amount == r.Amount) {
				continue
			}
		}
		out = append(out, r.Date)
	}
	return out
}

const (
	// dupDays is how far apart two records of one payment can be.
	dupDays = 3
	// shortGap is closer than a recurring payment's payments come: two
	// payments this close (bus fares, a shop) aren't one.
	shortGap = 15
)

func suggestion(g *group, today Date) (Suggestion, bool) {
	// Leave out what's far from the usual amount: a few yen paid with
	// points beside the card, or something else bought at the same place.
	usual := median(g.amounts())
	g.records = slices.DeleteFunc(slices.Clone(g.records), func(r Payment) bool {
		return r.Amount < usual/5 || r.Amount > usual*5
	})
	if len(g.records) == 0 {
		return Suggestion{}, false
	}
	recent := g.amounts()[:min(4, len(g.records))]
	kind := "bill"
	if g.kind == "income" {
		kind = "income"
	} else if stable(recent) {
		kind = "subscription"
	}
	need := minMonths
	if !stable(recent) {
		need++
	}
	occ := occurrences(g.records)
	if len(occ) < need || median(g.amounts()) < minAmount {
		return Suggestion{}, false
	}

	// How often: the most common gap, in months, between the last eight
	// payments, in at least half of them (and at least as many times as a
	// subscription or bill needs payments, less one); and not paid twice
	// in two weeks more than once.
	occ = occ[max(0, len(occ)-8):]
	count := map[int]int{}
	gaps, short := 0, 0
	for i := 1; i < len(occ); i++ {
		days := int(occ[i].Sub(occ[i-1].Time).Hours()/24 + 0.5)
		if days < shortGap {
			short++
			continue
		}
		count[int(float64(days)/30.44+0.5)]++
		gaps++
	}
	every := 0
	for _, m := range []int{1, 2, 3} {
		if count[m] > count[every] {
			every = m
		}
	}
	if short > 1 || every == 0 || count[every]*2 < gaps || count[every] < need-1 {
		return Suggestion{}, false
	}
	// On about the same day each time, lately.
	latest := g.records[:min(6, len(g.records))]
	if _, near := usualDay(latest); near*3 < len(latest)*2 {
		return Suggestion{}, false
	}
	// And still going: the last no more than about a period ago.
	last := g.records[0]
	if last.Date.addMonths(every, last.Date.Day()).addDays(halfPeriod(every)).before(today) {
		return Suggestion{}, false
	}

	s := Suggestion{
		Key:        g.kind + ":" + g.keys[0],
		Name:       name(g.records),
		Kind:       kind,
		Matches:    matches(g.keys),
		Months:     every,
		StartDate:  nextDue(every, last.Date, &last.Date, today),
		CategoryID: last.categoryID,
		Payments:   g.records[:min(maxPayments, len(g.records))],
	}
	s.Amount = estimate(kind, 0, g.records)
	if last.accountID != 0 {
		s.AccountID = &last.accountID
	}
	return s, true
}

// matches are the keys to match, leaving out any another one is part of.
func matches(keys []string) []string {
	var out []string
	for _, k := range keys {
		redundant := false
		for _, o := range keys {
			if o != k && strings.Contains(k, o) {
				redundant = true
			}
		}
		if !redundant && !slices.Contains(out, k) {
			out = append(out, k)
		}
	}
	return out
}

// name is what most of the records call it: the brand of a "brand - shop"
// payee, in half-width letters; the shortest if there's a tie.
func name(records []Payment) string {
	count := map[string]int{}
	best := ""
	for _, r := range records {
		n := r.Label
		if i := strings.Index(n, " - "); i > 0 {
			n = n[:i]
		}
		n = strings.TrimRight(narrow(n), "*・ ")
		count[n]++
		c, b := count[n], count[best]
		if best == "" || c > b || (c == b && utf8.RuneCountInString(n) < utf8.RuneCountInString(best)) {
			best = n
		}
	}
	if utf8.RuneCountInString(best) > maxName {
		best = string([]rune(best)[:maxName])
	}
	return best
}

// narrow turns full-width letters and digits into ASCII, as PayeeKey does
// but keeping their case: "Ａｍａｚｏｎ" is shown as "Amazon".
func narrow(s string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 0xFF01 && r <= 0xFF5E:
			return r - 0xFEE0
		case r == 0x3000:
			return ' '
		}
		return r
	}, strings.TrimSpace(s))
}
