// All records, a month at a time, with filters.
import { useState } from 'react';
import type { Transaction } from '../../api';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, formatMonth, monthStart } from '../../money';
import { navigate, type Route } from '../../router';
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

  const { records, error, reload } = useRecords({
    from: month,
    to: addMonths(month, 1),
    account_id: account,
    category_id: category,
    q: p.get('q') ?? undefined,
  });

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
      <h1>Records</h1>
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
        <TransactionList records={records} onOpen={setOpen} />
      )}
      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
    </section>
  );
}
