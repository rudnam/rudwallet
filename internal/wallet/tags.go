package wallet

import (
	"context"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/lib/pq"
	"github.com/rudnam/wallet-app/internal/httpx"
)

const (
	// maxTag is the longest tag, in characters.
	maxTag = 30
	// maxTags is the most tags one record has.
	maxTags = 10
)

// Tag is a tag in use, and on how many records.
type Tag struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// cleanTag tidies a tag as typed: no "#" in front, single spaces. It
// returns "" for one that is empty.
func cleanTag(s string) string {
	return strings.Join(strings.Fields(strings.TrimLeft(strings.TrimSpace(s), "#＃")), " ")
}

// cleanTags tidies a record's tags and checks them; the same tag twice,
// ignoring case, is kept once. Nil stays nil (keep the record's tags).
func cleanTags(in []string) ([]string, error) {
	if in == nil {
		return nil, nil
	}
	out := []string{}
	seen := map[string]bool{}
	for _, s := range in {
		s = cleanTag(s)
		if s == "" || seen[strings.ToLower(s)] {
			continue
		}
		if utf8.RuneCountInString(s) > maxTag {
			return nil, httpx.Errorf(http.StatusBadRequest, "a tag must be at most %d characters", maxTag)
		}
		seen[strings.ToLower(s)] = true
		out = append(out, s)
	}
	if len(out) > maxTags {
		return nil, httpx.Errorf(http.StatusBadRequest, "a record can have at most %d tags", maxTags)
	}
	return out, nil
}

// spellTags writes each tag the way the user's records already spell it, so
// "trip" typed today joins the "Trip" used before. Nil becomes empty.
func (s *Store) spellTags(ctx context.Context, userID int64, tags []string) ([]string, error) {
	out := []string{}
	if len(tags) == 0 {
		return out, nil
	}
	lower := make([]string, len(tags))
	for i, t := range tags {
		lower[i] = strings.ToLower(t)
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT DISTINCT ON (lower(g)) g FROM transactions, unnest(tags) g
		 WHERE user_id = $1 AND lower(g) = ANY($2::text[])
		 ORDER BY lower(g), g`, userID, pq.Array(lower))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	known := map[string]string{}
	for rows.Next() {
		var g string
		if err := rows.Scan(&g); err != nil {
			return nil, err
		}
		known[strings.ToLower(g)] = g
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, t := range tags {
		if k, ok := known[strings.ToLower(t)]; ok {
			t = k
		}
		out = append(out, t)
	}
	return out, nil
}

// Tags lists the tags on the user's records, the most used first.
func (s *Store) Tags(ctx context.Context, userID int64) ([]Tag, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT min(g), count(*) FROM transactions, unnest(tags) g WHERE user_id = $1
		 GROUP BY lower(g) ORDER BY count(*) DESC, lower(g)`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Tag{}
	for rows.Next() {
		var t Tag
		if err := rows.Scan(&t.Name, &t.Count); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// BulkTag adds a tag to the user's records among ids that don't have it
// yet, and have room for it.
func (s *Store) BulkTag(ctx context.Context, userID int64, ids []int64, tag string) (int, error) {
	spelled, err := s.spellTags(ctx, userID, []string{tag})
	if err != nil {
		return 0, err
	}
	return affected(s.db.ExecContext(ctx,
		`UPDATE transactions SET tags = array_append(tags, $3)
		 WHERE user_id = $1 AND id = ANY($2::bigint[]) AND cardinality(tags) < $4
		   AND NOT EXISTS (SELECT 1 FROM unnest(tags) g WHERE lower(g) = lower($3))`,
		userID, pq.Array(ids), spelled[0], maxTags))
}

// BulkUntag takes a tag, ignoring case, off the user's records among ids.
func (s *Store) BulkUntag(ctx context.Context, userID int64, ids []int64, tag string) (int, error) {
	return affected(s.db.ExecContext(ctx,
		`UPDATE transactions SET tags = ARRAY(SELECT g FROM unnest(tags) WITH ORDINALITY AS u(g, n)
			WHERE lower(g) <> lower($3) ORDER BY n)
		 WHERE user_id = $1 AND id = ANY($2::bigint[])
		   AND EXISTS (SELECT 1 FROM unnest(tags) g WHERE lower(g) = lower($3))`,
		userID, pq.Array(ids), tag))
}

func (h *Handler) listTags(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.Tags(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list)
	return nil
}
