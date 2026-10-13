package wallet

import (
	"context"
	"database/sql"
	"net/http"
	"strconv"
	"time"

	"github.com/rudnam/wallet-app/internal/httpx"
)

// maxStatsSpan bounds one stats request; the answer has a row per day.
const maxStatsSpan = 20 * 366 * 24 * time.Hour

// Stats sums confirmed income and expenses over a period. Transfers move
// money between the user's own accounts, so they count as neither.
type Stats struct {
	Spent  int64 `json:"spent"`
	Income int64 `json:"income"`
	// Categories are the totals per category and kind, largest first.
	// CategoryID is null for records without one.
	Categories []CategoryTotal `json:"categories"`
	// Days are the totals per calendar day in the requested time zone,
	// oldest first; days without records are left out.
	Days []DayTotal `json:"days"`
}

type CategoryTotal struct {
	CategoryID *int64 `json:"category_id"`
	Kind       string `json:"kind"`
	Amount     int64  `json:"amount"`
	Count      int    `json:"count"`
}

type DayTotal struct {
	Day    string `json:"day"` // YYYY-MM-DD
	Spent  int64  `json:"spent"`
	Income int64  `json:"income"`
}

// Stats totals the user's confirmed records in [from, to), grouping days
// in the IANA time zone tz. accountID 0 means every account.
func (s *Store) Stats(ctx context.Context, userID int64, from, to time.Time, tz string, accountID int64) (Stats, error) {
	st := Stats{Categories: []CategoryTotal{}, Days: []DayTotal{}}
	const where = `user_id = $1 AND status = 'confirmed' AND kind IN ('expense', 'income')
		AND occurred_at >= $2 AND occurred_at < $3 AND ($4 = 0 OR account_id = $4)`

	rows, err := s.db.QueryContext(ctx, `
		SELECT category_id, kind, sum(amount), count(*) FROM transactions WHERE `+where+`
		GROUP BY 1, 2 ORDER BY 3 DESC, 1`, userID, from, to, accountID)
	if err != nil {
		return st, err
	}
	defer rows.Close()
	for rows.Next() {
		var c CategoryTotal
		var cat sql.NullInt64
		if err := rows.Scan(&cat, &c.Kind, &c.Amount, &c.Count); err != nil {
			return st, err
		}
		c.CategoryID = ptr(cat)
		if c.Kind == "expense" {
			st.Spent += c.Amount
		} else {
			st.Income += c.Amount
		}
		st.Categories = append(st.Categories, c)
	}
	if err := rows.Err(); err != nil {
		return st, err
	}

	rows, err = s.db.QueryContext(ctx, `
		SELECT to_char(occurred_at AT TIME ZONE $5, 'YYYY-MM-DD') AS day,
		       coalesce(sum(amount) FILTER (WHERE kind = 'expense'), 0),
		       coalesce(sum(amount) FILTER (WHERE kind = 'income'), 0)
		FROM transactions WHERE `+where+`
		GROUP BY 1 ORDER BY 1`, userID, from, to, accountID, tz)
	if err != nil {
		return st, err
	}
	defer rows.Close()
	for rows.Next() {
		var d DayTotal
		if err := rows.Scan(&d.Day, &d.Spent, &d.Income); err != nil {
			return st, err
		}
		st.Days = append(st.Days, d)
	}
	return st, rows.Err()
}

// knownTimeZone reports whether PostgreSQL knows the IANA zone name.
func (s *Store) knownTimeZone(ctx context.Context, tz string) (bool, error) {
	var ok bool
	err := s.db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = $1)`, tz).Scan(&ok)
	return ok, err
}

func (h *Handler) stats(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	from, err1 := time.Parse(time.RFC3339, q.Get("from"))
	to, err2 := time.Parse(time.RFC3339, q.Get("to"))
	if err1 != nil || err2 != nil || !to.After(from) {
		return httpx.Errorf(http.StatusBadRequest, "from and to must be RFC 3339 times, from first")
	}
	if to.Sub(from) > maxStatsSpan {
		return httpx.Errorf(http.StatusBadRequest, "choose a period of 20 years or less")
	}
	tz := q.Get("tz")
	if tz == "" {
		tz = "UTC"
	} else if ok, err := h.store.knownTimeZone(r.Context(), tz); err != nil {
		return err
	} else if !ok {
		return httpx.Errorf(http.StatusBadRequest, "unknown time zone")
	}
	var accountID int64
	if v := q.Get("account_id"); v != "" {
		var err error
		if accountID, err = strconv.ParseInt(v, 10, 64); err != nil {
			return httpx.Errorf(http.StatusBadRequest, "account_id must be a number")
		}
	}
	st, err := h.store.Stats(r.Context(), userID(r), from, to, tz, accountID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}
