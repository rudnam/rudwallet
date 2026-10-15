package wallet

import (
	"context"
	"sort"

	"github.com/lib/pq"

	"github.com/rudnam/wallet-app/internal/db"
)

// PayeeGroup is the confirmed records without a category that share a payee
// (compared by PayeeKey) and a kind. Records without a payee are grouped by
// their note instead; records with neither are left out.
type PayeeGroup struct {
	// Payee is the name as first written; Categorize matches it by PayeeKey.
	Payee string `json:"payee"`
	Kind  string `json:"kind"`
	Count int    `json:"count"`
	// Total is the sum of the records' amounts.
	Total int64 `json:"total"`
	// Note is one record's note, which can say what the payee is.
	Note string `json:"note"`
}

// recordLabel is what a record is grouped by: its payee, else its note.
func recordLabel(payee, note string) string {
	if PayeeKey(payee) != "" {
		return payee
	}
	return note
}

func groupKey(kind, label string) string { return kind + "\x00" + PayeeKey(label) }

// UncategorizedGroups lists the user's uncategorised records by payee, the
// most frequent first.
func (s *Store) UncategorizedGroups(ctx context.Context, userID int64) ([]PayeeGroup, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT kind, payee, note, amount FROM transactions
		 WHERE user_id = $1 AND category_id IS NULL AND kind <> 'transfer' AND status = 'confirmed'
		 ORDER BY occurred_at DESC, id DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	index := map[string]int{}
	out := []PayeeGroup{}
	for rows.Next() {
		var kind, payee, note string
		var amount int64
		if err := rows.Scan(&kind, &payee, &note, &amount); err != nil {
			return nil, err
		}
		label := recordLabel(payee, note)
		if PayeeKey(label) == "" {
			continue
		}
		k := groupKey(kind, label)
		i, ok := index[k]
		if !ok {
			i = len(out)
			index[k] = i
			out = append(out, PayeeGroup{Payee: label, Kind: kind})
		}
		g := &out[i]
		g.Count++
		g.Total += amount
		if g.Note == "" && label == payee {
			g.Note = note
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Count != out[j].Count {
			return out[i].Count > out[j].Count
		}
		return out[i].Total > out[j].Total
	})
	return out, nil
}

// PayeeCategory files a payee's uncategorised records under a category.
type PayeeCategory struct {
	Payee      string `json:"payee"`
	Kind       string `json:"kind"`
	CategoryID int64  `json:"category_id"`
}

// Categorize gives each payee's uncategorised, confirmed records its
// category, and remembers the category for the payee's future emails. It
// returns how many records changed. Every category must be the user's own
// and of the payee's kind.
func (s *Store) Categorize(ctx context.Context, userID int64, items []PayeeCategory) (int, error) {
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return 0, err
	}
	byID := map[int64]Category{}
	for _, c := range cats {
		byID[c.ID] = c
	}
	want := map[string]int64{}
	for _, it := range items {
		c, ok := byID[it.CategoryID]
		if !ok {
			return 0, db.ErrNotFound
		}
		if c.Kind != it.Kind {
			return 0, invalid("%q is an %s category", c.Name, c.Kind)
		}
		if PayeeKey(it.Payee) != "" {
			want[groupKey(it.Kind, it.Payee)] = it.CategoryID
		}
	}
	if len(want) == 0 {
		return 0, nil
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx,
		`SELECT id, kind, payee, note FROM transactions
		 WHERE user_id = $1 AND category_id IS NULL AND kind <> 'transfer' AND status = 'confirmed'
		 FOR UPDATE`, userID)
	if err != nil {
		return 0, err
	}
	ids := map[int64][]int64{}    // category -> records
	learned := map[string]int64{} // payee key -> category
	for rows.Next() {
		var id int64
		var kind, payee, note string
		if err := rows.Scan(&id, &kind, &payee, &note); err != nil {
			rows.Close()
			return 0, err
		}
		cat, ok := want[groupKey(kind, recordLabel(payee, note))]
		if !ok {
			continue
		}
		ids[cat] = append(ids[cat], id)
		if key := PayeeKey(payee); key != "" {
			learned[key] = cat
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	changed := 0
	for cat, list := range ids {
		res, err := tx.ExecContext(ctx,
			`UPDATE transactions SET category_id = $3 WHERE user_id = $1 AND id = ANY($2)`,
			userID, pq.Array(list), cat)
		if err != nil {
			return 0, err
		}
		n, _ := res.RowsAffected()
		changed += int(n)
	}
	for key, cat := range learned {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, payee_key) DO UPDATE SET category_id = EXCLUDED.category_id`,
			userID, key, cat); err != nil {
			return 0, err
		}
	}
	return changed, tx.Commit()
}

// SetCategory changes one record's category (nil: none), keeping the rest.
func (s *Store) SetCategory(ctx context.Context, userID, id int64, categoryID *int64) (Transaction, error) {
	t, err := s.Get(ctx, userID, id)
	if err != nil {
		return t, err
	}
	t.CategoryID = categoryID
	if err := s.checkRefs(ctx, userID, t); err != nil {
		return t, err
	}
	err = rowsChanged(s.db.ExecContext(ctx,
		`UPDATE transactions SET category_id = $3 WHERE user_id = $1 AND id = $2`, userID, id, categoryID))
	return t, err
}

// SetAccount moves one record to another account, keeping the rest. For a
// transfer, accountID is the account it comes from, and toAccountID, if
// given, the one it goes to. A payment from an email or a notification also
// teaches its card's account, as correcting it in the app does.
func (s *Store) SetAccount(ctx context.Context, userID, id, accountID int64, toAccountID *int64) (Transaction, error) {
	t, err := s.Get(ctx, userID, id)
	if err != nil {
		return t, err
	}
	t.AccountID = accountID
	if toAccountID != nil {
		t.ToAccountID = toAccountID
	}
	if err := s.checkRefs(ctx, userID, t); err != nil {
		return t, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return t, err
	}
	defer tx.Rollback()
	if err := rowsChanged(tx.ExecContext(ctx,
		`UPDATE transactions SET account_id = $3, to_account_id = $4 WHERE user_id = $1 AND id = $2`,
		userID, id, t.AccountID, t.ToAccountID)); err != nil {
		return t, err
	}
	if (t.Source == "email" || t.Source == "notification") && t.SourceLabel != "" && t.Kind != "transfer" {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, t.SourceLabel, t.AccountID); err != nil {
			return t, err
		}
	}
	return t, tx.Commit()
}
