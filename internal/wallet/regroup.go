package wallet

import (
	"context"
	"strings"
)

// Wallets set up before the defaults had subcategories have them as main
// categories: Groceries beside Food & Drinks rather than under it. Regroup
// offers to put them where a new wallet has them.

// RegroupOffer lists the user's categories that a new wallet has under a
// main category, each with ParentID set to the user's main category it
// would go under. Only a main category without subcategories of its own
// is offered, and only under a main category of the same kind.
func (s *Store) RegroupOffer(ctx context.Context, userID int64) ([]Category, error) {
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return nil, err
	}
	return regroupMoves(cats), nil
}

// Regroup makes the moves RegroupOffer lists, those of ids only (ids it no
// longer lists are skipped), and returns the categories it moved.
func (s *Store) Regroup(ctx context.Context, userID int64, ids []int64) ([]Category, error) {
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
	moved := []Category{}
	for _, c := range regroupMoves(cats) {
		if !wanted[c.ID] {
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE categories SET parent_id = $3 WHERE user_id = $1 AND id = $2`, userID, c.ID, *c.ParentID); err != nil {
			return nil, err
		}
		moved = append(moved, c)
	}
	return moved, tx.Commit()
}

func regroupMoves(cats []Category) []Category {
	hasSubs := map[int64]bool{}
	for _, c := range cats {
		if c.ParentID != nil {
			hasSubs[*c.ParentID] = true
		}
	}
	out := []Category{}
	for _, c := range cats {
		if c.ParentID != nil || hasSubs[c.ID] {
			continue
		}
		main, ok := defaultMainOf(c)
		if !ok {
			continue
		}
		parent := mainCategory(cats, c.Kind, main)
		if parent == nil || *parent == c.ID {
			continue
		}
		c.ParentID = parent
		out = append(out, c)
	}
	return out
}

// defaultMainOf is the English name of the main category a new wallet has
// c under, if c is one of the default subcategories (by name, ignoring
// case, in English or Japanese, and of the same kind).
func defaultMainOf(c Category) (string, bool) {
	for _, d := range defaultCategories {
		main, ok := defaultParents[d.Name]
		if ok && d.Kind == c.Kind && (strings.EqualFold(c.Name, d.Name) || c.Name == japaneseNames[d.Name]) {
			return main, true
		}
	}
	return "", false
}
