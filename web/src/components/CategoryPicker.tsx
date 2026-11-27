// The one way to pick a category from many, as on the phone: the main
// categories, a row each. A row with a chevron (a main category with
// subcategories) opens that category's own page; any other row picks. The page
// lists the main category first, then its subcategories, in rows of the same
// shape; Back (or Escape) returns to the main categories. Nothing opens in
// place, so nothing moves under the pointer. Searching lists every match,
// subcategories with their main category's name.
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { Category, CategoryKind } from '../api';
import { inOrder, isSubcategory, mainCategoryId, subcategoriesOf } from '../categories';
import { t } from '../i18n';
import { CategoryIcon, Icon, icons } from './Icon';
import { Dialog } from './Dialog';

/** A choice in the category picker that isn't a category, above them: "All categories", "No category". */
export interface CategoryPickerExtra {
  label: string;
  selected?: boolean;
  onPick: () => void;
}

/** With more categories than this, the picker has a search field. */
const searchFrom = 10;

export function CategoryPicker({
  categories,
  kind,
  selected,
  title,
  mainTakesInSubs = false,
  extras = [],
  onPick,
  onClose,
}: {
  categories: Category[];
  /** Only this kind's; undefined: both, under headings. */
  kind?: CategoryKind;
  selected: number | null;
  title?: string;
  /** Picking a main category means it and its subcategories (a filter): its page says "All of …", not "… (general)". */
  mainTakesInSubs?: boolean;
  extras?: CategoryPickerExtra[];
  onPick: (c: Category) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  // The main category whose page is showing, or null for the list of them.
  const [open, setOpen] = useState<number | null>(null);
  const all = inOrder(categories).filter((c) => !kind || c.kind === kind);
  const q = query.trim().toLowerCase();
  const parentName = (c: Category) => categories.find((p) => p.id === c.parent_id)?.name ?? '';
  const matches = all.filter((c) => c.name.toLowerCase().includes(q) || (isSubcategory(c, categories) && parentName(c).toLowerCase().includes(q)));
  const kinds: CategoryKind[] = kind ? [kind] : ['expense', 'income'];
  const openMain = all.find((c) => c.id === open && subcategoriesOf(c.id, categories).length > 0);
  const holding = mainCategoryId(selected, categories);
  // Focus follows the page: its Back button when it opens, the row it came from when it closes,
  // so the keyboard (and Escape) stay in the picker and a screen reader lands where it should.
  const pickerRef = useRef<HTMLDivElement>(null);
  const cameFrom = useRef<number | null>(null);
  useEffect(() => {
    const el = pickerRef.current;
    if (!el) return;
    if (openMain) {
      cameFrom.current = openMain.id;
      el.querySelector<HTMLElement>('.picker-back')?.focus();
    } else if (cameFrom.current !== null) {
      el.querySelector<HTMLElement>(`[data-main="${cameFrom.current}"]`)?.focus();
      cameFrom.current = null;
    }
  }, [openMain]);
  useEffect(() => {
    pickerRef.current?.querySelector<HTMLElement>('.picker-list button.on, .picker-list button.holds')?.scrollIntoView({ block: 'nearest' });
  }, []);
  const mainLabel = (c: Category) => (mainTakesInSubs ? t('All of {name}', { name: c.name }) : t('{name} (general)', { name: c.name }));

  // Escape on a page goes back to the main categories, as the phone's back does; on the list, it closes.
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter' && (e.target as HTMLElement).tagName === 'INPUT') {
      e.preventDefault();
      return;
    }
    if (e.key === 'Escape' && openMain && !q) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(null);
    }
  };

  const row = (c: Category, label: string, detail?: string) => (
    <li key={c.id}>
      <button
        type="button"
        className={c.id === selected ? 'on' : undefined}
        aria-pressed={c.id === selected}
        onClick={() => {
          onPick(c);
          onClose();
        }}
      >
        <CategoryIcon icon={c.icon} name={c.name} color={c.color} />
        <span className="picker-main">
          <span>{label}</span>
          {detail && <span className="muted small">{detail}</span>}
        </span>
        {c.id === selected && <Icon d={icons.check} className="picker-tick" />}
      </button>
    </li>
  );

  let body;
  if (q) {
    body = (
      <>
        {kinds.map((k) => {
          const list = matches.filter((c) => c.kind === k);
          if (list.length === 0) return null;
          return (
            <div key={k} className="stack tight">
              {!kind && <span className="muted small">{k === 'expense' ? t('Expenses') : t('Income')}</span>}
              <ul className="picker-list" role="list">
                {list.map((c) => row(c, c.name, isSubcategory(c, categories) ? parentName(c) : undefined))}
              </ul>
            </div>
          );
        })}
        {matches.length === 0 && <p className="muted small">{t('No category matches')}</p>}
      </>
    );
  } else if (openMain) {
    body = (
      <div className="stack tight picker-page" key={openMain.id}>
        <div className="picker-subhead">
          <button type="button" className="picker-back" aria-label={t('Back')} onClick={() => setOpen(null)}>
            <Icon d={icons.back} className="glyph" />
          </button>
          <strong className="picker-subhead-title">{openMain.name}</strong>
        </div>
        <ul className="picker-list" role="list" aria-label={openMain.name}>
          {row(openMain, mainLabel(openMain))}
          {subcategoriesOf(openMain.id, categories).map((s) => row(s, s.name))}
        </ul>
      </div>
    );
  } else {
    body = (
      <>
        {extras.length > 0 && (
          <ul className="picker-list picker-extras" role="list">
            {extras.map((e) => (
              <li key={e.label}>
                <button
                  type="button"
                  className={e.selected ? 'on' : undefined}
                  aria-pressed={!!e.selected}
                  onClick={() => {
                    e.onPick();
                    onClose();
                  }}
                >
                  <span className="picker-blank" aria-hidden="true" />
                  <span className="picker-main">
                    <span className={e.selected ? undefined : 'muted'}>{e.label}</span>
                  </span>
                  {e.selected && <Icon d={icons.check} className="picker-tick" />}
                </button>
              </li>
            ))}
          </ul>
        )}
        {kinds.map((k) => {
          const list = all.filter((c) => !isSubcategory(c, categories) && c.kind === k);
          if (list.length === 0) return null;
          return (
            <div key={k} className="stack tight picker-page">
              {!kind && <span className="muted small">{k === 'expense' ? t('Expenses') : t('Income')}</span>}
              <ul className="picker-list" role="list">
                {list.map((c) => {
                  const subs = subcategoriesOf(c.id, categories);
                  if (subs.length === 0) return row(c, c.name);
                  const chosenSub = subs.find((s) => s.id === selected);
                  const holds = holding === c.id;
                  const detail = chosenSub ? chosenSub.name : selected === c.id ? mainLabel(c) : subs.map((s) => s.name).join(' · ');
                  return (
                    <li key={c.id}>
                      <button
                        type="button"
                        className={holds ? 'holds' : undefined}
                        data-main={c.id}
                        aria-label={`${c.name}, ${detail}`}
                        onClick={() => setOpen(c.id)}
                      >
                        <CategoryIcon icon={c.icon} name={c.name} color={c.color} />
                        <span className="picker-main">
                          <span>{c.name}</span>
                          <span className="small">{detail}</span>
                        </span>
                        {holds && <Icon d={icons.check} className="picker-tick" />}
                        <Icon d={icons.chevron} className="picker-caret" />
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </>
    );
  }

  return (
    <Dialog title={title ?? t('Choose a category')} onClose={onClose}>
      <div className="stack picker" ref={pickerRef} onKeyDown={onKeyDown}>
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
        {body}
      </div>
    </Dialog>
  );
}
