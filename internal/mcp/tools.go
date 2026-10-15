package mcp

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// call is one tool call by a signed-in user.
type call struct {
	r        *http.Request
	h        *Handler
	userID   int64
	currency string
}

func (c *call) money(amount int64) (string, error) {
	if c.currency == "" {
		st, err := c.h.wallet.Settings(c.r.Context(), c.userID)
		if err != nil {
			return "", err
		}
		c.currency = st.Currency
	}
	return wallet.FormatMinor(amount, c.currency), nil
}

// toolError is a problem the model should see and can fix, e.g. a bad
// argument; other errors are logged and reported as internal.
type toolError struct{ msg string }

func (e *toolError) Error() string { return e.msg }

func toolErrorf(format string, args ...any) error { return &toolError{fmt.Sprintf(format, args...)} }

// walletErr turns the wallet store's expected errors into tool errors.
func walletErr(err error, what string) error {
	var inv *wallet.InvalidError
	switch {
	case errors.Is(err, db.ErrNotFound):
		return toolErrorf("no such %s", what)
	case errors.Is(err, wallet.ErrDuplicateName):
		return toolErrorf("there is already a %s with that name", what)
	case errors.As(err, &inv):
		return toolErrorf("%s", inv.Msg)
	}
	return err
}

var errBadID = errors.New("bad id")

func parseID(s string) (int64, error) {
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil || id <= 0 {
		return 0, errBadID
	}
	return id, nil
}

type tool struct {
	Name        string         `json:"name"`
	Title       string         `json:"title"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"inputSchema"`
	Annotations map[string]any `json:"annotations"`
	run         func(c *call, args json.RawMessage) (any, error)
}

// decode reads a tool's arguments into dst.
func decode(args json.RawMessage, dst any) error {
	if err := json.Unmarshal(args, dst); err != nil {
		return toolErrorf("bad arguments: %v", err)
	}
	return nil
}

func object(props map[string]any, required ...string) map[string]any {
	s := map[string]any{"type": "object", "properties": props, "additionalProperties": false}
	if len(required) > 0 {
		s["required"] = required
	}
	return s
}

var (
	readOnly = map[string]any{"readOnlyHint": true, "openWorldHint": false}
	changes  = map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}
	dateArg  = func(desc string) map[string]any {
		return map[string]any{"type": "string", "description": desc + " A date (2026-10-01, midnight UTC) or an RFC 3339 time with an offset (2026-10-01T00:00:00+09:00)."}
	}
	kindArg = map[string]any{"type": "string", "enum": []string{"expense", "income"}}
)

func parseTime(s, name string) (time.Time, error) {
	if s == "" {
		return time.Time{}, nil
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, nil
	}
	if t, err := time.Parse(time.DateOnly, s); err == nil {
		return t, nil
	}
	return time.Time{}, toolErrorf("%s must be a date like 2026-10-01 or an RFC 3339 time", name)
}

var toolList = []*tool{
	{
		Name:        "get_overview",
		Title:       "Overview",
		Description: "The user's currency, accounts with balances, categories (with IDs), and how many records await review or have no category.",
		InputSchema: object(map[string]any{}),
		Annotations: readOnly,
		run:         overview,
	},
	{
		Name:        "list_records",
		Title:       "List records",
		Description: "Records (expenses, income, transfers), newest first, optionally filtered.",
		InputSchema: object(map[string]any{
			"from":          dateArg("Only records at or after this time."),
			"to":            dateArg("Only records before this time."),
			"account_id":    map[string]any{"type": "integer"},
			"category_id":   map[string]any{"type": "integer"},
			"uncategorized": map[string]any{"type": "boolean", "description": "Only records without a category."},
			"query":         map[string]any{"type": "string", "description": "Text to find in the payee or note."},
			"status":        map[string]any{"type": "string", "enum": []string{"review", "confirmed"}, "description": "Only records waiting for review, or only confirmed ones."},
			"limit":         map[string]any{"type": "integer", "description": "At most this many (default 100, max 500)."},
		}),
		Annotations: readOnly,
		run:         listRecords,
	},
	{
		Name:  "list_uncategorized_payees",
		Title: "Uncategorised records by payee",
		Description: "Confirmed records without a category, grouped by payee (or by note when there is no payee), most frequent first. " +
			"File them with categorize_payees.",
		InputSchema: object(map[string]any{
			"offset": map[string]any{"type": "integer", "description": "Skip this many payees."},
			"limit":  map[string]any{"type": "integer", "description": "At most this many payees (default 100, max 500)."},
		}),
		Annotations: readOnly,
		run:         uncategorized,
	},
	{
		Name:  "categorize_payees",
		Title: "Categorise payees",
		Description: "Give every uncategorised record of each payee a category, and remember it for the payee's future records. " +
			"Use the exact payee and kind from list_uncategorized_payees, and a category of the same kind.",
		InputSchema: object(map[string]any{
			"items": map[string]any{
				"type": "array",
				"items": object(map[string]any{
					"payee":       map[string]any{"type": "string"},
					"kind":        kindArg,
					"category_id": map[string]any{"type": "integer"},
				}, "payee", "kind", "category_id"),
			},
		}, "items"),
		Annotations: changes,
		run:         categorize,
	},
	{
		Name:        "set_record_category",
		Title:       "Set a record's category",
		Description: "Change one record's category. category_id null removes it. Transfers have no category.",
		InputSchema: object(map[string]any{
			"record_id":   map[string]any{"type": "integer"},
			"category_id": map[string]any{"type": []string{"integer", "null"}},
		}, "record_id", "category_id"),
		Annotations: changes,
		run:         setCategory,
	},
	{
		Name:        "create_category",
		Title:       "Create a category",
		Description: "Add a category. Check get_overview first: reuse a fitting category rather than adding a near-duplicate.",
		InputSchema: object(map[string]any{
			"name": map[string]any{"type": "string", "description": "1 to 60 characters."},
			"kind": kindArg,
		}, "name", "kind"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false},
		run:         createCategory,
	},
	{
		Name:  "delete_category",
		Title: "Delete a category",
		Description: "Delete a category. Its records become uncategorised, and its budgets and remembered payees are deleted with it. " +
			"It can't be undone: ask the user first.",
		InputSchema: object(map[string]any{
			"category_id": map[string]any{"type": "integer"},
		}, "category_id"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false},
		run:         deleteCategory,
	},
	{
		Name:  "find_duplicates",
		Title: "Find likely duplicate records",
		Description: "Pairs of records of the same kind whose amounts and times are close, in any accounts (e.g. a cash entry and the card charge it repeats), " +
			"closest first: equal amounts, then the same payee, then nearest in time. Only reads; a record can be in several pairs. " +
			"Not every pair is a duplicate (two equal coffees on one day are two): show the pairs to the user, with each record's source, " +
			"and let them choose which record to delete, if any.",
		InputSchema: object(map[string]any{
			"from":                     dateArg("Only records at or after this time."),
			"to":                       dateArg("Only records before this time."),
			"account_id":               map[string]any{"type": "integer", "description": "Only pairs with a record in this account."},
			"max_days_apart":           map[string]any{"type": "integer", "description": "How many days apart the two may be (default 3, 0 to 31; 0 means within 12 hours)."},
			"amount_tolerance_percent": map[string]any{"type": "integer", "description": "How much the amounts may differ, as a percentage of the larger (default 5, 0 to 50; 0 means equal)."},
			"offset":                   map[string]any{"type": "integer", "description": "Skip this many pairs."},
			"limit":                    map[string]any{"type": "integer", "description": "At most this many pairs (default 50, max 200)."},
		}),
		Annotations: readOnly,
		run:         findDuplicates,
	},
	{
		Name:  "delete_records",
		Title: "Delete records",
		Description: "Delete records, all or none: if any ID is wrong, nothing is deleted. " +
			"A transfer is one record, so deleting it removes it from both its accounts. Balances change accordingly. " +
			"It can't be undone: list exactly which records will go (date, amount, payee, account) and ask the user to confirm first.",
		InputSchema: object(map[string]any{
			"record_ids": map[string]any{"type": "array", "items": map[string]any{"type": "integer"}, "minItems": 1, "maxItems": maxRecordIDs,
				"description": fmt.Sprintf("1 to %d record IDs.", maxRecordIDs)},
		}, "record_ids"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false},
		run:         deleteRecords,
	},
	{
		Name:  "set_account_balance",
		Title: "Correct an account's balance",
		Description: "Make an account's balance match a statement without changing any record: its starting balance is set so that it plus the " +
			"confirmed records before as_of comes to the given balance. Records waiting for review count only once confirmed; " +
			"if the statement includes them, confirm them first. The previous starting balance is returned, so it can be put back. " +
			"Confirm the figure and its date with the user first.",
		InputSchema: object(map[string]any{
			"account_id": map[string]any{"type": "integer"},
			"balance":    map[string]any{"type": "string", "description": "The statement's balance, a decimal in the user's currency, e.g. \"-1234.50\" for money owed on a card."},
			"as_of": dateArg("When the statement's balance was taken: records before this time count toward it, later ones are added on top. " +
				"For a statement closing on 30 September, give 2026-10-01 (or a time with the user's offset). Leave it out to count every record."),
		}, "account_id", "balance"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false},
		run:         setBalance,
	},
	{
		Name:  "confirm_records",
		Title: "Confirm records to review",
		Description: "Clear \"needs review\" on records the user has checked, as the app's review page does. Each needs an amount and, unless it is a transfer, " +
			"a category (set_record_category). Records flagged as a possible duplicate are left for review unless keep_duplicates is true. " +
			"Records that weren't confirmed are listed with the reason.",
		InputSchema: object(map[string]any{
			"record_ids": map[string]any{"type": "array", "items": map[string]any{"type": "integer"}, "minItems": 1, "maxItems": maxRecordIDs,
				"description": fmt.Sprintf("1 to %d record IDs.", maxRecordIDs)},
			"keep_duplicates": map[string]any{"type": "boolean", "description": "Also confirm records flagged as a possible duplicate, and clear the flag. Only once the user has said both records are real."},
		}, "record_ids"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false},
		run:         confirmRecords,
	},
	{
		Name:        "spending_by_category",
		Title:       "Spending by category",
		Description: "Confirmed expenses in a period, summed by category, largest first.",
		InputSchema: object(map[string]any{
			"from": dateArg("Start of the period."),
			"to":   dateArg("End of the period (not included)."),
		}, "from", "to"),
		Annotations: readOnly,
		run:         spending,
	},
}

var tools = func() map[string]*tool {
	m := map[string]*tool{}
	for _, t := range toolList {
		m[t.Name] = t
	}
	return m
}()

// ---------- The tools ----------

type outAccount struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
	Balance  string `json:"balance"`
	Archived bool   `json:"archived"`
}

type outCategory struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
}

func overview(c *call, _ json.RawMessage) (any, error) {
	ctx := c.r.Context()
	o, err := c.h.wallet.Overview(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	c.currency = o.Settings.Currency
	groups, err := c.h.wallet.UncategorizedGroups(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	uncategorized := 0
	for _, g := range groups {
		uncategorized += g.Count
	}
	accounts := []outAccount{}
	for _, a := range o.Accounts {
		bal, _ := c.money(a.Balance)
		accounts = append(accounts, outAccount{a.ID, a.Name, a.Kind, bal, a.Archived})
	}
	cats := []outCategory{}
	for _, cat := range o.Categories {
		cats = append(cats, outCategory{cat.ID, cat.Name, cat.Kind})
	}
	return map[string]any{
		"currency": o.Settings.Currency, "accounts": accounts, "categories": cats,
		"records_to_review": o.ReviewCount, "uncategorized_records": uncategorized,
	}, nil
}

type outRecord struct {
	ID         int64  `json:"id"`
	OccurredAt string `json:"occurred_at"`
	Kind       string `json:"kind"`
	Amount     string `json:"amount"`
	Account    string `json:"account"`
	ToAccount  string `json:"to_account,omitempty"`
	CategoryID *int64 `json:"category_id"`
	Category   string `json:"category,omitempty"`
	Payee      string `json:"payee"`
	Note       string `json:"note"`
	Status     string `json:"status"`
	// Source is where it came from: manual, import, email or notification.
	Source string `json:"source"`
	// DuplicateOfID is set while it waits for review as a possible
	// duplicate of that record.
	DuplicateOfID *int64 `json:"duplicate_of_id,omitempty"`
}

// recordNames looks up the account and category names records show.
type recordNames struct {
	accounts, categories map[int64]string
}

func (c *call) recordNames() (recordNames, error) {
	o, err := c.h.wallet.Overview(c.r.Context(), c.userID)
	if err != nil {
		return recordNames{}, err
	}
	c.currency = o.Settings.Currency
	n := recordNames{map[int64]string{}, map[int64]string{}}
	for _, a := range o.Accounts {
		n.accounts[a.ID] = a.Name
	}
	for _, cat := range o.Categories {
		n.categories[cat.ID] = cat.Name
	}
	return n, nil
}

func (c *call) record(n recordNames, t wallet.Transaction) outRecord {
	amount, _ := c.money(t.Amount)
	rec := outRecord{ID: t.ID, OccurredAt: t.OccurredAt.Format(time.RFC3339), Kind: t.Kind, Amount: amount,
		Account: n.accounts[t.AccountID], CategoryID: t.CategoryID, Payee: t.Payee, Note: t.Note, Status: t.Status,
		Source: t.Source, DuplicateOfID: t.DuplicateOfID}
	if t.ToAccountID != nil {
		rec.ToAccount = n.accounts[*t.ToAccountID]
	}
	if t.CategoryID != nil {
		rec.Category = n.categories[*t.CategoryID]
	}
	return rec
}

func (c *call) records(list []wallet.Transaction) ([]outRecord, error) {
	n, err := c.recordNames()
	if err != nil {
		return nil, err
	}
	out := []outRecord{}
	for _, t := range list {
		out = append(out, c.record(n, t))
	}
	return out, nil
}

func listRecords(c *call, args json.RawMessage) (any, error) {
	var in struct {
		From, To      string
		AccountID     int64 `json:"account_id"`
		CategoryID    int64 `json:"category_id"`
		Uncategorized bool
		Query         string
		Status        string
		Limit         int
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	if in.Status != "" && in.Status != "review" && in.Status != "confirmed" {
		return nil, toolErrorf("status must be review or confirmed")
	}
	f := wallet.Filter{AccountID: in.AccountID, CategoryID: in.CategoryID, Uncategorized: in.Uncategorized, Query: in.Query, Status: in.Status}
	var err error
	if f.From, err = parseTime(in.From, "from"); err != nil {
		return nil, err
	}
	if f.To, err = parseTime(in.To, "to"); err != nil {
		return nil, err
	}
	ctx := c.r.Context()
	list, err := c.h.wallet.List(ctx, c.userID, f)
	if err != nil {
		return nil, err
	}
	limit := in.Limit
	if limit <= 0 {
		limit = 100
	}
	limit = min(limit, 500)
	truncated := len(list) > limit
	list = list[:min(limit, len(list))]

	out, err := c.records(list)
	if err != nil {
		return nil, err
	}
	return map[string]any{"currency": c.currency, "records": out, "truncated": truncated}, nil
}

func uncategorized(c *call, args json.RawMessage) (any, error) {
	var in struct{ Offset, Limit int }
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	groups, err := c.h.wallet.UncategorizedGroups(c.r.Context(), c.userID)
	if err != nil {
		return nil, err
	}
	limit := in.Limit
	if limit <= 0 {
		limit = 100
	}
	limit = min(limit, 500)
	groups = groups[min(max(in.Offset, 0), len(groups)):]
	remaining := max(len(groups)-limit, 0)
	groups = groups[:min(limit, len(groups))]
	type payee struct {
		Payee   string `json:"payee"`
		Kind    string `json:"kind"`
		Records int    `json:"records"`
		Total   string `json:"total"`
		Note    string `json:"note,omitempty"`
	}
	out := []payee{}
	for _, g := range groups {
		total, err := c.money(g.Total)
		if err != nil {
			return nil, err
		}
		out = append(out, payee{g.Payee, g.Kind, g.Count, total, g.Note})
	}
	return map[string]any{"currency": c.currency, "payees": out, "remaining": remaining}, nil
}

func categorize(c *call, args json.RawMessage) (any, error) {
	var in struct {
		Items []wallet.PayeeCategory `json:"items"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	n, err := c.h.wallet.Categorize(c.r.Context(), c.userID, in.Items)
	if err != nil {
		return nil, walletErr(err, "category")
	}
	return map[string]int{"updated_records": n}, nil
}

func setCategory(c *call, args json.RawMessage) (any, error) {
	var in struct {
		RecordID   int64  `json:"record_id"`
		CategoryID *int64 `json:"category_id"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	t, err := c.h.wallet.SetCategory(c.r.Context(), c.userID, in.RecordID, in.CategoryID)
	if err != nil {
		return nil, walletErr(err, "record")
	}
	return map[string]any{"id": t.ID, "category_id": t.CategoryID}, nil
}

func createCategory(c *call, args json.RawMessage) (any, error) {
	var in struct{ Name, Kind string }
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(in.Name)
	if name == "" || utf8.RuneCountInString(name) > 60 {
		return nil, toolErrorf("a category name must be 1 to 60 characters")
	}
	if in.Kind != "expense" && in.Kind != "income" {
		return nil, toolErrorf("kind must be expense or income")
	}
	cat, err := c.h.wallet.CreateCategory(c.r.Context(), c.userID, wallet.Category{Name: name, Kind: in.Kind, Color: "#66706b"})
	if err != nil {
		return nil, walletErr(err, "category")
	}
	return outCategory{cat.ID, cat.Name, cat.Kind}, nil
}

func deleteCategory(c *call, args json.RawMessage) (any, error) {
	var in struct {
		CategoryID int64 `json:"category_id"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	if err := c.h.wallet.DeleteCategory(c.r.Context(), c.userID, in.CategoryID); err != nil {
		return nil, walletErr(err, "category")
	}
	return map[string]any{"deleted_category_id": in.CategoryID}, nil
}

func spending(c *call, args json.RawMessage) (any, error) {
	var in struct{ From, To string }
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	from, err := parseTime(in.From, "from")
	if err != nil {
		return nil, err
	}
	to, err := parseTime(in.To, "to")
	if err != nil {
		return nil, err
	}
	if from.IsZero() || to.IsZero() || !to.After(from) {
		return nil, toolErrorf("give a from and a later to")
	}
	ctx := c.r.Context()
	sums, err := c.h.wallet.Spending(ctx, c.userID, from, to)
	if err != nil {
		return nil, err
	}
	cats, err := c.h.wallet.Categories(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	names := map[int64]string{0: "(no category)"}
	for _, cat := range cats {
		names[cat.ID] = cat.Name
	}
	type row struct {
		CategoryID int64  `json:"category_id"`
		Category   string `json:"category"`
		Amount     string `json:"amount"`
		minor      int64
	}
	rows := []row{}
	var total int64
	for id, sum := range sums {
		total += sum
		rows = append(rows, row{CategoryID: id, Category: names[id], minor: sum})
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].minor > rows[j].minor })
	for i := range rows {
		if rows[i].Amount, err = c.money(rows[i].minor); err != nil {
			return nil, err
		}
	}
	totalText, err := c.money(total)
	if err != nil {
		return nil, err
	}
	return map[string]any{"currency": c.currency, "total": totalText, "categories": rows}, nil
}

// maxRecordIDs is the most records one call changes: a page of list_records.
const maxRecordIDs = 500

// recordIDs checks a tool's record IDs and drops repeats.
func recordIDs(ids []int64) ([]int64, error) {
	ids = slices.Clone(ids)
	slices.Sort(ids)
	ids = slices.Compact(ids)
	if len(ids) == 0 || len(ids) > maxRecordIDs {
		return nil, toolErrorf("give 1 to %d record IDs", maxRecordIDs)
	}
	if ids[0] <= 0 {
		return nil, toolErrorf("no such record: %d", ids[0])
	}
	return ids, nil
}

func findDuplicates(c *call, args json.RawMessage) (any, error) {
	var in struct {
		From, To         string
		AccountID        int64 `json:"account_id"`
		MaxDaysApart     *int  `json:"max_days_apart"`
		TolerancePercent *int  `json:"amount_tolerance_percent"`
		Offset, Limit    int
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	q := wallet.DuplicateQuery{AccountID: in.AccountID, MaxApart: 3 * 24 * time.Hour, TolerancePercent: 5}
	var err error
	if q.From, err = parseTime(in.From, "from"); err != nil {
		return nil, err
	}
	if q.To, err = parseTime(in.To, "to"); err != nil {
		return nil, err
	}
	if d := in.MaxDaysApart; d != nil {
		if *d < 0 || *d > 31 {
			return nil, toolErrorf("max_days_apart must be 0 to 31")
		}
		// 0 days apart means the same day, give or take a time zone.
		q.MaxApart = time.Duration(max(*d*24, 12)) * time.Hour
	}
	if p := in.TolerancePercent; p != nil {
		if *p < 0 || *p > 50 {
			return nil, toolErrorf("amount_tolerance_percent must be 0 to 50")
		}
		q.TolerancePercent = *p
	}
	pairs, truncated, err := c.h.wallet.Duplicates(c.r.Context(), c.userID, q)
	if err != nil {
		return nil, err
	}
	limit := in.Limit
	if limit <= 0 {
		limit = 50
	}
	limit = min(limit, 200)
	pairs = pairs[min(max(in.Offset, 0), len(pairs)):]
	remaining := max(len(pairs)-limit, 0)
	pairs = pairs[:min(limit, len(pairs))]

	n, err := c.recordNames()
	if err != nil {
		return nil, err
	}
	type pair struct {
		Records          [2]outRecord `json:"records"`
		AmountDifference string       `json:"amount_difference"`
		DaysApart        float64      `json:"days_apart"`
		SameAccount      bool         `json:"same_account"`
		SamePayee        bool         `json:"same_payee"`
		// Flagged: the app already marked one as a possible duplicate of the other.
		Flagged bool `json:"flagged"`
	}
	out := []pair{}
	for _, p := range pairs {
		diff, err := c.money(max(p.A.Amount-p.B.Amount, p.B.Amount-p.A.Amount))
		if err != nil {
			return nil, err
		}
		apart := p.A.OccurredAt.Sub(p.B.OccurredAt).Abs()
		out = append(out, pair{
			Records:          [2]outRecord{c.record(n, p.A), c.record(n, p.B)},
			AmountDifference: diff,
			DaysApart:        math.Round(apart.Hours()/24*10) / 10,
			SameAccount:      p.A.AccountID == p.B.AccountID && equalID(p.A.ToAccountID, p.B.ToAccountID),
			SamePayee:        p.SamePayee,
			Flagged:          equalID(p.A.DuplicateOfID, &p.B.ID) || equalID(p.B.DuplicateOfID, &p.A.ID),
		})
	}
	res := map[string]any{"currency": c.currency, "pairs": out, "remaining": remaining}
	if truncated {
		res["note"] = "Too many pairs to compare at once: only the nearest in time were. Search a shorter period to find the rest."
	}
	return res, nil
}

func equalID(a, b *int64) bool { return (a == nil && b == nil) || (a != nil && b != nil && *a == *b) }

func deleteRecords(c *call, args json.RawMessage) (any, error) {
	var in struct {
		RecordIDs []int64 `json:"record_ids"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	ids, err := recordIDs(in.RecordIDs)
	if err != nil {
		return nil, err
	}
	deleted, err := c.h.wallet.DeleteRecords(c.r.Context(), c.userID, ids)
	if err != nil {
		return nil, walletErr(err, "record")
	}
	out, err := c.records(deleted)
	if err != nil {
		return nil, err
	}
	return map[string]any{"currency": c.currency, "deleted": len(out), "records": out}, nil
}

func setBalance(c *call, args json.RawMessage) (any, error) {
	var in struct {
		AccountID int64 `json:"account_id"`
		Balance   string
		AsOf      string `json:"as_of"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	ctx := c.r.Context()
	st, err := c.h.wallet.Settings(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	c.currency = st.Currency
	balance, err := wallet.ParseMinor(in.Balance, st.Currency)
	if err != nil {
		return nil, toolErrorf("balance: %v", err)
	}
	asOf, err := parseTime(in.AsOf, "as_of")
	if err != nil {
		return nil, err
	}
	ch, err := c.h.wallet.SetBalance(ctx, c.userID, in.AccountID, balance, asOf)
	if err != nil {
		return nil, walletErr(err, "account")
	}
	res := map[string]any{"currency": c.currency, "account_id": ch.After.ID, "account": ch.After.Name}
	for k, v := range map[string]int64{
		"previous_balance": ch.Before.Balance, "balance": ch.After.Balance,
		"previous_starting_balance": ch.Before.InitialBalance, "starting_balance": ch.After.InitialBalance,
	} {
		if res[k], err = c.money(v); err != nil {
			return nil, err
		}
	}
	if ch.ToReview > 0 {
		res["records_to_review"] = ch.ToReview
	}
	return res, nil
}

func confirmRecords(c *call, args json.RawMessage) (any, error) {
	var in struct {
		RecordIDs      []int64 `json:"record_ids"`
		KeepDuplicates bool    `json:"keep_duplicates"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	ids, err := recordIDs(in.RecordIDs)
	if err != nil {
		return nil, err
	}
	confirmed, skipped, err := c.h.wallet.ConfirmRecords(c.r.Context(), c.userID, ids, in.KeepDuplicates)
	if err != nil {
		return nil, err
	}
	confirmedIDs := []int64{}
	for _, t := range confirmed {
		confirmedIDs = append(confirmedIDs, t.ID)
	}
	return map[string]any{"confirmed": confirmedIDs, "not_confirmed": skipped}, nil
}
