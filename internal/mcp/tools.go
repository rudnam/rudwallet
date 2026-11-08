package mcp

import (
	"encoding/json"
	"errors"
	"fmt"
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
	kindArg      = map[string]any{"type": "string", "enum": []string{"expense", "income"}}
	recordIDsArg = map[string]any{"type": "array", "items": map[string]any{"type": "integer"},
		"minItems": 1, "maxItems": maxRecordIDs, "description": fmt.Sprintf("1 to %d record IDs.", maxRecordIDs)}
)

// maxRecordIDs is the most records one call changes: a page of list_records.
const maxRecordIDs = 500

// recordIDs checks a tool's record IDs, dropping repeats.
func recordIDs(ids []int64) ([]int64, error) {
	slices.Sort(ids)
	ids = slices.Compact(ids)
	if len(ids) == 0 || len(ids) > maxRecordIDs {
		return nil, toolErrorf("give 1 to %d record_ids", maxRecordIDs)
	}
	return ids, nil
}

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
		Name:  "reconcile_account",
		Title: "Reconcile an account's balance",
		Description: "Set an account's balance to what the user really has (e.g. the cash counted in their wallet), " +
			"by recording the difference as one expense (less than the app thought) or income (more), " +
			"in the given category or else the user's Other / Other income category. Nothing is recorded if the balance already matches.",
		InputSchema: object(map[string]any{
			"account_id":  map[string]any{"type": "integer"},
			"balance":     map[string]any{"type": "string", "description": "What the account holds, as a decimal amount in the user's currency, e.g. \"4200\"."},
			"category_id": map[string]any{"type": "integer", "description": "Optional category for the difference."},
		}, "account_id", "balance"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false},
		run:         reconcileAccount,
	},
	{
		Name:  "set_record_account",
		Title: "Set a record's account",
		Description: "Move one record to another of the user's accounts (IDs from get_overview). " +
			"For a transfer, account_id is the account it comes from; to_account_id, if given, is the one it goes to, and must differ. " +
			"A payment read from a notification also teaches the app that its card belongs to the new account, so later payments by that card go there.",
		InputSchema: object(map[string]any{
			"record_id":     map[string]any{"type": "integer"},
			"account_id":    map[string]any{"type": "integer"},
			"to_account_id": map[string]any{"type": "integer", "description": "Transfers only."},
		}, "record_id", "account_id"),
		Annotations: changes,
		run:         setAccount,
	},
	{
		Name:  "create_category",
		Title: "Create a category",
		Description: "Add a category. Check get_overview first: reuse a fitting category rather than adding a near-duplicate. " +
			"With parent_id it is a subcategory of that main category (same kind; categories are one level deep).",
		InputSchema: object(map[string]any{
			"name":      map[string]any{"type": "string", "description": "1 to 60 characters."},
			"kind":      kindArg,
			"parent_id": map[string]any{"type": "integer", "description": "Optional: the main category to put it under."},
		}, "name", "kind"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false},
		run:         createCategory,
	},
	{
		Name:  "delete_category",
		Title: "Delete a category",
		Description: "Delete a category. Its records become uncategorised, its subcategories become main categories, and its budgets and remembered payees are deleted with it. " +
			"It can't be undone: ask the user first.",
		InputSchema: object(map[string]any{
			"category_id": map[string]any{"type": "integer"},
		}, "category_id"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false},
		run:         deleteCategory,
	},
	{
		Name:  "edit_records",
		Title: "Edit many records",
		Description: "Make one change to many records at once (IDs from list_records): " +
			"categorize (to category_id, or none if null; only records of the category's kind change, never transfers), " +
			"move (to account_id; for a transfer, the account it comes from), " +
			"confirm (records awaiting review that have a category and are not flagged as duplicates), " +
			"tag or untag (add or take off tag). Says how many changed and how many were skipped.",
		InputSchema: object(map[string]any{
			"record_ids":  recordIDsArg,
			"action":      map[string]any{"type": "string", "enum": []string{"categorize", "move", "confirm", "tag", "untag"}},
			"category_id": map[string]any{"type": []string{"integer", "null"}, "description": "For categorize."},
			"account_id":  map[string]any{"type": "integer", "description": "For move."},
			"tag":         map[string]any{"type": "string", "description": "For tag and untag."},
		}, "record_ids", "action"),
		Annotations: changes,
		run:         editRecords,
	},
	{
		Name:  "delete_records",
		Title: "Delete records",
		Description: "Delete one or more records (IDs from list_records); they no longer count in balances or spending. " +
			"restore_records can undo it for a day. Ask the user first.",
		InputSchema: object(map[string]any{"record_ids": recordIDsArg}, "record_ids"),
		Annotations: map[string]any{"readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false},
		run:         deleteRecords,
	},
	{
		Name:        "restore_records",
		Title:       "Restore deleted records",
		Description: "Put back records deleted in the last day, with the same IDs.",
		InputSchema: object(map[string]any{"record_ids": recordIDsArg}, "record_ids"),
		Annotations: changes,
		run:         restoreRecords,
	},
	{
		Name:  "update_account",
		Title: "Update an account",
		Description: "Rename an account, archive it (hidden from the app's lists, its records and balance are kept) or unarchive it, " +
			"or set its balance. Setting balance changes the account's starting balance without recording anything, " +
			"so its past balances move too; to record the difference as an expense or income instead, use reconcile_account. " +
			"Give only what changes.",
		InputSchema: object(map[string]any{
			"account_id": map[string]any{"type": "integer"},
			"name":       map[string]any{"type": "string", "description": "1 to 60 characters."},
			"archived":   map[string]any{"type": "boolean"},
			"balance":    map[string]any{"type": "string", "description": "What the account should hold now, as a decimal amount in the user's currency, e.g. \"4200\"."},
		}, "account_id"),
		Annotations: changes,
		run:         updateAccount,
	},
	{
		Name:  "spending_by_category",
		Title: "Spending by category",
		Description: "Confirmed expenses in a period, summed by category, largest first. " +
			"A subcategory's row names its main category; a main category's own row leaves its subcategories out.",
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
	// ParentID: the main category a subcategory is under.
	ParentID *int64 `json:"parent_id,omitempty"`
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
		cats = append(cats, outCategory{cat.ID, cat.Name, cat.Kind, cat.ParentID})
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
	// Source is how the record was made: manual, import or notification.
	Source string   `json:"source"`
	Tags   []string `json:"tags,omitempty"`
}

func listRecords(c *call, args json.RawMessage) (any, error) {
	var in struct {
		From, To      string
		AccountID     int64 `json:"account_id"`
		CategoryID    int64 `json:"category_id"`
		Uncategorized bool
		Query         string
		Limit         int
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	f := wallet.Filter{AccountID: in.AccountID, CategoryID: in.CategoryID, Uncategorized: in.Uncategorized, Query: in.Query}
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

	o, err := c.h.wallet.Overview(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	c.currency = o.Settings.Currency
	accounts := map[int64]string{}
	for _, a := range o.Accounts {
		accounts[a.ID] = a.Name
	}
	cats := map[int64]string{}
	for _, cat := range o.Categories {
		cats[cat.ID] = cat.Name
	}
	out := []outRecord{}
	for _, t := range list {
		amount, _ := c.money(t.Amount)
		rec := outRecord{ID: t.ID, OccurredAt: t.OccurredAt.Format(time.RFC3339), Kind: t.Kind, Amount: amount,
			Account: accounts[t.AccountID], CategoryID: t.CategoryID, Payee: t.Payee, Note: t.Note, Status: t.Status, Source: t.Source, Tags: t.Tags}
		if t.ToAccountID != nil {
			rec.ToAccount = accounts[*t.ToAccountID]
		}
		if t.CategoryID != nil {
			rec.Category = cats[*t.CategoryID]
		}
		out = append(out, rec)
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

func setAccount(c *call, args json.RawMessage) (any, error) {
	var in struct {
		RecordID    int64  `json:"record_id"`
		AccountID   int64  `json:"account_id"`
		ToAccountID *int64 `json:"to_account_id"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	t, err := c.h.wallet.SetAccount(c.r.Context(), c.userID, in.RecordID, in.AccountID, in.ToAccountID)
	if err != nil {
		return nil, walletErr(err, "record")
	}
	return map[string]any{"id": t.ID, "account_id": t.AccountID, "to_account_id": t.ToAccountID}, nil
}

func reconcileAccount(c *call, args json.RawMessage) (any, error) {
	var in struct {
		AccountID  int64  `json:"account_id"`
		Balance    string `json:"balance"`
		CategoryID *int64 `json:"category_id"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	if _, err := c.money(0); err != nil { // loads the currency
		return nil, err
	}
	balance, ok := wallet.ParseMinor(in.Balance, c.currency)
	if !ok {
		return nil, toolErrorf("balance must be a decimal amount in %s, e.g. 4200", c.currency)
	}
	t, err := c.h.wallet.Reconcile(c.r.Context(), c.userID, in.AccountID, balance, in.CategoryID, time.Now())
	if err != nil {
		return nil, walletErr(err, "account")
	}
	if t == nil {
		return map[string]any{"recorded": false, "message": "The balance already matches; nothing was recorded."}, nil
	}
	amount, _ := c.money(t.Amount)
	return map[string]any{"recorded": true, "id": t.ID, "kind": t.Kind, "amount": amount, "category_id": t.CategoryID}, nil
}

func createCategory(c *call, args json.RawMessage) (any, error) {
	var in struct {
		Name, Kind string
		ParentID   *int64 `json:"parent_id"`
	}
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
	cat, err := c.h.wallet.CreateCategory(c.r.Context(), c.userID, wallet.Category{Name: name, Kind: in.Kind, Color: "#66706b", ParentID: in.ParentID})
	if err != nil {
		return nil, walletErr(err, "category")
	}
	return outCategory{cat.ID, cat.Name, cat.Kind, cat.ParentID}, nil
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

func editRecords(c *call, args json.RawMessage) (any, error) {
	var in struct {
		RecordIDs  []int64 `json:"record_ids"`
		Action     string  `json:"action"`
		CategoryID *int64  `json:"category_id"`
		AccountID  int64   `json:"account_id"`
		Tag        string  `json:"tag"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	ids, err := recordIDs(in.RecordIDs)
	if err != nil {
		return nil, err
	}
	ctx, w := c.r.Context(), c.h.wallet
	var n int
	switch in.Action {
	case "categorize":
		n, err = w.BulkCategorize(ctx, c.userID, ids, in.CategoryID)
	case "move":
		n, err = w.BulkMove(ctx, c.userID, ids, in.AccountID)
	case "confirm":
		n, err = w.BulkConfirm(ctx, c.userID, ids)
	case "tag", "untag":
		tag, terr := wallet.CleanTag(in.Tag)
		if terr != nil {
			return nil, walletErr(terr, "tag")
		}
		if in.Action == "tag" {
			n, err = w.BulkTag(ctx, c.userID, ids, tag)
		} else {
			n, err = w.BulkUntag(ctx, c.userID, ids, tag)
		}
	default:
		return nil, toolErrorf("action must be categorize, move, confirm, tag or untag")
	}
	if err != nil {
		return nil, walletErr(err, "record")
	}
	return map[string]int{"changed": n, "skipped": len(ids) - n}, nil
}

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
	n, err := c.h.wallet.BulkDelete(c.r.Context(), c.userID, ids)
	if err != nil {
		return nil, err
	}
	return map[string]int{"deleted": n, "not_found": len(ids) - n}, nil
}

func restoreRecords(c *call, args json.RawMessage) (any, error) {
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
	n, err := c.h.wallet.Restore(c.r.Context(), c.userID, ids)
	if err != nil {
		return nil, err
	}
	return map[string]int{"restored": n, "not_restored": len(ids) - n}, nil
}

func updateAccount(c *call, args json.RawMessage) (any, error) {
	var in struct {
		AccountID int64   `json:"account_id"`
		Name      *string `json:"name"`
		Archived  *bool   `json:"archived"`
		Balance   *string `json:"balance"`
	}
	if err := decode(args, &in); err != nil {
		return nil, err
	}
	if in.Name == nil && in.Archived == nil && in.Balance == nil {
		return nil, toolErrorf("give a name, archived or balance to change")
	}
	var balance int64
	if in.Balance != nil {
		if _, err := c.money(0); err != nil { // loads the currency
			return nil, err
		}
		var ok bool
		if balance, ok = wallet.ParseMinor(*in.Balance, c.currency); !ok {
			return nil, toolErrorf("balance must be a decimal amount in %s, e.g. 4200", c.currency)
		}
	}
	ctx := c.r.Context()
	accounts, err := c.h.wallet.Accounts(ctx, c.userID)
	if err != nil {
		return nil, err
	}
	i := slices.IndexFunc(accounts, func(a wallet.Account) bool { return a.ID == in.AccountID })
	if i < 0 {
		return nil, toolErrorf("no such account")
	}
	a := accounts[i]
	if in.Name != nil || in.Archived != nil {
		if in.Name != nil {
			a.Name = strings.TrimSpace(*in.Name)
			if a.Name == "" || utf8.RuneCountInString(a.Name) > 60 {
				return nil, toolErrorf("an account name must be 1 to 60 characters")
			}
		}
		if in.Archived != nil {
			a.Archived = *in.Archived
		}
		if a, err = c.h.wallet.UpdateAccount(ctx, c.userID, a); err != nil {
			return nil, walletErr(err, "account")
		}
	}
	if in.Balance != nil {
		if a, err = c.h.wallet.SetBalance(ctx, c.userID, a.ID, balance); err != nil {
			return nil, walletErr(err, "account")
		}
	}
	bal, err := c.money(a.Balance)
	if err != nil {
		return nil, err
	}
	return outAccount{a.ID, a.Name, a.Kind, bal, a.Archived}, nil
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
	parents := map[int64]int64{}
	for _, cat := range cats {
		names[cat.ID] = cat.Name
		if cat.ParentID != nil {
			parents[cat.ID] = *cat.ParentID
		}
	}
	type row struct {
		CategoryID int64  `json:"category_id"`
		Category   string `json:"category"`
		Parent     string `json:"main_category,omitempty"`
		Amount     string `json:"amount"`
		minor      int64
	}
	rows := []row{}
	var total int64
	for id, sum := range sums {
		total += sum
		r := row{CategoryID: id, Category: names[id], minor: sum}
		if p, ok := parents[id]; ok {
			r.Parent = names[p]
		}
		rows = append(rows, r)
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
