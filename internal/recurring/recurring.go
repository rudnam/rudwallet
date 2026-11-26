// Package recurring keeps the user's recurring payments: subscriptions
// (the same amount every month or few), bills (an amount that moves, such
// as electricity) and income (a salary). Records stay ordinary records; a
// recurring payment names the texts its records carry, and its payments,
// last payment and next due date are worked out from them each time, so a
// payment recorded any way (by hand, from a notification, imported) counts,
// and changing what it matches applies to records already there.
//
// It also finds recurring payments in the user's records to suggest
// (detect.go), and works out what they do to the month going on
// (schedule.go), so the rest of the month's spending can be estimated from
// what isn't recurring.
package recurring

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/lib/pq"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/wallet"
)

const (
	maxName    = 60
	maxMatches = 10
	maxMatch   = 100
	maxAmount  = 1_000_000_000_000
	maxTracked = 100
	// history is how far back payments are looked for.
	history = 400 * 24 * time.Hour
)

var kinds = []string{"subscription", "bill", "income"}

// Recurring is a recurring payment. ID through Active are the user's; the
// rest is worked out from their records.
type Recurring struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
	// Kind is subscription, bill or income.
	Kind string `json:"kind"`
	// Matches are texts its records' payee (or note) contain, compared
	// ignoring case and full-width letters.
	Matches []string `json:"matches"`
	// Amount is what it costs (or pays): payments far from it aren't its.
	Amount int64 `json:"amount"`
	// Months is how often: 1, 2, 3, 6 or 12.
	Months int `json:"months"`
	// StartDate is when it's due if it has no payments yet.
	StartDate  Date   `json:"start_date"`
	CategoryID *int64 `json:"category_id"`
	AccountID  *int64 `json:"account_id"`
	// Active is false for one that stopped (a cancelled subscription):
	// kept, but not expected any more.
	Active bool `json:"active"`

	// Estimate is what the next payment should be.
	Estimate int64 `json:"estimate"`
	// Payments are its records, newest first (up to 12).
	Payments []Payment `json:"payments"`
	LastPaid *Payment  `json:"last_paid"`
	NextDue  *Date     `json:"next_due"`
	// Late is true when the next payment was due before today.
	Late bool `json:"late"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

func (s *Store) List(ctx context.Context, userID int64) ([]Recurring, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, name, kind, matches, amount, months, start_date, category_id, account_id, active
		FROM recurring_payments WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Recurring{}
	for rows.Next() {
		var r Recurring
		var start time.Time
		var cat, acc sql.NullInt64
		if err := rows.Scan(&r.ID, &r.Name, &r.Kind, pq.Array(&r.Matches), &r.Amount, &r.Months, &start, &cat, &acc, &r.Active); err != nil {
			return nil, err
		}
		r.StartDate = dateOf(start)
		r.CategoryID, r.AccountID = ptr(cat), ptr(acc)
		if r.Matches == nil {
			r.Matches = []string{}
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func ptr(n sql.NullInt64) *int64 {
	if !n.Valid {
		return nil
	}
	return &n.Int64
}

func (s *Store) Create(ctx context.Context, userID int64, r Recurring) (Recurring, error) {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM recurring_payments WHERE user_id = $1`, userID).Scan(&n); err != nil {
		return r, err
	}
	if n >= maxTracked {
		return r, httpx.Errorf(http.StatusConflict, "you can keep up to 100 recurring payments")
	}
	err := s.db.QueryRowContext(ctx, `
		INSERT INTO recurring_payments (user_id, name, kind, matches, amount, months, start_date, category_id, account_id, active)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING id`,
		userID, r.Name, r.Kind, pq.Array(r.Matches), r.Amount, r.Months, r.StartDate.Time, r.CategoryID, r.AccountID, r.Active).Scan(&r.ID)
	return r, err
}

func (s *Store) Update(ctx context.Context, userID int64, r Recurring) error {
	return rowsChanged(s.db.ExecContext(ctx, `
		UPDATE recurring_payments SET name = $3, kind = $4, matches = $5, amount = $6, months = $7,
			start_date = $8, category_id = $9, account_id = $10, active = $11
		WHERE user_id = $1 AND id = $2`,
		userID, r.ID, r.Name, r.Kind, pq.Array(r.Matches), r.Amount, r.Months, r.StartDate.Time, r.CategoryID, r.AccountID, r.Active))
}

// exists reports whether the user has the recurring payment.
func (s *Store) exists(ctx context.Context, userID, id int64) (bool, error) {
	var ok bool
	err := s.db.QueryRowContext(ctx,
		`SELECT EXISTS (SELECT 1 FROM recurring_payments WHERE user_id = $1 AND id = $2)`, userID, id).Scan(&ok)
	return ok, err
}

func (s *Store) Delete(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM recurring_payments WHERE user_id = $1 AND id = $2`, userID, id))
}

func rowsChanged(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return db.ErrNotFound
	}
	return nil
}

func (s *Store) Dismiss(ctx context.Context, userID int64, key string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO recurring_dismissed (user_id, key) VALUES ($1, $2) ON CONFLICT DO NOTHING`, userID, key)
	return err
}

func (s *Store) dismissed(ctx context.Context, userID int64) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT key FROM recurring_dismissed WHERE user_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		out[k] = true
	}
	return out, rows.Err()
}

// payments returns the user's income and expenses since the given time,
// newest first, dated in zone. Records to review count: they are payments
// made, only not confirmed yet.
func (s *Store) payments(ctx context.Context, userID int64, since time.Time, zone *time.Location) ([]Payment, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, kind, amount, payee, note, category_id, account_id, occurred_at, status = 'confirmed'
		FROM transactions
		WHERE user_id = $1 AND kind IN ('expense', 'income') AND occurred_at >= $2
		ORDER BY occurred_at DESC, id DESC`, userID, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Payment{}
	for rows.Next() {
		var p Payment
		var payee, note string
		var cat sql.NullInt64
		var at time.Time
		if err := rows.Scan(&p.TransactionID, &p.kind, &p.Amount, &payee, &note, &cat, &p.accountID, &at, &p.Confirmed); err != nil {
			return nil, err
		}
		p.Label = label(payee, note)
		p.key = wallet.PayeeKey(p.Label)
		p.categoryID = ptr(cat)
		p.Date = dateOf(at.In(zone))
		out = append(out, p)
	}
	return out, rows.Err()
}

// ---------- HTTP ----------

type Handler struct {
	store  *Store
	wallet *wallet.Store
}

func NewHandler(st *Store, ws *wallet.Store) *Handler { return &Handler{store: st, wallet: ws} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	route := func(pattern string, fn httpx.HandlerFunc) { mux.Handle(pattern, requireUser(fn)) }
	route("GET /api/recurring", h.list)
	route("POST /api/recurring", h.create)
	route("POST /api/recurring/dismiss", h.dismiss)
	route("PUT /api/recurring/{id}", h.update)
	route("DELETE /api/recurring/{id}", h.delete)
}

func userID(r *http.Request) int64 { return account.UserFrom(r).ID }

// Overview is GET /api/recurring's answer.
type Overview struct {
	Recurring   []Recurring  `json:"recurring"`
	Suggestions []Suggestion `json:"suggestions"`
	Month       Month        `json:"month"`
}

// zone is the request's tz, an IANA time zone (UTC if not given).
func zone(r *http.Request) (*time.Location, error) {
	tz := r.URL.Query().Get("tz")
	if tz == "" {
		return time.UTC, nil
	}
	z, err := time.LoadLocation(tz)
	if err != nil || tz == "Local" {
		return nil, httpx.Errorf(http.StatusBadRequest, "unknown time zone")
	}
	return z, nil
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) error {
	z, err := zone(r)
	if err != nil {
		return err
	}
	ctx, uid := r.Context(), userID(r)
	now := time.Now()
	records, err := h.store.payments(ctx, uid, now.Add(-history), z)
	if err != nil {
		return err
	}
	list, err := h.store.List(ctx, uid)
	if err != nil {
		return err
	}
	dismissed, err := h.store.dismissed(ctx, uid)
	if err != nil {
		return err
	}
	today := dateOf(now.In(z))
	for i := range list {
		list[i].status(records, today)
	}
	o := Overview{Recurring: list, Suggestions: Suggest(records, list, dismissed, today), Month: month(list, today)}
	httpx.WriteJSON(w, http.StatusOK, o)
	return nil
}

// input is a recurring payment as sent: Active and StartDate may be left out.
type input struct {
	Name       string   `json:"name"`
	Kind       string   `json:"kind"`
	Matches    []string `json:"matches"`
	Amount     int64    `json:"amount"`
	Months     int      `json:"months"`
	StartDate  *Date    `json:"start_date"`
	CategoryID *int64   `json:"category_id"`
	AccountID  *int64   `json:"account_id"`
	Active     *bool    `json:"active"`
}

// read checks a recurring payment sent by the user.
func (h *Handler) read(w http.ResponseWriter, r *http.Request) (Recurring, error) {
	var in input
	if err := httpx.ReadJSON(w, r, 64<<10, &in); err != nil {
		return Recurring{}, err
	}
	bad := func(msg string) (Recurring, error) {
		return Recurring{}, httpx.Errorf(http.StatusBadRequest, "%s", msg)
	}
	rec := Recurring{
		Name: strings.TrimSpace(in.Name), Kind: in.Kind, Amount: in.Amount, Months: in.Months,
		CategoryID: in.CategoryID, AccountID: in.AccountID, Active: in.Active == nil || *in.Active,
	}
	if rec.Months == 0 {
		rec.Months = 1
	}
	if in.StartDate != nil {
		rec.StartDate = *in.StartDate
	} else {
		rec.StartDate = dateOf(time.Now())
	}
	switch {
	case rec.Name == "" || utf8.RuneCountInString(rec.Name) > maxName:
		return bad("give it a name of up to 60 characters")
	case !slices.Contains(kinds, rec.Kind):
		return bad("kind must be subscription, bill or income")
	case rec.Amount <= 0 || rec.Amount > maxAmount:
		return bad("enter an amount")
	case !slices.Contains([]int{1, 2, 3, 6, 12}, rec.Months):
		return bad("months must be 1, 2, 3, 6 or 12")
	}
	rec.Matches = []string{}
	for _, m := range in.Matches {
		k := wallet.PayeeKey(m)
		if k != "" && !slices.Contains(rec.Matches, k) {
			rec.Matches = append(rec.Matches, k)
		}
	}
	if len(rec.Matches) == 0 {
		// The name is what it's matched by unless told otherwise.
		rec.Matches = []string{wallet.PayeeKey(rec.Name)}
	}
	if len(rec.Matches) > maxMatches {
		return bad("up to 10 texts to match")
	}
	for _, m := range rec.Matches {
		if utf8.RuneCountInString(m) > maxMatch {
			return bad("a text to match can be up to 100 characters")
		}
	}

	o, err := h.wallet.Overview(r.Context(), userID(r))
	if err != nil {
		return rec, err
	}
	if rec.CategoryID != nil {
		i := slices.IndexFunc(o.Categories, func(c wallet.Category) bool { return c.ID == *rec.CategoryID })
		if i < 0 {
			return bad("no such category")
		}
		if o.Categories[i].Kind != recordKind(rec.Kind) {
			return bad("the category must be an " + recordKind(rec.Kind) + " category")
		}
	}
	if rec.AccountID != nil && !slices.ContainsFunc(o.Accounts, func(a wallet.Account) bool { return a.ID == *rec.AccountID }) {
		return bad("no such account")
	}
	return rec, nil
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) error {
	rec, err := h.read(w, r)
	if err != nil {
		return err
	}
	if rec, err = h.store.Create(r.Context(), userID(r), rec); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, rec)
	return nil
}

func pathID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.Errorf(http.StatusNotFound, "no such recurring payment")
	}
	return id, nil
}

func notFound(err error) error {
	if errors.Is(err, db.ErrNotFound) {
		return httpx.Errorf(http.StatusNotFound, "no such recurring payment")
	}
	return err
}

func (h *Handler) update(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	// Another user's is not found, before anything in it is checked.
	if ok, err := h.store.exists(r.Context(), userID(r), id); err != nil {
		return err
	} else if !ok {
		return notFound(db.ErrNotFound)
	}
	rec, err := h.read(w, r)
	if err != nil {
		return err
	}
	rec.ID = id
	if err := h.store.Update(r.Context(), userID(r), rec); err != nil {
		return notFound(err)
	}
	httpx.WriteJSON(w, http.StatusOK, rec)
	return nil
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	if err := h.store.Delete(r.Context(), userID(r), id); err != nil {
		return notFound(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (h *Handler) dismiss(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Key string `json:"key"`
	}
	if err := httpx.ReadJSON(w, r, 4<<10, &in); err != nil {
		return err
	}
	if in.Key == "" || utf8.RuneCountInString(in.Key) > 200 {
		return httpx.Errorf(http.StatusBadRequest, "key is required")
	}
	if err := h.store.Dismiss(r.Context(), userID(r), in.Key); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
