package wallet

import (
	"context"
	"net/http"
	"sort"

	"github.com/rudnam/wallet-app/internal/httpx"
)

// Suggestion is the categories a payment waiting for review most likely
// belongs in, the likeliest first, so it can be filed in one tap.
type Suggestion struct {
	TransactionID int64   `json:"transaction_id"`
	CategoryIDs   []int64 `json:"category_ids"`
}

// maxSuggestions is how many categories each payment gets: few enough to
// fit as chips on a phone's row.
const maxSuggestions = 3

// ReviewSuggestions suggests categories for each record to review (other
// than transfers): first the categories the user's confirmed records from
// the same shop were filed in (the most records first), then the
// categories of the record's kind the user filed the most records in over
// the last 90 days.
func (s *Store) ReviewSuggestions(ctx context.Context, userID int64) ([]Suggestion, error) {
	out := []Suggestion{}
	reviews, err := s.List(ctx, userID, Filter{Status: "review"})
	if err != nil || len(reviews) == 0 {
		return out, err
	}

	type count struct {
		category int64
		n        int
	}
	// The shop's categories, by kind and shop key.
	byShop := map[string]map[int64]int{}
	rows, err := s.db.QueryContext(ctx,
		`SELECT payee, kind, category_id, count(*) FROM transactions
		 WHERE user_id = $1 AND status = 'confirmed' AND payee <> '' AND category_id IS NOT NULL AND kind <> 'transfer'
		   AND occurred_at > now() - interval '400 days'
		 GROUP BY payee, kind, category_id`, userID)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var payee, kind string
		var c count
		if err := rows.Scan(&payee, &kind, &c.category, &c.n); err != nil {
			rows.Close()
			return out, err
		}
		key := kind + "\x00" + PayeeKey(payee)
		if byShop[key] == nil {
			byShop[key] = map[int64]int{}
		}
		byShop[key][c.category] += c.n
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	// The most used categories of each kind lately.
	popular := map[string][]int64{}
	rows, err = s.db.QueryContext(ctx,
		`SELECT kind, category_id FROM transactions
		 WHERE user_id = $1 AND status = 'confirmed' AND category_id IS NOT NULL AND kind <> 'transfer'
		   AND occurred_at > now() - interval '90 days'
		 GROUP BY kind, category_id ORDER BY count(*) DESC, category_id`, userID)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var kind string
		var id int64
		if err := rows.Scan(&kind, &id); err != nil {
			rows.Close()
			return out, err
		}
		popular[kind] = append(popular[kind], id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	for _, t := range reviews {
		if t.Kind == "transfer" {
			continue
		}
		var ids []int64
		seen := map[int64]bool{}
		add := func(id int64) {
			if len(ids) < maxSuggestions && !seen[id] {
				seen[id] = true
				ids = append(ids, id)
			}
		}
		if t.Payee != "" {
			var shop []count
			for id, n := range byShop[t.Kind+"\x00"+PayeeKey(t.Payee)] {
				shop = append(shop, count{id, n})
			}
			sort.Slice(shop, func(i, j int) bool {
				if shop[i].n != shop[j].n {
					return shop[i].n > shop[j].n
				}
				return shop[i].category < shop[j].category
			})
			for _, c := range shop {
				add(c.category)
			}
		}
		for _, id := range popular[t.Kind] {
			add(id)
		}
		if ids == nil {
			ids = []int64{}
		}
		out = append(out, Suggestion{TransactionID: t.ID, CategoryIDs: ids})
	}
	return out, nil
}

func (h *Handler) reviewSuggestions(w http.ResponseWriter, r *http.Request) error {
	s, err := h.store.ReviewSuggestions(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, struct {
		Suggestions []Suggestion `json:"suggestions"`
	}{s})
	return nil
}
