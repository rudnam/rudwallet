// Monthly spending limits, per category or for everything.
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Budget } from '../../api';
import { Meter } from '../../components/charts';
import { Dialog } from '../../components/Dialog';
import { addMonths, amountInput, formatMonth, monthStart, parseAmount } from '../../money';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { budgetSpent } from '../home/HomePage';

export function BudgetsPage() {
  const w = useWallet();
  const [month] = useState(() => monthStart(new Date()));
  const { records } = useRecords({ from: month, to: addMonths(month, 1), status: 'confirmed' });
  const [budgets, setBudgets] = useState<Budget[] | null>(null);
  const [editing, setEditing] = useState<Budget | 'new' | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.budgets().then(setBudgets, (err) => setError(errorMessage(err)));
  }, []);
  useEffect(load, [load]);

  return (
    <section className="stack">
      <div className="row">
        <h1 className="grow">Budgets</h1>
        <button type="button" onClick={() => setEditing('new')}>
          New budget
        </button>
      </div>
      <p className="muted">{formatMonth(month)}</p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {budgets === null ? (
        <p className="muted">Loading…</p>
      ) : budgets.length === 0 ? (
        <div className="card stack empty">
          <p>No budgets yet.</p>
          <p className="muted small">Set a monthly limit for a category, or for all your spending, and watch it fill up.</p>
        </div>
      ) : (
        <ul className="list" aria-label="Budgets">
          {budgets.map((b) => {
            const c = w.category(b.category_id);
            return (
              <li key={b.id} className="card stack tight">
                <div className="row">
                  <strong className="grow">{b.name}</strong>
                  <span className="muted small">{c ? `${c.icon} ${c.name}` : 'All spending'}</span>
                  <button type="button" className="link" aria-label={`Edit budget ${b.name}`} onClick={() => setEditing(b)}>
                    Edit
                  </button>
                </div>
                <Meter spent={budgetSpent(b, records ?? [])} limit={b.amount} format={(n) => w.money(n)} />
              </li>
            );
          })}
        </ul>
      )}
      {editing && (
        <BudgetForm
          budget={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            load();
          }}
        />
      )}
    </section>
  );
}

function BudgetForm({ budget, onClose, onSaved }: { budget?: Budget; onClose: () => void; onSaved: () => void }) {
  const w = useWallet();
  const exp = w.settings.currency_exponent;
  const [name, setName] = useState(budget?.name ?? '');
  const [categoryId, setCategoryId] = useState<number | null>(budget?.category_id ?? null);
  const [amount, setAmount] = useState(budget ? amountInput(budget.amount, exp) : '');
  const [error, setError] = useState('');

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(amount, exp);
    if (!minor) {
      setError('Enter a monthly limit.');
      return;
    }
    const input = { name: name || w.category(categoryId)?.name || 'All spending', category_id: categoryId, amount: minor };
    try {
      if (budget) await api.updateBudget(budget.id, input);
      else await api.createBudget(input);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!budget || !confirm(`Delete the budget "${budget.name}"?`)) return;
    try {
      await api.deleteBudget(budget.id);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Dialog title={budget ? 'Edit budget' : 'New budget'} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <label className="field">
          Category
          <select value={categoryId ?? ''} onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">All spending</option>
            {w.categories
              .filter((c) => c.kind === 'expense')
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.icon} {c.name}
                </option>
              ))}
          </select>
        </label>
        <label className="field">
          Monthly limit ({w.settings.currency})
          <input inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        <label className="field">
          Name (optional)
          <input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          {budget && (
            <button type="button" className="link danger" onClick={remove}>
              Delete
            </button>
          )}
          <span className="grow" />
          <button type="submit">Save</button>
        </div>
      </form>
    </Dialog>
  );
}
