// Main categories and their subcategories (one level deep): a subcategory
// has a parent_id. One whose main category isn't in the list is treated as
// a main one.
import type { Category, Stats } from './api';

/** Under a main category that's in the list. */
export function isSubcategory(c: Category, all: Category[]): boolean {
  return c.parent_id != null && all.some((p) => p.id === c.parent_id);
}

/** A main category's subcategories, in the user's order. */
export function subcategoriesOf(id: number | null, all: Category[]): Category[] {
  return id === null ? [] : all.filter((c) => c.parent_id === id);
}

/** The main category id is, or is under. */
export function mainCategoryId(id: number | null, all: Category[]): number | null {
  const c = all.find((x) => x.id === id);
  return c && isSubcategory(c, all) ? c.parent_id! : id;
}

/** Every category, each main one followed by its subcategories: the order lists of them show. */
export function inOrder(all: Category[]): Category[] {
  return all.filter((c) => !isSubcategory(c, all)).flatMap((m) => [m, ...subcategoriesOf(m.id, all)]);
}

/** A menu option's label: a subcategory indented under its main category. */
export function optionLabel(c: Category, all: Category[]): string {
  return isSubcategory(c, all) ? `   ${c.name}` : c.name;
}

/** "Food & Drinks › Cafés" for a subcategory; a main category's own name. */
export function categoryPath(id: number | null, all: Category[]): string | undefined {
  const c = all.find((x) => x.id === id);
  if (!c) return undefined;
  const main = isSubcategory(c, all) ? all.find((x) => x.id === c.parent_id) : undefined;
  return main ? `${main.name} › ${c.name}` : c.name;
}

/** The totals with each subcategory's added into its main category's. */
export function byMainCategory(s: Stats, all: Category[]): Stats {
  const merged = new Map<string, Stats['categories'][number]>();
  for (const t of s.categories) {
    const id = mainCategoryId(t.category_id, all);
    const key = `${t.kind}:${id}`;
    const m = merged.get(key);
    merged.set(key, m ? { ...m, amount: m.amount + t.amount, count: m.count + t.count } : { ...t, category_id: id });
  }
  return { ...s, categories: [...merged.values()].sort((a, b) => b.amount - a.amount) };
}

/** What a budget on id takes in: it and its subcategories. */
export function withSubcategories(id: number, all: Category[]): Set<number> {
  return new Set([id, ...subcategoriesOf(id, all).map((c) => c.id)]);
}

/**
 * The categories as the app shows them: a subcategory wears its main
 * category's colour, whatever it was saved with, so a main category and its
 * subcategories always match (the icons tell them apart). One whose main
 * category isn't in the list keeps its own.
 */
export function wearingMainColours(all: Category[]): Category[] {
  const colours = new Map(all.map((c) => [c.id, c.color]));
  return all.map((c) => {
    const main = c.parent_id != null ? colours.get(c.parent_id) : undefined;
    return main !== undefined ? { ...c, color: main } : c;
  });
}
