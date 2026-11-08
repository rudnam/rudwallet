// All records, a month at a time, with filters; a search, or a tag, looks
// through every month. Records can be chosen to delete, categorise, move or
// tag many at once.
import { useEffect, useState, type ReactNode } from 'react';
import { inOrder, optionLabel } from '../../categories';
import { api, errorMessage, type BulkChange, type Tag, type Transaction, type TransactionFilter } from '../../api';
import { cleanTag } from '../../components/TagField';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, formatMonth, monthStart } from '../../money';
import { navigate, type Route } from '../../router';
import { useToast } from '../../toast';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { t, tn } from '../../i18n';

/** The most records the server sends at once (maxList in internal/wallet). */
const maxRecords = 2000;

export function RecordsPage({
  route,
  title = t('Records'),
  above,
  hideReview = false,
}: {
  route: Route;
  title?: string;
  /** Shown under the filters, above the summary: Activity's "to check". */
  above?: ReactNode;
  /** Leave out records waiting for review (Activity shows them in "to check"). */
  hideReview?: boolean;
}) {
  const w = useWallet();
  const p = route.params;
  const monthParam = p.get('month'); // YYYY-MM
  const month = monthParam ? new Date(Number(monthParam.slice(0, 4)), Number(monthParam.slice(5, 7)) - 1, 1) : monthStart(new Date());
  const account = Number(p.get('account')) || undefined;
  const categoryParam = p.get('category');
  const category = categoryParam === 'none' ? 'none' : Number(categoryParam) || undefined;
  const search = p.get('q') ?? '';
  const tag = p.get('tag') ?? '';
  const everyMonth = !!(search || tag);
  const [tags, setTags] = useState<Tag[]>(() => api.seen.tags() ?? []);
  const [q, setQ] = useState(search);
  const [open, setOpen] = useState<Transaction | null>(null);
  // While choosing records: the chosen IDs.
  const [selected, setSelected] = useState<Set<number> | null>(null);

  const filterKey = p.toString();
  const filter: TransactionFilter = {
    account_id: account,
    category_id: category,
    // A main category lists its subcategories' records too.
    with_subcategories: typeof category === 'number' || undefined,
    q: search || undefined,
    tag: tag || undefined,
  };
  const { records, error, reload } = useRecords({
    from: everyMonth ? undefined : month,
    to: everyMonth ? undefined : addMonths(month, 1),
    ...filter,
  });

  // Once a month is in, the months either side load too, so stepping to them is instant.
  const loaded = records !== null;
  useEffect(() => {
    if (!loaded || everyMonth) return;
    for (const m of [addMonths(month, -1), addMonths(month, 1)])
      if (m <= new Date()) api.prefetch.transactions({ from: m, to: addMonths(m, 1), ...filter });
    // filterKey holds the month and the filter.
  }, [loaded, filterKey]);

  // The tags to filter by; they change as records are tagged.
  useEffect(() => {
    api.tags().then(setTags, () => {});
  }, [records]);

  // A different month or filter starts the choice afresh.
  useEffect(() => setSelected((s) => (s ? new Set() : s)), [filterKey]);
  // Records that leave the list (moved to an account not shown) leave the choice.
  useEffect(() => setSelected((s) => (s && records ? new Set(records.filter((t) => s.has(t.id)).map((t) => t.id)) : s)), [records]);

  function set(changes: Record<string, string | number | undefined>) {
    const next: Record<string, string | number> = {};
    for (const [k, v] of p.entries()) next[k] = v;
    for (const [k, v] of Object.entries(changes)) {
      if (v === undefined || v === '') delete next[k];
      else next[k] = v;
    }
    navigate('records', next);
  }
  const ym = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;

  const confirmed = (records ?? []).filter((t) => t.status === 'confirmed');
  const shown = records && hideReview ? records.filter((t) => t.status !== 'review') : records;
  const out = confirmed.filter((t) => t.kind === 'expense').reduce((s, t) => s + t.amount, 0);
  const inc = confirmed.filter((t) => t.kind === 'income').reduce((s, t) => s + t.amount, 0);

  return (
    <section className="stack">
      <header className="page-head">
        <div>
          <h1>{title}</h1>
        </div>
        {records && records.length > 0 && (
          <button type="button" className="secondary" onClick={() => setSelected(selected ? null : new Set())}>
            {selected ? t('Done') : t('Select')}
          </button>
        )}
      </header>
      {everyMonth ? (
        <div className="search-scope">
          <strong>{search ? t('Results from every month') : t('#{tag} in every month', { tag })}</strong>
          <button
            type="button"
            className="link"
            onClick={() => {
              setQ('');
              set({ q: undefined, tag: undefined });
            }}
          >
            {t('Back to {month}', { month: formatMonth(month) })}
          </button>
        </div>
      ) : (
        <div className="month-picker">
          <button type="button" className="link" aria-label={t('Previous month')} onClick={() => set({ month: ym(addMonths(month, -1)) })}>
            ‹
          </button>
          <strong>{formatMonth(month)}</strong>
          <button type="button" className="link" aria-label={t('Next month')} onClick={() => set({ month: ym(addMonths(month, 1)) })}>
            ›
          </button>
        </div>
      )}
      <p className="summary">
        <span>
          <span className="muted">{t('Spent')}</span> <strong>{w.money(out)}</strong>
        </span>
        {inc > 0 && (
          <>
            <span>
              <span className="muted">{t('Income')}</span> <strong className="income">{w.money(inc)}</strong>
            </span>
            <span>
              <span className="muted">{t('Net')}</span> <strong>{w.money(inc - out, true)}</strong>
            </span>
          </>
        )}
      </p>
      <div className="filters">
        <select aria-label={t('Account')} className={account ? 'set' : undefined} value={account ?? ''} onChange={(e) => set({ account: e.target.value })}>
          <option value="">{t('Account')}</option>
          {w.accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <select aria-label={t('Category')} className={category ? 'set' : undefined} value={category ?? ''} onChange={(e) => set({ category: e.target.value })}>
          <option value="">{t('Category')}</option>
          <option value="none">{t('No category')}</option>
          {inOrder(w.categories).map((c) => (
            <option key={c.id} value={c.id}>
              {optionLabel(c, w.categories)}
            </option>
          ))}
        </select>
        <form
          className="search"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            set({ q });
          }}
        >
          <input type="search" aria-label={t('Search records')} placeholder={t('Search')} value={q} onChange={(e) => setQ(e.target.value)} />
        </form>
      </div>
      {(tags.length > 0 || tag) && (
        <ul className="tag-filter" aria-label={t('Filter by tag')}>
          {tags.map((x) => {
            const on = x.name.toLowerCase() === tag.toLowerCase();
            return (
              <li key={x.name}>
                <button type="button" className={on ? 'chip on' : 'chip'} aria-pressed={on} onClick={() => set({ tag: on ? undefined : x.name })}>
                  #{x.name} <span className="muted">{x.count}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {above}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {shown === null ? (
        <p className="muted">{t('Loading…')}</p>
      ) : shown.length === 0 ? (
        <div className="card stack empty">
          <p className="muted">{t('No records match.')}</p>
        </div>
      ) : (
        <>
          {(records?.length ?? 0) >= maxRecords && (
            <p className="muted small">{t('Showing the newest {n} matches. Add words or a filter to narrow the search.', { n: maxRecords.toLocaleString() })}</p>
          )}
          {selected && (
            <BulkBar
              records={shown}
              tags={tags}
              selected={selected}
              onSelect={setSelected}
              onChanged={reload}
            />
          )}
          <TransactionList
            records={shown}
            onOpen={setOpen}
            selected={selected ?? undefined}
            onToggle={(t) => {
              const next = new Set(selected);
              if (!next.delete(t.id)) next.add(t.id);
              setSelected(next);
            }}
          />
        </>
      )}
      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
    </section>
  );
}

/** What to do with the chosen records: change their category or account, or delete them. */
function BulkBar({
  records,
  tags,
  selected,
  onSelect,
  onChanged,
}: {
  records: Transaction[];
  tags: Tag[];
  selected: Set<number>;
  onSelect: (s: Set<number>) => void;
  onChanged: () => Promise<void>;
}) {
  const w = useWallet();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const n = selected.size;
  const all = n === records.length;
  async function apply(change: BulkChange) {
    setBusy(true);
    setError('');
    try {
      const res = await api.bulkTransactions(change);
      const done =
        change.action === 'delete'
          ? tn(res.changed, 'Deleted {n} record.', 'Deleted {n} records.')
          : change.action === 'move'
            ? tn(res.changed, 'Moved {n} record.', 'Moved {n} records.')
            : change.action === 'tag'
              ? tn(res.changed, 'Tagged {n} record #{tag}.', 'Tagged {n} records #{tag}.', { tag: change.tag })
              : change.action === 'untag'
                ? tn(res.changed, 'Took #{tag} off {n} record.', 'Took #{tag} off {n} records.', { tag: change.tag })
                : tn(res.changed, 'Recategorised {n} record.', 'Recategorised {n} records.');
      toast(
        res.skipped > 0 ? `${done} ${t('{n} didn’t fit and stayed as they were.', { n: res.skipped })}` : done,
        change.action === 'delete' && res.changed > 0 ? { label: t('Undo'), run: () => restore(change.ids) } : undefined,
      );
      if (change.action === 'delete') onSelect(new Set());
      await Promise.all([onChanged(), w.refresh()]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  // Brings back the records just deleted.
  async function restore(deleted: number[]) {
    try {
      const res = await api.restoreTransactions(deleted);
      toast(tn(res.restored, 'Restored {n} record.', 'Restored {n} records.'));
      await Promise.all([onChanged(), w.refresh()]);
    } catch (err) {
      toast(t("Couldn't restore: {error}", { error: errorMessage(err) }));
    }
  }

  const ids = [...selected];
  // Tags on the chosen records, to take off.
  const chosenTags = [
    ...new Map(records.filter((r) => selected.has(r.id)).flatMap((r) => r.tags ?? []).map((x) => [x.toLowerCase(), x])).values(),
  ];

  function changeTag(v: string) {
    if (v === 'new') {
      const typed = cleanTag(prompt(t('Tag the chosen records with:')) ?? '');
      if (typed) apply({ ids, action: 'tag', tag: typed });
    } else if (v.startsWith('add:')) apply({ ids, action: 'tag', tag: v.slice(4) });
    else if (v.startsWith('remove:')) apply({ ids, action: 'untag', tag: v.slice(7) });
  }
  const expenseCats = inOrder(w.categories).filter((c) => c.kind === 'expense');
  const incomeCats = inOrder(w.categories).filter((c) => c.kind === 'income');

  return (
    <div className="card bulk-bar" role="region" aria-label={t('Chosen records')}>
      <span className="bulk-count grow" aria-live="polite">
        {n === 0 ? t('Choose records') : t('{n} chosen', { n })}
      </span>
      <button type="button" className="link" onClick={() => onSelect(all ? new Set() : new Set(records.map((t) => t.id)))}>
        {all ? t('Choose none') : t('Choose all {n}', { n: records.length })}
      </button>
      {n > 0 && (
        <div className="bulk-actions">
          <select
            aria-label={t('Set category')}
            value=""
            disabled={busy}
            onChange={(e) => {
              const v = e.target.value;
              if (v) apply({ ids, action: 'categorize', category_id: v === 'none' ? null : Number(v) });
            }}
          >
            <option value="">{t('Category…')}</option>
            <option value="none">{t('No category')}</option>
            <optgroup label={t('Expenses')}>
              {expenseCats.map((c) => (
                <option key={c.id} value={c.id}>
                  {optionLabel(c, w.categories)}
                </option>
              ))}
            </optgroup>
            <optgroup label={t('Income')}>
              {incomeCats.map((c) => (
                <option key={c.id} value={c.id}>
                  {optionLabel(c, w.categories)}
                </option>
              ))}
            </optgroup>
          </select>
          <select
            aria-label={t('Move to account')}
            value=""
            disabled={busy}
            onChange={(e) => e.target.value && apply({ ids, action: 'move', account_id: Number(e.target.value) })}
          >
            <option value="">{t('Account…')}</option>
            {w.accounts
              .filter((a) => !a.archived)
              .map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
          </select>
          <select aria-label={t('Tag records')} value="" disabled={busy} onChange={(e) => changeTag(e.target.value)}>
            <option value="">{t('Tag…')}</option>
            <optgroup label={t('Add tag')}>
              <option value="new">{t('New tag…')}</option>
              {tags.map((x) => (
                <option key={x.name} value={`add:${x.name}`}>
                  #{x.name}
                </option>
              ))}
            </optgroup>
            {chosenTags.length > 0 && (
              <optgroup label={t('Remove tag')}>
                {chosenTags.map((x) => (
                  <option key={x} value={`remove:${x}`}>
                    #{x}
                  </option>
                ))}
              </optgroup>
            )}
          </select>
          <button
            type="button"
            className="link danger"
            disabled={busy}
            onClick={() => confirm(tn(n, 'Delete {n} record?', 'Delete {n} records?')) && apply({ ids, action: 'delete' })}
          >
            {t('Delete')}
          </button>
        </div>
      )}
      {error && (
        <p role="alert" className="error small">
          {error}
        </p>
      )}
    </div>
  );
}
