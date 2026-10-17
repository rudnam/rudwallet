// All records, a month at a time, with filters; a search looks through every
// month. Records can be chosen to delete, categorise or move many at once.
import { useEffect, useState } from 'react';
import { api, errorMessage, type BulkChange, type Transaction } from '../../api';
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

export function RecordsPage({ route }: { route: Route }) {
  const w = useWallet();
  const p = route.params;
  const monthParam = p.get('month'); // YYYY-MM
  const month = monthParam ? new Date(Number(monthParam.slice(0, 4)), Number(monthParam.slice(5, 7)) - 1, 1) : monthStart(new Date());
  const account = Number(p.get('account')) || undefined;
  const categoryParam = p.get('category');
  const category = categoryParam === 'none' ? 'none' : Number(categoryParam) || undefined;
  const search = p.get('q') ?? '';
  const [q, setQ] = useState(search);
  const [open, setOpen] = useState<Transaction | null>(null);
  // While choosing records: the chosen IDs.
  const [selected, setSelected] = useState<Set<number> | null>(null);

  const { records, error, reload } = useRecords({
    from: search ? undefined : month,
    to: search ? undefined : addMonths(month, 1),
    account_id: account,
    category_id: category,
    q: search || undefined,
  });

  // A different month or filter starts the choice afresh.
  const filterKey = p.toString();
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
  const out = confirmed.filter((t) => t.kind === 'expense').reduce((s, t) => s + t.amount, 0);
  const inc = confirmed.filter((t) => t.kind === 'income').reduce((s, t) => s + t.amount, 0);

  return (
    <section className="stack">
      <header className="page-head">
        <div>
          <h1>{t('Records')}</h1>
        </div>
        {records && records.length > 0 && (
          <button type="button" className="secondary" onClick={() => setSelected(selected ? null : new Set())}>
            {selected ? t('Done') : t('Select')}
          </button>
        )}
      </header>
      {search ? (
        <div className="card search-scope">
          <strong>{t('Results from every month')}</strong>
          <button
            type="button"
            className="link"
            onClick={() => {
              setQ('');
              set({ q: undefined });
            }}
          >
            {t('Back to {month}', { month: formatMonth(month) })}
          </button>
        </div>
      ) : (
        <div className="card month-picker">
          <button type="button" className="link" aria-label={t('Previous month')} onClick={() => set({ month: ym(addMonths(month, -1)) })}>
            ‹
          </button>
          <strong>{formatMonth(month)}</strong>
          <button type="button" className="link" aria-label={t('Next month')} onClick={() => set({ month: ym(addMonths(month, 1)) })}>
            ›
          </button>
        </div>
      )}
      <div className="filters">
        <select aria-label={t('Account')} value={account ?? ''} onChange={(e) => set({ account: e.target.value })}>
          <option value="">{t('All accounts')}</option>
          {w.accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <select aria-label={t('Category')} value={category ?? ''} onChange={(e) => set({ category: e.target.value })}>
          <option value="">{t('All categories')}</option>
          <option value="none">{t('No category')}</option>
          {w.categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
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
          <input type="search" aria-label={t('Search records')} placeholder={t('Search payee or note')} value={q} onChange={(e) => setQ(e.target.value)} />
        </form>
      </div>
      <div className="card tiles summary">
        <div className="tile">
          <span className="muted small">{t('Spent')}</span>
          <strong>{w.money(out)}</strong>
        </div>
        <div className="tile">
          <span className="muted small">{t('Income')}</span>
          <strong>{w.money(inc)}</strong>
        </div>
        <div className="tile">
          <span className="muted small">{t('Net')}</span>
          <strong>{w.money(inc - out, true)}</strong>
        </div>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {records === null ? (
        <p className="muted">{t('Loading…')}</p>
      ) : records.length === 0 ? (
        <div className="card stack empty">
          <p className="muted">{t('No records match.')}</p>
        </div>
      ) : (
        <>
          {records.length >= maxRecords && (
            <p className="muted small">{t('Showing the newest {n} matches. Add words or a filter to narrow the search.', { n: maxRecords.toLocaleString() })}</p>
          )}
          {selected && (
            <BulkBar
              records={records}
              selected={selected}
              onSelect={setSelected}
              onChanged={reload}
            />
          )}
          <TransactionList
            records={records}
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
  selected,
  onSelect,
  onChanged,
}: {
  records: Transaction[];
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
            : tn(res.changed, 'Recategorised {n} record.', 'Recategorised {n} records.');
      toast(res.skipped > 0 ? `${done} ${t('{n} didn’t fit and stayed as they were.', { n: res.skipped })}` : done);
      if (change.action === 'delete') onSelect(new Set());
      await Promise.all([onChanged(), w.refresh()]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const ids = [...selected];
  const expenseCats = w.categories.filter((c) => c.kind === 'expense');
  const incomeCats = w.categories.filter((c) => c.kind === 'income');

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
                  {c.name}
                </option>
              ))}
            </optgroup>
            <optgroup label={t('Income')}>
              {incomeCats.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
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
          <button
            type="button"
            className="link danger"
            disabled={busy}
            onClick={() => confirm(tn(n, 'Delete {n} record? This can’t be undone.', 'Delete {n} records? This can’t be undone.')) && apply({ ids, action: 'delete' })}
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
