package wallet

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
)

// What the app has learned from confirmed payments, shown so the user can
// check and correct it: each shop's category (payee_rules) and each card's
// account (source_rules). The PayPay and MUFG imports' own choices
// (payPayRulePrefix, mufgRulePrefix) belong to the imports and are left out.

// PayeeRule gives records from a shop a category.
type PayeeRule struct {
	// Key is the shop's name as matched (see PayeeKey).
	Key string `json:"key"`
	// Payee is the name as most of the shop's records spell it, or Key.
	Payee      string `json:"payee"`
	CategoryID int64  `json:"category_id"`
	// Records counts the user's records the rule matches.
	Records int `json:"records"`
}

// CardRule puts payments by a card into an account.
type CardRule struct {
	Source    string `json:"source"`
	AccountID int64  `json:"account_id"`
	Records   int    `json:"records"`
}

type Rules struct {
	Payees []PayeeRule `json:"payees"`
	Cards  []CardRule  `json:"cards"`
}

// Rules lists what the user's payments taught the app: shops with the most
// records first, and cards by name.
func (s *Store) Rules(ctx context.Context, userID int64) (Rules, error) {
	out := Rules{Payees: []PayeeRule{}, Cards: []CardRule{}}
	rows, err := s.db.QueryContext(ctx,
		`SELECT payee_key, category_id FROM payee_rules WHERE user_id = $1 AND payee_key NOT LIKE $2 || '%'`,
		userID, mufgRulePrefix)
	if err != nil {
		return out, err
	}
	byKey := map[string]int{}
	for rows.Next() {
		var r PayeeRule
		if err := rows.Scan(&r.Key, &r.CategoryID); err != nil {
			rows.Close()
			return out, err
		}
		r.Payee = r.Key
		byKey[r.Key] = len(out.Payees)
		out.Payees = append(out.Payees, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	// Count each rule's records the way ruleCategory matches them, and
	// name it as its records most often do.
	rows, err = s.db.QueryContext(ctx,
		`SELECT payee, count(*) FROM transactions WHERE user_id = $1 AND payee <> '' GROUP BY payee`, userID)
	if err != nil {
		return out, err
	}
	named := map[string]int{} // rule key -> records behind its shown name
	for rows.Next() {
		var payee string
		var n int
		if err := rows.Scan(&payee, &n); err != nil {
			rows.Close()
			return out, err
		}
		key := PayeeKey(payee)
		i, ok := matchRule(byKey, key)
		if !ok {
			continue
		}
		r := &out.Payees[i]
		r.Records += n
		if key == r.Key && n > named[r.Key] {
			r.Payee, named[r.Key] = payee, n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}
	sort.Slice(out.Payees, func(i, j int) bool {
		a, b := out.Payees[i], out.Payees[j]
		if a.Records != b.Records {
			return a.Records > b.Records
		}
		return a.Key < b.Key
	})

	rows, err = s.db.QueryContext(ctx,
		`SELECT r.source, r.account_id,
			(SELECT count(*) FROM transactions t WHERE t.user_id = r.user_id AND t.source_label = r.source)
		 FROM source_rules r WHERE r.user_id = $1 AND r.source NOT LIKE $2 || '%' AND r.source NOT LIKE $3 || '%'
		 ORDER BY r.source`,
		userID, payPayRulePrefix, mufgRulePrefix)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var c CardRule
		if err := rows.Scan(&c.Source, &c.AccountID, &c.Records); err != nil {
			return out, err
		}
		out.Cards = append(out.Cards, c)
	}
	return out, rows.Err()
}

// matchRule is ruleCategory's choice among rules (by key): the exact key,
// else the longest key of 3 or more characters the shop's key starts with.
func matchRule(byKey map[string]int, key string) (int, bool) {
	if i, ok := byKey[key]; ok {
		return i, true
	}
	best, found := "", false
	for k := range byKey {
		n := utf8.RuneCountInString(k)
		if n >= 3 && strings.HasPrefix(key, k) && n > utf8.RuneCountInString(best) {
			best, found = k, true
		}
	}
	return byKey[best], found
}

// SetPayeeRule makes records from a shop get a category from now on.
func (s *Store) SetPayeeRule(ctx context.Context, userID int64, payee string, categoryID int64) (PayeeRule, error) {
	key := PayeeKey(payee)
	if key == "" || utf8.RuneCountInString(key) > maxText {
		return PayeeRule{}, invalid("enter the shop's name")
	}
	if _, err := s.category(ctx, userID, categoryID); errors.Is(err, db.ErrNotFound) {
		return PayeeRule{}, invalid("no such category")
	} else if err != nil {
		return PayeeRule{}, err
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3)
		 ON CONFLICT (user_id, payee_key) DO UPDATE SET category_id = EXCLUDED.category_id`,
		userID, key, categoryID)
	return PayeeRule{Key: key, Payee: strings.TrimSpace(payee), CategoryID: categoryID}, err
}

// DeletePayeeRule forgets a shop's category.
func (s *Store) DeletePayeeRule(ctx context.Context, userID int64, payee string) error {
	return rowsChanged(s.db.ExecContext(ctx,
		`DELETE FROM payee_rules WHERE user_id = $1 AND payee_key = $2`, userID, PayeeKey(payee)))
}

// SetCardRule changes the account a card's payments go to. Cards are only
// learned from payments, so the rule must exist.
func (s *Store) SetCardRule(ctx context.Context, userID int64, source string, accountID int64) error {
	if importRule(source) {
		return db.ErrNotFound
	}
	if _, err := s.account(ctx, userID, accountID); errors.Is(err, db.ErrNotFound) {
		return invalid("no such account")
	} else if err != nil {
		return err
	}
	return rowsChanged(s.db.ExecContext(ctx,
		`UPDATE source_rules SET account_id = $3 WHERE user_id = $1 AND source = $2`, userID, source, accountID))
}

// DeleteCardRule forgets a card's account: its payments go to the default
// account until the user confirms one in another.
func (s *Store) DeleteCardRule(ctx context.Context, userID int64, source string) error {
	if importRule(source) {
		return db.ErrNotFound
	}
	return rowsChanged(s.db.ExecContext(ctx,
		`DELETE FROM source_rules WHERE user_id = $1 AND source = $2`, userID, source))
}

// importRule is a source_rules entry an import keeps for itself.
func importRule(source string) bool {
	return strings.HasPrefix(source, payPayRulePrefix) || strings.HasPrefix(source, mufgRulePrefix)
}

// learnIfNew remembers a confirmed record's shop and card, but only where
// nothing was learned yet: confirming many at once never changes a rule.
func learnIfNew(ctx context.Context, tx *sql.Tx, userID int64, t Transaction) error {
	if key := PayeeKey(t.Payee); key != "" && t.CategoryID != nil {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
			userID, key, *t.CategoryID); err != nil {
			return err
		}
	}
	if t.SourceLabel != "" {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
			userID, t.SourceLabel, t.AccountID); err != nil {
			return err
		}
	}
	return nil
}

// ---------- HTTP ----------

func (h *Handler) rules(w http.ResponseWriter, r *http.Request) error {
	rules, err := h.store.Rules(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, rules)
	return nil
}

func (h *Handler) setPayeeRule(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Payee      string `json:"payee"`
		CategoryID int64  `json:"category_id"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	rule, err := h.store.SetPayeeRule(r.Context(), userID(r), in.Payee, in.CategoryID)
	if err := storeErr(err, "rule"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, rule)
	return nil
}

func (h *Handler) deletePayeeRule(w http.ResponseWriter, r *http.Request) error {
	if err := storeErr(h.store.DeletePayeeRule(r.Context(), userID(r), r.URL.Query().Get("payee")), "rule"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (h *Handler) setCardRule(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Source    string `json:"source"`
		AccountID int64  `json:"account_id"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if err := storeErr(h.store.SetCardRule(r.Context(), userID(r), in.Source, in.AccountID), "rule"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (h *Handler) deleteCardRule(w http.ResponseWriter, r *http.Request) error {
	if err := storeErr(h.store.DeleteCardRule(r.Context(), userID(r), r.URL.Query().Get("source")), "rule"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
