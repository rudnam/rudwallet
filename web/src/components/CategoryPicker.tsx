// The one way to pick a category from many: the main categories, a row each,
// with its subcategories named small under it, so it's plain at a glance
// whether a more specific one exists. A main category with subcategories
// opens in place to show them as chips (it first, for what none fits); one
// without is picked at once. Searching lists every match, subcategories too.
import { useState } from 'react';
import type { Category, CategoryKind } from '../api';
import { inOrder, isSubcategory, mainCategoryId, subcategoriesOf } from '../categories';
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
  // The main category open, showing its subcategories: at first, the one holding the choice.
  const [open, setOpen] = useState<number | null>(() => {
    const main = mainCategoryId(selected, categories);
    return main !== null && subcategoriesOf(main, categories).length > 0 ? main : null;
  });
  const all = inOrder(categories).filter((c) => !kind || c.kind === kind);
  const q = query.trim().toLowerCase();
  const parentName = (c: Category) => categories.find((p) => p.id === c.parent_id)?.name ?? '';
  const matches = all.filter((c) => c.name.toLowerCase().includes(q) || (isSubcategory(c, categories) && parentName(c).toLowerCase().includes(q)));
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
          const list = (q ? matches : all.filter((c) => !isSubcategory(c, categories))).filter((c) => c.kind === k);
          if (list.length === 0) return null;
          return (
            <div key={k} className="stack tight">
              {!kind && <span className="muted small">{k === 'expense' ? t('Expenses') : t('Income')}</span>}
              <ul className="picker-list" role="list">
                {list.map((c) => {
                  // Searching: every match, a subcategory with its main category's name.
                  if (q) {
                    const sub = isSubcategory(c, categories);
                    return (
                      <li key={c.id}>
                        <button type="button" className={c.id === selected ? 'on' : undefined} aria-pressed={c.id === selected} onClick={() => onPick(c)}>
                          <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" />
                          <span>
                            {c.name}
                            {sub && <span className="muted small"> · {parentName(c)}</span>}
                          </span>
                        </button>
                      </li>
                    );
                  }
                  const subs = subcategoriesOf(c.id, categories);
                  const holds = selected === c.id || subs.some((s) => s.id === selected);
                  const isOpen = open === c.id;
                  return (
                    <li key={c.id} className="picker-group">
                      <button
                        type="button"
                        className={[holds ? 'holds' : '', subs.length === 0 && c.id === selected ? 'on' : ''].join(' ').trim() || undefined}
                        aria-expanded={subs.length > 0 ? isOpen : undefined}
                        aria-pressed={subs.length === 0 ? c.id === selected : undefined}
                        onClick={() => (subs.length > 0 ? setOpen(isOpen ? null : c.id) : onPick(c))}
                      >
                        <CategoryIcon icon={c.icon} name={c.name} color={c.color} />
                        <span className="picker-main">
                          <span>{c.name}</span>
                          {subs.length > 0 && <span className="muted small">{subs.map((s) => s.name).join(' · ')}</span>}
                        </span>
                        {subs.length > 0 && (
                          <span className="picker-caret" aria-hidden="true">
                            {isOpen ? '▴' : '▾'}
                          </span>
                        )}
                      </button>
                      {isOpen && (
                        <div className="chips picker-subs" role="group" aria-label={t('Subcategories of {name}', { name: c.name })}>
                          {[c, ...subs].map((s) => (
                            <button
                              key={s.id}
                              type="button"
                              className={s.id === selected ? 'chip on' : 'chip'}
                              aria-pressed={s.id === selected}
                              onClick={() => onPick(s)}
                            >
                              <CategoryIcon icon={s.icon} name={s.name} color={s.color} size="small" />
                              {s.id === c.id ? t('{name} (general)', { name: c.name }) : s.name}
                            </button>
                          ))}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
        {q && matches.length === 0 && <p className="muted small">{t('No category matches')}</p>}
      </div>
    </Dialog>
  );
}
