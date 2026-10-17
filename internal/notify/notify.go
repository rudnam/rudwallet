// Package notify turns the phone's payment notifications into records. The
// Android app listens for notifications from payment apps (Google Wallet
// and PayPay) (android/…/notify/PaymentListener.java), keeps them while offline,
// and sends them to POST /api/notifications.
//
// A payment is recorded once, however it is reported: the same notification
// sent again is recognised by its app and time, and a payment already
// recorded another way (an import, or by hand) is matched
// rather than added (wallet.CreateFromNotification).
package notify

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// Incoming is one notification as the phone sends it.
type Incoming struct {
	Package string `json:"package"`
	// When is the time the notification carries (Notification.when), in
	// milliseconds since 1970: for a payment, when it was made.
	When  int64  `json:"when"`
	Title string `json:"title"`
	Text  string `json:"text"`
}

type Notification struct {
	ID            int64     `json:"id"`
	Package       string    `json:"package"`
	App           string    `json:"app"`
	Title         string    `json:"title"`
	Text          string    `json:"text"`
	PostedAt      time.Time `json:"posted_at"`
	Status        string    `json:"status"`
	Detail        string    `json:"detail"`
	TransactionID *int64    `json:"transaction_id"`
	// Duplicate is set when this notification had already been received.
	Duplicate bool `json:"duplicate,omitempty"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

const columns = `id, package, title, body, posted_at, status, detail, transaction_id`

func scan(row interface{ Scan(...any) error }) (Notification, error) {
	var n Notification
	var tx sql.NullInt64
	err := row.Scan(&n.ID, &n.Package, &n.Title, &n.Text, &n.PostedAt, &n.Status, &n.Detail, &tx)
	if tx.Valid {
		n.TransactionID = &tx.Int64
	}
	n.App = apps[n.Package]
	return n, err
}

// Recent returns the user's latest notifications, newest first.
func (s *Store) Recent(ctx context.Context, userID int64, limit int) ([]Notification, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+columns+` FROM payment_notifications WHERE user_id = $1 ORDER BY created_at DESC, id DESC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Notification{}
	for rows.Next() {
		n, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// claim records that a notification arrived, before it is processed. An
// app may update a notification it already posted: one that wasn't read as
// a payment is read again with its new text. ok is false if it was received
// before and needs nothing more.
func (s *Store) claim(ctx context.Context, userID int64, key string, in Incoming, at time.Time) (n Notification, ok bool, err error) {
	n, err = scan(s.db.QueryRowContext(ctx,
		`INSERT INTO payment_notifications (user_id, dedup_key, package, title, body, posted_at, status, detail)
		 VALUES ($1, $2, $3, $4, $5, $6, 'unparsed', 'Could not be processed.')
		 ON CONFLICT (user_id, dedup_key) DO UPDATE SET title = EXCLUDED.title, body = EXCLUDED.body
		   WHERE payment_notifications.status IN ('ignored', 'unparsed')
		     AND (payment_notifications.title, payment_notifications.body) <> (EXCLUDED.title, EXCLUDED.body)
		 RETURNING `+columns,
		userID, key, clip(in.Package, 200), clip(in.Title, 300), clip(in.Text, 1000), at))
	if errors.Is(err, sql.ErrNoRows) {
		n, err = scan(s.db.QueryRowContext(ctx,
			`SELECT `+columns+` FROM payment_notifications WHERE user_id = $1 AND dedup_key = $2`, userID, key))
		return n, false, err
	}
	return n, true, err
}

func (s *Store) finish(ctx context.Context, userID int64, n Notification) (Notification, error) {
	return scan(s.db.QueryRowContext(ctx,
		`UPDATE payment_notifications SET status = $3, detail = $4, transaction_id = $5
		 WHERE user_id = $1 AND id = $2 RETURNING `+columns,
		userID, n.ID, n.Status, clip(n.Detail, 500), n.TransactionID))
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// ---------- Processing ----------

type Service struct {
	store  *Store
	wallet *wallet.Store
}

func NewService(st *Store, w *wallet.Store) *Service { return &Service{store: st, wallet: w} }

// Receive processes one notification. Receiving it again returns the first
// result, marked Duplicate.
func (s *Service) Receive(ctx context.Context, userID int64, in Incoming, now time.Time) (Notification, error) {
	at := now
	if in.When > 0 {
		at = time.UnixMilli(in.When)
	}
	if at.After(now.Add(time.Hour)) { // a phone with its clock wrong
		at = now
	}
	// The app and the notification's own time identify it; its text may
	// change when the app updates it. Without a time, its text has to do.
	key := in.Package + "|" + strconv.FormatInt(in.When, 10)
	if in.When <= 0 {
		sum := sha256.Sum256([]byte(in.Title + "\x00" + in.Text))
		key = in.Package + "|" + hex.EncodeToString(sum[:16])
	}
	n, fresh, err := s.store.claim(ctx, userID, key, in, at)
	if err != nil || !fresh {
		n.Duplicate = !fresh
		return n, err
	}

	r := Parse(in.Package, in.Title, in.Text)
	n.Status, n.Detail = r.Status, r.Detail
	if r.Status == "payment" {
		p := r.Payment
		if p.OccurredAt.IsZero() { // the time the notification itself gives, if any, is better
			p.OccurredAt = at
		}
		t, matched, err := s.wallet.CreateFromNotification(ctx, userID, p)
		if err != nil {
			return n, err
		}
		n.TransactionID = &t.ID
		if matched {
			n.Status, n.Detail = "matched", "Already recorded, so it wasn't added again."
		}
	}
	return s.store.finish(ctx, userID, n)
}

// ---------- HTTP ----------

// maxBatch is the most notifications one request may carry. The phone sends
// what it kept while offline, oldest first, in batches.
const maxBatch = 50

type Handler struct {
	store   *Store
	service *Service
}

func NewHandler(st *Store, svc *Service) *Handler { return &Handler{store: st, service: svc} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/notifications", requireUser(httpx.HandlerFunc(h.recent)))
	mux.Handle("POST /api/notifications", requireUser(httpx.HandlerFunc(h.receive)))
}

type list struct {
	Notifications []Notification `json:"notifications"`
}

func (h *Handler) recent(w http.ResponseWriter, r *http.Request) error {
	ns, err := h.store.Recent(r.Context(), account.UserFrom(r).ID, 30)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list{ns})
	return nil
}

// receive takes {notifications: [...]} and answers with what each became,
// in the same order.
func (h *Handler) receive(w http.ResponseWriter, r *http.Request) error {
	var body struct {
		Notifications []Incoming `json:"notifications"`
	}
	if err := httpx.ReadJSON(w, r, 256<<10, &body); err != nil {
		return err
	}
	if len(body.Notifications) > maxBatch {
		return httpx.Errorf(http.StatusRequestEntityTooLarge, "send at most %d notifications at a time", maxBatch)
	}
	userID := account.UserFrom(r).ID
	out := list{Notifications: []Notification{}}
	for _, in := range body.Notifications {
		if in.Package == "" {
			return httpx.Errorf(http.StatusBadRequest, "each notification needs its app's package")
		}
		n, err := h.service.Receive(r.Context(), userID, in, time.Now())
		if err != nil {
			return err
		}
		out.Notifications = append(out.Notifications, n)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
	return nil
}
