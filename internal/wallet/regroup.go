package wallet

import "context"

// A wallet set up before the defaults took their present shape has them
// elsewhere: Groceries beside Food & Drinks rather than under it, Bills &
// Utilities under Housing rather than a main category of its own,
// Subscriptions under Entertainment rather than Bills & Utilities. Regroup
// offers to put them where a new wallet has them.

// RegroupOffer lists the moves that would put the user's categories where a
// new wallet has them, each category with ParentID set to the user's main
// category it would go under. A category is moved under a main category of
// its kind only if it has no subcategories of its own. withMain also lists
// subcategories that new wallets have as main categories, with ParentID nil
// (older apps, which don't ask for them, can't show those).
func (s *Store) RegroupOffer(ctx context.Context, userID int64, withMain bool) ([]Category, error) {
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return nil, err
	}
	if !withMain {
		return nestMoves(cats), nil
	}
	out := unnestMoves(cats)
	return append(out, nestMoves(applyMoves(cats, out))...), nil
}

// Regroup makes the moves RegroupOffer lists, those of ids only (ids it no
// longer lists are skipped), and returns the categories it moved: first those
// that become main categories, then those that go under one, so a category
// can go under one that just became a main category.
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
	for _, phase := range []func([]Category) []Category{unnestMoves, nestMoves} {
		var now []Category
		for _, c := range phase(cats) {
			if !wanted[c.ID] {
				continue
			}
			if _, err := tx.ExecContext(ctx, `UPDATE categories SET parent_id = $3 WHERE user_id = $1 AND id = $2`, userID, c.ID, c.ParentID); err != nil {
				return nil, err
			}
			now = append(now, c)
		}
		cats = applyMoves(cats, now)
		moved = append(moved, now...)
	}
	return moved, tx.Commit()
}

// nestMoves: the user's categories that a new wallet has under a main
// category the user has (as a main one), and that aren't under it yet.
func nestMoves(cats []Category) []Category {
	hasSubs := map[int64]bool{}
	for _, c := range cats {
		if c.ParentID != nil {
			hasSubs[*c.ParentID] = true
		}
	}
	out := []Category{}
	for _, c := range cats {
		main := defaultParents[defaultName(c.Name)]
		if main == "" || hasSubs[c.ID] || defaultKind(c.Name) != c.Kind {
			continue
		}
		parent := mainCategory(cats, c.Kind, main)
		if parent == nil || *parent == c.ID || (c.ParentID != nil && *c.ParentID == *parent) {
			continue
		}
		c.ParentID = parent
		out = append(out, c)
	}
	return out
}

// unnestMoves: the user's subcategories that a new wallet has as main
// categories, each with ParentID nil.
func unnestMoves(cats []Category) []Category {
	out := []Category{}
	for _, c := range cats {
		d := defaultName(c.Name)
		if c.ParentID == nil || d == "" || defaultKind(c.Name) != c.Kind {
			continue
		}
		if _, sub := defaultParents[d]; sub {
			continue
		}
		c.ParentID = nil
		out = append(out, c)
	}
	return out
}

// applyMoves is cats with moves made.
func applyMoves(cats, moves []Category) []Category {
	to := map[int64]*int64{}
	for _, m := range moves {
		to[m.ID] = m.ParentID
	}
	out := make([]Category, len(cats))
	for i, c := range cats {
		if p, ok := to[c.ID]; ok {
			c.ParentID = p
		}
		out[i] = c
	}
	return out
}

// defaultKind is the kind of the default category name is; "" if none.
func defaultKind(name string) string {
	d := defaultName(name)
	for _, c := range defaultCategories {
		if c.Name == d {
			return c.Kind
		}
	}
	return ""
}
