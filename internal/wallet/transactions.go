package wallet

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	"github.com/rudnam/wallet-app/internal/db"
)

// Filter narrows List. Zero values mean "any".
type Filter struct {
	From, To   time.Time // To is exclusive
	AccountID  int64
	CategoryID int64
	// Uncategorized lists only records without a category.
	Uncategorized bool
	Query         string
	Status        string
}

// maxList caps one page of records; a month of even heavy use fits.
const maxList = 2000

const txColumns = `id, kind, amount, account_id, to_account_id, category_id, occurred_at, payee, note,
	status, source, source_label, duplicate_of_id, created_at`

func scanTx(row interface{ Scan(...any) error }) (Transaction, error) {
	var t Transaction
	var to, cat, dup sql.NullInt64
	err := row.Scan(&t.ID, &t.Kind, &t.Amount, &t.AccountID, &to, &cat, &t.OccurredAt, &t.Payee, &t.Note,
		&t.Status, &t.Source, &t.SourceLabel, &dup, &t.CreatedAt)
	t.ToAccountID, t.CategoryID, t.DuplicateOfID = ptr(to), ptr(cat), ptr(dup)
	return t, err
}

func ptr(n sql.NullInt64) *int64 {
	if !n.Valid {
		return nil
	}
	return &n.Int64
}

// List returns matching records, newest first.
func (s *Store) List(ctx context.Context, userID int64, f Filter) ([]Transaction, error) {
	where := []string{"user_id = $1"}
	args := []any{userID}
	add := func(cond string, v any) {
		args = append(args, v)
		where = append(where, fmt.Sprintf(cond, len(args)))
	}
	if !f.From.IsZero() {
		add("occurred_at >= $%d", f.From)
	}
	if !f.To.IsZero() {
		add("occurred_at < $%d", f.To)
	}
	if f.AccountID != 0 {
		add("(account_id = $%[1]d OR to_account_id = $%[1]d)", f.AccountID)
	}
	if f.CategoryID != 0 {
		add("category_id = $%d", f.CategoryID)
	}
	if f.Uncategorized {
		where = append(where, "category_id IS NULL AND kind <> 'transfer'")
	}
	if f.Status != "" {
		add("status = $%d", f.Status)
	}
	if q := strings.TrimSpace(f.Query); q != "" {
		add("(payee ILIKE $%[1]d OR note ILIKE $%[1]d)", "%"+escapeLike(q)+"%")
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE `+strings.Join(where, " AND ")+
			fmt.Sprintf(` ORDER BY occurred_at DESC, id DESC LIMIT %d`, maxList), args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Transaction{}
	for rows.Next() {
		t, err := scanTx(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func escapeLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

func (s *Store) Get(ctx context.Context, userID, id int64) (Transaction, error) {
	t, err := scanTx(s.db.QueryRowContext(ctx,
		`SELECT `+txColumns+` FROM transactions WHERE user_id = $1 AND id = $2`, userID, id))
	if errors.Is(err, sql.ErrNoRows) {
		return t, db.ErrNotFound
	}
	return t, err
}

// InvalidError is a validation problem the user can fix.
type InvalidError struct{ Msg string }

func (e *InvalidError) Error() string { return e.Msg }

func invalid(format string, args ...any) error { return &InvalidError{fmt.Sprintf(format, args...)} }

// checkRefs makes sure the record's accounts and category are the user's
// own, and fit its kind.
func (s *Store) checkRefs(ctx context.Context, userID int64, t Transaction) error {
	if _, err := s.account(ctx, userID, t.AccountID); errors.Is(err, db.ErrNotFound) {
		return invalid("no such account")
	} else if err != nil {
		return err
	}
	if t.Kind == "transfer" {
		if t.ToAccountID == nil || *t.ToAccountID == t.AccountID {
			return invalid("a transfer needs two different accounts")
		}
		if _, err := s.account(ctx, userID, *t.ToAccountID); errors.Is(err, db.ErrNotFound) {
			return invalid("no such account")
		} else if err != nil {
			return err
		}
		if t.CategoryID != nil {
			return invalid("a transfer has no category")
		}
		return nil
	}
	if t.ToAccountID != nil {
		return invalid("only a transfer has a second account")
	}
	if t.CategoryID != nil {
		c, err := s.category(ctx, userID, *t.CategoryID)
		if errors.Is(err, db.ErrNotFound) {
			return invalid("no such category")
		} else if err != nil {
			return err
		}
		if c.Kind != t.Kind {
			return invalid("%q is an %s category", c.Name, c.Kind)
		}
	}
	return nil
}

func (s *Store) Create(ctx context.Context, userID int64, t Transaction) (Transaction, error) {
	if err := s.setUp(ctx, userID); err != nil {
		return t, err
	}
	if err := s.checkRefs(ctx, userID, t); err != nil {
		return t, err
	}
	if t.Source == "" {
		t.Source = "manual"
	}
	if t.Status == "" {
		t.Status = "confirmed"
	}
	return scanTx(s.db.QueryRowContext(ctx,
		`INSERT INTO transactions (user_id, kind, amount, account_id, to_account_id, category_id, occurred_at,
			payee, note, status, source, source_label, duplicate_of_id)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
		 RETURNING `+txColumns,
		userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID, t.OccurredAt,
		t.Payee, t.Note, t.Status, t.Source, t.SourceLabel, t.DuplicateOfID))
}

// Update saves the user's edits. When they confirm or correct a record that
// came from an email or a notification, the merchant's category and the card's account are
// remembered for next time.
func (s *Store) Update(ctx context.Context, userID int64, t Transaction) (Transaction, error) {
	old, err := s.Get(ctx, userID, t.ID)
	if err != nil {
		return t, err
	}
	if err := s.checkRefs(ctx, userID, t); err != nil {
		return t, err
	}
	if t.Status == "confirmed" {
		t.DuplicateOfID = nil
	} else {
		t.DuplicateOfID = old.DuplicateOfID
	}
	updated, err := scanTx(s.db.QueryRowContext(ctx,
		`UPDATE transactions SET kind = $3, amount = $4, account_id = $5, to_account_id = $6, category_id = $7,
			occurred_at = $8, payee = $9, note = $10, status = $11, duplicate_of_id = $12
		 WHERE user_id = $1 AND id = $2 RETURNING `+txColumns,
		userID, t.ID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID,
		t.OccurredAt, t.Payee, t.Note, t.Status, t.DuplicateOfID))
	if errors.Is(err, sql.ErrNoRows) {
		return t, db.ErrNotFound
	}
	if err != nil {
		return t, err
	}
	if (old.Source == "email" || old.Source == "notification") && updated.Status == "confirmed" {
		if err := s.learn(ctx, userID, updated); err != nil {
			return updated, err
		}
	}
	return updated, nil
}

func (s *Store) learn(ctx context.Context, userID int64, t Transaction) error {
	if key := PayeeKey(t.Payee); key != "" && t.CategoryID != nil {
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, payee_key) DO UPDATE SET category_id = EXCLUDED.category_id`,
			userID, key, *t.CategoryID); err != nil {
			return err
		}
	}
	if t.SourceLabel != "" {
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, t.SourceLabel, t.AccountID); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) Delete(ctx context.Context, userID, id int64) error {
	return rowsChanged(s.db.ExecContext(ctx, `DELETE FROM transactions WHERE user_id = $1 AND id = $2`, userID, id))
}

// PayeeKey normalises a merchant name for matching: full-width letters and
// digits become ASCII, case and spacing are ignored, and trailing markers
// such as "*" are dropped.
func PayeeKey(payee string) string {
	var b strings.Builder
	for _, r := range payee {
		switch {
		case r >= 0xFF01 && r <= 0xFF5E: // full-width ASCII
			r -= 0xFEE0
		case r == 0x3000:
			r = ' '
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return strings.TrimRight(strings.Join(strings.Fields(b.String()), " "), "*・ ")
}

// ---------- Records from emails and notifications ----------

// Payment is a payment read from an email or a phone notification, ready to
// record.
type Payment struct {
	Amount     int64
	Currency   string
	Refund     bool
	Merchant   string
	OccurredAt time.Time
	// Source names what paid (e.g. the card), so the account can be learned.
	Source string
	Note   string
}

// SameMoment is how far apart two reports of one payment can be: a card's
// email and the phone's notification both arrive within minutes of paying.
const SameMoment = 15 * time.Minute

// CreateFromEmail records an emailed payment, unless the phone already
// reported it: a record from a notification for the same amount within
// SameMoment, which no other email claimed, is returned instead (matched),
// and gets the card's account if it is still waiting for review.
func (s *Store) CreateFromEmail(ctx context.Context, userID int64, p Payment) (t Transaction, matched bool, err error) {
	return s.createPayment(ctx, userID, p, "email")
}

// CreateFromNotification records a payment from a phone notification,
// unless it was already recorded another way (its email, an import, or by
// hand): that record is returned instead (matched). Each record matches at
// most one notification, so two equal payments in a row stay two.
func (s *Store) CreateFromNotification(ctx context.Context, userID int64, p Payment) (t Transaction, matched bool, err error) {
	return s.createPayment(ctx, userID, p, "notification")
}

// createPayment picks the account learned for the card (else the default
// account, else the first one) and the category learned for the merchant.
// The record waits for review unless the user turned on auto-confirm and the
// category is known. A record with the same amount within a day is flagged
// as a possible duplicate, and never confirmed automatically.
func (s *Store) createPayment(ctx context.Context, userID int64, p Payment, source string) (Transaction, bool, error) {
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return Transaction{}, false, err
	}
	t := Transaction{
		Kind: "expense", Amount: p.Amount, OccurredAt: p.OccurredAt, Payee: p.Merchant, Note: p.Note,
		Status: "review", Source: source, SourceLabel: p.Source,
	}
	if p.Refund {
		t.Kind = "income"
	}
	if t.OccurredAt.IsZero() {
		t.OccurredAt = time.Now()
	}
	if p.Currency != "" && !strings.EqualFold(p.Currency, st.Currency) {
		// No exchange rates here: the user enters what it cost in their currency.
		t.Note = strings.TrimSpace(fmt.Sprintf("Charged %s %s. Enter the amount in %s. %s",
			FormatMinor(p.Amount, p.Currency), strings.ToUpper(p.Currency), st.Currency, t.Note))
		t.Amount = 0
	}

	learned, err := s.learnedAccount(ctx, userID, p.Source)
	if err != nil {
		return t, false, err
	}
	if same, ok, err := s.samePayment(ctx, userID, t, source, learned); err != nil || ok {
		return same, ok, err
	}

	switch {
	case learned != nil:
		t.AccountID = *learned
	default:
		if t.AccountID, err = s.defaultAccount(ctx, userID, st); err != nil {
			return t, false, err
		}
	}
	if t.Kind == "expense" {
		t.CategoryID, err = s.ruleCategory(ctx, userID, p.Merchant)
		if err != nil {
			return t, false, err
		}
	}

	var dup int64
	err = s.db.QueryRowContext(ctx,
		`SELECT id FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND amount > 0
		   AND occurred_at BETWEEN $4::timestamptz - interval '1 day' AND $4::timestamptz + interval '1 day'
		   -- Two alerts about the same card are two payments (the same alert
		   -- twice is caught before this): compare with other sources.
		   AND NOT (source = $5 AND source_label = $6)
		 ORDER BY abs(extract(epoch FROM occurred_at - $4::timestamptz)) LIMIT 1`,
		userID, t.Kind, t.Amount, t.OccurredAt, source, t.SourceLabel).Scan(&dup)
	switch {
	case err == nil:
		t.DuplicateOfID = &dup
	case !errors.Is(err, sql.ErrNoRows):
		return t, false, err
	}
	if st.AutoConfirm && t.CategoryID != nil && t.DuplicateOfID == nil && t.Amount > 0 {
		t.Status = "confirmed"
	}
	t, err = s.Create(ctx, userID, t)
	return t, false, err
}

// samePayment finds the record of this payment reported the other way: for
// an email, a notification's record no email has claimed; for a
// notification, any record no notification has claimed. It must have the
// same amount, within SameMoment. A record the user already confirmed must
// also be in the account learned for this card, if there is one.
func (s *Store) samePayment(ctx context.Context, userID int64, t Transaction, source string, learned *int64) (Transaction, bool, error) {
	if t.Amount <= 0 {
		return t, false, nil
	}
	var other string
	switch source {
	case "email":
		other = `source = 'notification' AND NOT EXISTS (SELECT 1 FROM inbound_emails e WHERE e.transaction_id = transactions.id)`
	case "notification":
		other = `source <> 'notification' AND NOT EXISTS (SELECT 1 FROM payment_notifications n WHERE n.transaction_id = transactions.id)`
	default:
		return t, false, nil
	}
	same, err := scanTx(s.db.QueryRowContext(ctx,
		`SELECT `+txColumns+` FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND amount = $3
		   AND occurred_at BETWEEN $4::timestamptz - $5::interval AND $4::timestamptz + $5::interval
		   AND (status = 'review' OR $6::bigint IS NULL OR account_id = $6)
		   AND `+other+`
		 ORDER BY abs(extract(epoch FROM occurred_at - $4::timestamptz)), id LIMIT 1`,
		userID, t.Kind, t.Amount, t.OccurredAt, fmt.Sprintf("%d seconds", int(SameMoment.Seconds())), learned))
	if errors.Is(err, sql.ErrNoRows) {
		return t, false, nil
	}
	if err != nil {
		return t, false, err
	}
	// The card's email knows which account paid better than the phone does.
	if source == "email" && same.Status == "review" && learned != nil && same.AccountID != *learned {
		same, err = scanTx(s.db.QueryRowContext(ctx,
			`UPDATE transactions SET account_id = $3 WHERE user_id = $1 AND id = $2 RETURNING `+txColumns,
			userID, same.ID, *learned))
		if err != nil {
			return same, false, err
		}
	}
	return same, true, nil
}

// learnedAccount is the account the user chose for a card before, or nil.
func (s *Store) learnedAccount(ctx context.Context, userID int64, source string) (*int64, error) {
	if source == "" {
		return nil, nil
	}
	var id int64
	err := s.db.QueryRowContext(ctx,
		`SELECT account_id FROM source_rules WHERE user_id = $1 AND source = $2`, userID, source).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &id, nil
}

// defaultAccount is where payments from an unknown card go.
func (s *Store) defaultAccount(ctx context.Context, userID int64, st Settings) (int64, error) {
	if st.DefaultAccountID != nil {
		return *st.DefaultAccountID, nil
	}
	var id int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id FROM wallet_accounts WHERE user_id = $1 ORDER BY archived, id LIMIT 1`, userID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		// Every account was deleted; make one so the payment isn't lost.
		err = s.db.QueryRowContext(ctx,
			`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, 'Cash', 'cash')
			 ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id`, userID).Scan(&id)
	}
	return id, err
}

// ruleCategory finds the category learned for a merchant: an exact match,
// else the longest learned name the merchant starts with (so a chain's
// branches share one rule).
func (s *Store) ruleCategory(ctx context.Context, userID int64, merchant string) (*int64, error) {
	key := PayeeKey(merchant)
	if key == "" {
		return nil, nil
	}
	var id int64
	err := s.db.QueryRowContext(ctx,
		`SELECT category_id FROM payee_rules
		 WHERE user_id = $1 AND (payee_key = $2 OR (char_length(payee_key) >= 3 AND left($2, char_length(payee_key)) = payee_key))
		 ORDER BY char_length(payee_key) DESC LIMIT 1`, userID, key).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &id, nil
}

// FormatMinor writes whole minor units as a plain decimal, e.g. 1250 USD as "12.50".
func FormatMinor(amount int64, currency string) string {
	exp := CurrencyExponent(currency)
	if exp == 0 {
		return fmt.Sprint(amount)
	}
	div := int64(1)
	for range exp {
		div *= 10
	}
	return fmt.Sprintf("%d.%0*d", amount/div, exp, amount%div)
}
