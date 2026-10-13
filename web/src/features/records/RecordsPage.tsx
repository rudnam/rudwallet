// All records, a month at a time, with filters. Records can be chosen to
// delete, categorise or move many at once.
import { useEffect, useState } from 'react';
import { api, errorMessage, type BulkChange, type Transaction } from '../../api';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, formatMonth, monthStart } from '../../money';
import { navigate, type Route } from '../../router';
import { useToast } from '../../toast';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';

export function RecordsPage({ route }: { route: Route }) {
  const w = useWallet();
  const p = route.params;
  const monthParam = p.get('month'); // YYYY-MM
  const month = monthParam ? new Date(Number(monthParam.slice(0, 4)), Number(monthParam.slice(5, 7)) - 1, 1) : monthStart(new Date());
  const account = Number(p.get('account')) || undefined;
  const categoryParam = p.get('category');
  const category = categoryParam === 'none' ? 'none' : Number(categoryParam) || undefined;
  const [q, setQ] = useState(p.get('q') ?? '');
  const [open, setOpen] = useState<Transaction | null>(null);
  // While choosing records: the chosen IDs.
  const [selected, setSelected] = useState<Set<number> | null>(null);

  const { records, error, reload } = useRecords({
    from: month,
    to: addMonths(month, 1),
    account_id: account,
    category_id: category,
    q: p.get('q') ?? undefined,
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
          <h1>Records</h1>
        </div>
        {records && records.length > 0 && (
          <button type="button" className="secondary" onClick={() => setSelected(selected ? null : new Set())}>
            {selected ? 'Done' : 'Select'}
          </button>
        )}
      </header>
      <div className="card month-picker">
        <button type="button" className="link" aria-label="Previous month" onClick={() => set({ month: ym(addMonths(month, -1)) })}>
          ‹
        </button>
        <strong>{formatMonth(month)}</strong>
        <button type="button" className="link" aria-label="Next month" onClick={() => set({ month: ym(addMonths(month, 1)) })}>
          ›
        </button>
      </div>
      <div className="filters">
        <select aria-label="Account" value={account ?? ''} onChange={(e) => set({ account: e.target.value })}>
          <option value="">All accounts</option>
          {w.accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <select aria-label="Category" value={category ?? ''} onChange={(e) => set({ category: e.target.value })}>
          <option value="">All categories</option>
          <option value="none">No category</option>
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
          <input type="search" aria-label="Search records" placeholder="Search payee or note" value={q} onChange={(e) => setQ(e.target.value)} />
        </form>
      </div>
      <div className="card tiles summary">
        <div className="tile">
          <span className="muted small">Spent</span>
          <strong>{w.money(out)}</strong>
        </div>
        <div className="tile">
          <span className="muted small">Income</span>
          <strong>{w.money(inc)}</strong>
        </div>
        <div className="tile">
          <span className="muted small">Net</span>
          <strong>{w.money(inc - out, true)}</strong>
        </div>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {records === null ? (
        <p className="muted">Loading…</p>
      ) : records.length === 0 ? (
        <div className="card stack empty">
          <p className="muted">No records match.</p>
        </div>
      ) : (
        <>
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
  const noun = (k: number) => (k === 1 ? 'record' : 'records');

  async function apply(change: BulkChange, done: string) {
    setBusy(true);
    setError('');
    try {
      const res = await api.bulkTransactions(change);
      toast(
        res.skipped > 0
          ? `${done} ${res.changed} ${noun(res.changed)}. ${res.skipped} didn’t fit and stayed as they were.`
          : `${done} ${res.changed} ${noun(res.changed)}.`,
      );
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
    <div className="card bulk-bar" role="region" aria-label="Chosen records">
      <span className="bulk-count grow" aria-live="polite">
        {n === 0 ? 'Choose records' : `${n} chosen`}
      </span>
      <button type="button" className="link" onClick={() => onSelect(all ? new Set() : new Set(records.map((t) => t.id)))}>
        {all ? 'Choose none' : `Choose all ${records.length}`}
      </button>
      {n > 0 && (
        <div className="bulk-actions">
          <select
            aria-label="Set category"
            value=""
            disabled={busy}
            onChange={(e) => {
              const v = e.target.value;
              if (v) apply({ ids, action: 'categorize', category_id: v === 'none' ? null : Number(v) }, 'Recategorised');
            }}
          >
            <option value="">Category…</option>
            <option value="none">No category</option>
            <optgroup label="Expenses">
              {expenseCats.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </optgroup>
            <optgroup label="Income">
              {incomeCats.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </optgroup>
          </select>
          <select
            aria-label="Move to account"
            value=""
            disabled={busy}
            onChange={(e) => e.target.value && apply({ ids, action: 'move', account_id: Number(e.target.value) }, 'Moved')}
          >
            <option value="">Account…</option>
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
            onClick={() => confirm(`Delete ${n} ${noun(n)}? This can’t be undone.`) && apply({ ids, action: 'delete' }, 'Deleted')}
          >
            Delete
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
