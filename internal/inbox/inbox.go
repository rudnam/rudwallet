// Package inbox is the email automation: each user gets a private address,
// payment emails forwarded to it become records to review in the wallet.
//
// Mail reaches the app through POST /api/inbound/email, which an email
// provider's inbound hook calls with the raw message (see docs/email.md and
// deploy/email-worker/). Users can also upload a message to test it.
package inbox

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base32"
	"errors"
	"log/slog"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/inbox/parse"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// AddressPrefix starts every forwarding address: pay-<token>@domain.
const AddressPrefix = "pay-"

// maxBodyText bounds the text kept for an email the user has to look at.
const maxBodyText = 20_000

type Email struct {
	ID            int64     `json:"id"`
	From          string    `json:"from"`
	Subject       string    `json:"subject"`
	ReceivedAt    time.Time `json:"received_at"`
	Status        string    `json:"status"`
	Parser        string    `json:"parser"`
	Detail        string    `json:"detail"`
	BodyText      string    `json:"body_text"`
	ConfirmURL    string    `json:"confirm_url"`
	TransactionID *int64    `json:"transaction_id"`
	Dismissed     bool      `json:"dismissed"`
	// Duplicate is set when this delivery had already been received.
	Duplicate bool `json:"duplicate,omitempty"`
}

// ---------- Store ----------

type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

func newToken() string {
	b := make([]byte, 8)
	rand.Read(b)
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}

// Token returns the user's address token, creating one on first use.
func (s *Store) Token(ctx context.Context, userID int64) (string, error) {
	var token string
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO inbox_addresses (user_id, token) VALUES ($1, $2)
		 ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id RETURNING token`,
		userID, newToken()).Scan(&token)
	return token, err
}

// Rotate gives the user a new address; mail to the old one is refused.
func (s *Store) Rotate(ctx context.Context, userID int64) (string, error) {
	token := newToken()
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO inbox_addresses (user_id, token) VALUES ($1, $2)
		 ON CONFLICT (user_id) DO UPDATE SET token = EXCLUDED.token, created_at = now()`, userID, token)
	return token, err
}

// UserByToken finds whose address a token is.
func (s *Store) UserByToken(ctx context.Context, token string) (int64, error) {
	var id int64
	err := s.db.QueryRowContext(ctx, `SELECT user_id FROM inbox_addresses WHERE token = $1`, token).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, db.ErrNotFound
	}
	return id, err
}

const emailColumns = `id, from_addr, subject, received_at, status, parser, detail, body_text, confirm_url, transaction_id, dismissed`

func scanEmail(row interface{ Scan(...any) error }) (Email, error) {
	var e Email
	var tx sql.NullInt64
	err := row.Scan(&e.ID, &e.From, &e.Subject, &e.ReceivedAt, &e.Status, &e.Parser, &e.Detail, &e.BodyText, &e.ConfirmURL, &tx, &e.Dismissed)
	if tx.Valid {
		e.TransactionID = &tx.Int64
	}
	return e, err
}

// Recent returns the user's latest emails, newest first.
func (s *Store) Recent(ctx context.Context, userID int64, limit int) ([]Email, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+emailColumns+` FROM inbound_emails WHERE user_id = $1 ORDER BY created_at DESC, id DESC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Email{}
	for rows.Next() {
		e, err := scanEmail(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (s *Store) Dismiss(ctx context.Context, userID, id int64) error {
	res, err := s.db.ExecContext(ctx, `UPDATE inbound_emails SET dismissed = true WHERE user_id = $1 AND id = $2`, userID, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return db.ErrNotFound
	}
	return nil
}

// claim records that an email arrived, before it is processed. If it fails
// half-way it stays "unparsed" with its text, so the user still sees it.
// ok is false if this email was received before.
func (s *Store) claim(ctx context.Context, userID int64, m parse.Message) (e Email, ok bool, err error) {
	received := m.Date
	if received.IsZero() {
		received = time.Now()
	}
	e, err = scanEmail(s.db.QueryRowContext(ctx,
		`INSERT INTO inbound_emails (user_id, message_id, from_addr, subject, received_at, status, detail, body_text)
		 VALUES ($1, $2, $3, $4, $5, 'unparsed', 'Could not be processed.', $6)
		 ON CONFLICT (user_id, message_id) DO NOTHING RETURNING `+emailColumns,
		userID, m.MessageID, clip(m.From, 300), clip(m.Subject, 500), received, clip(m.Text, maxBodyText)))
	if errors.Is(err, sql.ErrNoRows) {
		e, err = scanEmail(s.db.QueryRowContext(ctx,
			`SELECT `+emailColumns+` FROM inbound_emails WHERE user_id = $1 AND message_id = $2`, userID, m.MessageID))
		return e, false, err
	}
	return e, true, err
}

func (s *Store) finish(ctx context.Context, userID int64, e Email) (Email, error) {
	return scanEmail(s.db.QueryRowContext(ctx,
		`UPDATE inbound_emails SET status = $3, parser = $4, detail = $5, body_text = $6, confirm_url = $7, transaction_id = $8
		 WHERE user_id = $1 AND id = $2 RETURNING `+emailColumns,
		userID, e.ID, e.Status, e.Parser, clip(e.Detail, 500), e.BodyText, e.ConfirmURL, e.TransactionID))
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// ---------- Processing ----------

// Service turns received emails into records.
type Service struct {
	store  *Store
	wallet *wallet.Store
	// ai reads emails no rule knows; nil when no API key is configured.
	ai Extractor
}

func NewService(st *Store, w *wallet.Store, ai Extractor) *Service {
	return &Service{store: st, wallet: w, ai: ai}
}

// Receive processes one raw email for a user. Receiving the same email
// again returns the first result, marked Duplicate.
func (s *Service) Receive(ctx context.Context, userID int64, raw []byte) (Email, error) {
	m, err := parse.Read(raw)
	if err != nil {
		return Email{}, err
	}
	e, fresh, err := s.store.claim(ctx, userID, m)
	if err != nil || !fresh {
		e.Duplicate = !fresh
		return e, err
	}

	r := parse.Classify(m)
	if r.Kind == parse.KindUnknown {
		switch {
		case s.ai == nil:
			r.Detail = "No rule for this sender yet. Add the payment yourself, or turn on the AI reader."
		default:
			if r, err = s.ai.Extract(ctx, m); err != nil {
				slog.WarnContext(ctx, "AI email reader failed", "err", err, "from", m.From)
				r = parse.Result{Kind: parse.KindUnknown, Parser: "ai", Detail: "The AI reader couldn't read this email. Add the payment yourself."}
			}
		}
	}

	e.Status, e.Parser, e.Detail = string(r.Kind), r.Parser, r.Detail
	switch r.Kind {
	case parse.KindPayment:
		p := r.Payment
		t, matched, err := s.wallet.CreateFromEmail(ctx, userID, wallet.Payment{
			Amount: p.Amount, Currency: p.Currency, Refund: p.Refund, Merchant: p.Merchant,
			OccurredAt: p.OccurredAt, Source: p.Source, Note: p.Note,
		})
		if err != nil {
			return e, err
		}
		e.TransactionID, e.BodyText = &t.ID, ""
		if matched {
			e.Detail = "The phone already reported this payment, so it is recorded once."
		}
	case parse.KindIgnored:
		e.BodyText = ""
	case parse.KindForwarding:
		e.ConfirmURL = r.ConfirmURL
	case parse.KindUnknown:
		e.Status = "unparsed"
	}
	return s.store.finish(ctx, userID, e)
}
