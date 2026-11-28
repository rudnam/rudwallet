package wallet

import (
	"context"
	"strings"

	"github.com/rudnam/wallet-app/internal/db"
)

// A wallet set up before a default was renamed or given a new icon keeps the
// name and icon it had then: Restaurants rather than Eating out, a light
// bulb for Water. Refresh offers to give them the ones a new wallet has.

// RefreshOffer lists the user's default categories whose name or icon isn't
// what a new wallet has, each with the name and icon it would get. A
// category is renamed only from a former name of its default, and only if no
// other category of its kind already has the new name.
func (s *Store) RefreshOffer(ctx context.Context, userID int64) ([]Category, error) {
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return nil, err
	}
	return refreshes(cats), nil
}

// Refresh makes the changes RefreshOffer lists, those of ids only (ids it no
// longer lists are skipped), and returns the categories it changed.
func (s *Store) Refresh(ctx context.Context, userID int64, ids []int64) ([]Category, error) {
	tx, err := s.lockCategories(ctx, userID)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	cats, err := categoriesOf(ctx, tx, userID)
	if err != nil {
		return nil, err
	}
	wanted := map[int64]bool{}
	for _, id := range ids {
		wanted[id] = true
	}
	changed := []Category{}
	for _, c := range refreshes(cats) {
		if !wanted[c.ID] {
			continue
		}
		_, err := tx.ExecContext(ctx, `UPDATE categories SET name = $3, icon = $4 WHERE user_id = $1 AND id = $2`, userID, c.ID, c.Name, c.Icon)
		if db.IsUniqueViolation(err) {
			return nil, ErrDuplicateName
		}
		if err != nil {
			return nil, err
		}
		changed = append(changed, c)
	}
	return changed, tx.Commit()
}

// refreshes: the user's default categories with the name and icon a new
// wallet gives them, those that differ from what they have.
func refreshes(cats []Category) []Category {
	lang := walletLanguage(cats)
	taken := map[string]bool{} // kind and name, ignoring case
	for _, c := range cats {
		taken[c.Kind+":"+strings.ToLower(c.Name)] = true
	}
	out := []Category{}
	for _, c := range cats {
		d := defaultName(c.Name)
		def, ok := defaultCategory(d)
		if !ok || def.Kind != c.Kind {
			continue
		}
		name := c.Name
		if !strings.EqualFold(c.Name, d) && c.Name != japaneseNames[d] {
			// A former name: the default's name now, unless that's taken.
			if now := localName(d, lang); !taken[c.Kind+":"+strings.ToLower(now)] {
				name = now
			}
		}
		if name == c.Name && c.Icon == def.Icon {
			continue
		}
		c.Name, c.Icon = name, def.Icon
		out = append(out, c)
	}
	return out
}

// defaultCategory is the default with the English name.
func defaultCategory(english string) (Category, bool) {
	for _, c := range defaultCategories {
		if c.Name == english {
			return c, true
		}
	}
	return Category{}, false
}
