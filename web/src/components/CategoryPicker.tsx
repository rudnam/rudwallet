// The one way to pick a category from many: a dialog listing every category,
// each main one followed by its subcategories, indented; a main category is a
// choice of its own. With many, a search field on top finds them by name (a
// subcategory also by its main category's), and lists them with their path.
import { useState } from 'react';
import type { Category, CategoryKind } from '../api';
import { inOrder, isSubcategory } from '../categories';
import { t } from '../i18n';
import { CategoryIcon } from './Icon';
import { Dialog } from './Dialog';

/** With more categories than this, the picker has a search field. */
const searchFrom = 10;

export function CategoryPicker({
  categories,
  kind,
  selected,
  title,
  onPick,
  onClose,
}: {
  categories: Category[];
  /** Only this kind's; undefined: both, under headings. */
  kind?: CategoryKind;
  selected: number | null;
  title?: string;
  onPick: (c: Category) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const all = inOrder(categories).filter((c) => !kind || c.kind === kind);
  const q = query.trim().toLowerCase();
  const parentName = (c: Category) => categories.find((p) => p.id === c.parent_id)?.name ?? '';
  const shown = q ? all.filter((c) => c.name.toLowerCase().includes(q) || (isSubcategory(c, categories) && parentName(c).toLowerCase().includes(q))) : all;
  const kinds: CategoryKind[] = kind ? [kind] : ['expense', 'income'];
  return (
    <Dialog title={title ?? t('Choose a category')} onClose={onClose}>
      <div className="stack picker">
        {all.length > searchFrom && (
          <input
            type="search"
            data-autofocus
            aria-label={t('Find a category')}
            placeholder={t('Find a category')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        )}
        {kinds.map((k) => {
          const list = shown.filter((c) => c.kind === k);
          if (list.length === 0) return null;
          return (
            <div key={k} className="stack tight">
              {!kind && <span className="muted small">{k === 'expense' ? t('Expenses') : t('Income')}</span>}
              <ul className="picker-list" role="list">
                {list.map((c) => {
                  const sub = isSubcategory(c, categories);
                  return (
                    <li key={c.id}>
                      <button
                        type="button"
                        className={[sub && !q ? 'sub' : '', c.id === selected ? 'on' : ''].join(' ').trim() || undefined}
                        aria-pressed={c.id === selected}
                        onClick={() => onPick(c)}
                      >
                        <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" />
                        <span>
                          {c.name}
                          {sub && q && <span className="muted small"> · {parentName(c)}</span>}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
        {shown.length === 0 && <p className="muted small">{t('No category matches')}</p>}
      </div>
    </Dialog>
  );
}
