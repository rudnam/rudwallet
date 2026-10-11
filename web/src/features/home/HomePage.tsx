// The overview: balances, this month's spending, budgets, latest records.
import { useEffect, useState } from 'react';
import { api, type Budget, type Transaction } from '../../api';
import { CategoryBars, DailyColumns, Meter, type BarItem } from '../../components/charts';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, daysInMonth, formatCompact, monthStart } from '../../money';
import { href, navigate } from '../../router';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';

export function HomePage() {
  const w = useWallet();
  const [month] = useState(() => monthStart(new Date()));
  const { records, reload } = useRecords({ from: month, to: addMonths(month, 1) });
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [open, setOpen] = useState<Transaction | null>(null);

  useEffect(() => {
    api.budgets().then(setBudgets, () => setBudgets([]));
  }, []);

  const confirmed = (records ?? []).filter((t) => t.status === 'confirmed');
  const spent = sum(confirmed.filter((t) => t.kind === 'expense'));
  const income = sum(confirmed.filter((t) => t.kind === 'income'));
  const total = w.accounts.filter((a) => !a.archived).reduce((s, a) => s + a.balance, 0);
  const byCategory = spendingByCategory(confirmed, w);
  const daily = new Array<number>(daysInMonth(month)).fill(0);
  for (const t of confirmed) if (t.kind === 'expense') daily[new Date(t.occurred_at).getDate() - 1] += t.amount;

  return (
    <section className="stack">
      <h1>Overview</h1>

      {w.review_count > 0 && (
        <a className="banner" href={href('review')}>
          <span aria-hidden="true">✉</span>
          <span className="grow">
            <strong>
              {w.review_count} {w.review_count === 1 ? 'payment' : 'payments'} from email
            </strong>{' '}
            waiting for review
          </span>
          <span aria-hidden="true">›</span>
        </a>
      )}

      <div className="card hero">
        <span className="muted">Total balance</span>
        <span className="hero-figure">{w.money(total)}</span>
        <div className="tiles">
          <Tile label="Spent this month" value={w.money(spent)} />
          <Tile label="Income" value={w.money(income)} />
          <Tile label="Net" value={w.money(income - spent, true)} />
        </div>
      </div>

      <div className="accounts" aria-label="Accounts" role="list">
        {w.accounts
          .filter((a) => !a.archived)
          .map((a) => (
            <a key={a.id} role="listitem" className="card account-card" href={href('records', { account: a.id })}>
              <span className="swatch" style={{ background: a.color }} aria-hidden="true" />
              <span className="muted small">{a.name}</span>
              <strong>{w.money(a.balance)}</strong>
            </a>
          ))}
      </div>

      <div className="card stack">
        <h2>Spending this month</h2>
        {byCategory.length === 0 ? (
          <p className="muted">No spending recorded this month yet.</p>
        ) : (
          <>
            <CategoryBars
              items={byCategory}
              format={(n) => w.money(n)}
              onSelect={(key) => navigate('records', { category: key })}
            />
            <DailyColumns
              values={daily}
              labelFor={(i) =>
                new Date(month.getFullYear(), month.getMonth(), i + 1).toLocaleDateString(undefined, {
                  month: 'short',
                  day: 'numeric',
                })
              }
              format={(n) => w.money(n)}
              compact={(n) => formatCompact(n, w.settings)}
            />
          </>
        )}
      </div>

      {budgets.length > 0 && (
        <div className="card stack">
          <div className="row">
            <h2 className="grow">Budgets</h2>
            <a href={href('budgets')}>All budgets</a>
          </div>
          {budgets.map((b) => (
            <div key={b.id} className="stack tight">
              <span>{b.name}</span>
              <Meter spent={budgetSpent(b, confirmed)} limit={b.amount} format={(n) => w.money(n)} />
            </div>
          ))}
        </div>
      )}

      <div className="stack">
        <div className="row">
          <h2 className="grow">Latest records</h2>
          <a href={href('records')}>All records</a>
        </div>
        {records === null ? (
          <p className="muted">Loading…</p>
        ) : records.length === 0 ? (
          <p className="muted">Nothing yet this month. Add a record with the + button.</p>
        ) : (
          <TransactionList records={records.slice(0, 6)} onOpen={setOpen} label="Latest records" />
        )}
      </div>

      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
    </section>
  );
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="tile">
      <span className="muted small">{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function sum(list: Transaction[]): number {
  return list.reduce((s, t) => s + t.amount, 0);
}

export function spendingByCategory(list: Transaction[], w: ReturnType<typeof useWallet>): BarItem[] {
  const totals = new Map<number | 'none', number>();
  for (const t of list) {
    if (t.kind !== 'expense' || t.status !== 'confirmed') continue;
    const k = t.category_id ?? 'none';
    totals.set(k, (totals.get(k) ?? 0) + t.amount);
  }
  return [...totals.entries()]
    .map(([k, amount]) => {
      const c = k === 'none' ? undefined : w.category(k);
      return { key: k, label: c?.name ?? 'No category', icon: c?.icon ?? '•', amount };
    })
    .sort((a, b) => b.amount - a.amount);
}

export function budgetSpent(b: Budget, list: Transaction[]): number {
  return list
    .filter((t) => t.kind === 'expense' && t.status === 'confirmed' && (b.category_id === null || t.category_id === b.category_id))
    .reduce((s, t) => s + t.amount, 0);
}
