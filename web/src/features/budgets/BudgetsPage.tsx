// Monthly spending limits, per category or for everything.
import { useCallback, useEffect, useId, useState, type FormEvent } from 'react';
import { categoryPath } from '../../categories';
import { api, errorMessage, type Budget } from '../../api';
import { CategoryPicker } from '../../components/CategoryPicker';
import { Meter } from '../../components/charts';
import { Dialog } from '../../components/Dialog';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { addMonths, amountInput, formatMonth, monthStart, parseAmount } from '../../money';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { t } from '../../i18n';
import { budgetSpent } from '../home/HomePage';
import { href } from '../../router';

export function BudgetsPage() {
  const w = useWallet();
  const [month] = useState(() => monthStart(new Date()));
  const { records } = useRecords({ from: month, to: addMonths(month, 1), status: 'confirmed' });
  const [budgets, setBudgets] = useState<Budget[] | null>(() => api.seen.budgets() ?? null);
  const [editing, setEditing] = useState<Budget | 'new' | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.budgets().then(setBudgets, (err) => setError(errorMessage(err)));
  }, []);
  useEffect(load, [load]);

  return (
    <section className="stack">
      <a className="back" href={href('stats')}>
        <Icon d={icons.back} />
        {t('Insights')}
      </a>
      <header className="page-head">
        <div>
          <span className="eyebrow">{formatMonth(month)}</span>
          <h1>{t('Budgets')}</h1>
        </div>
        <button type="button" className="secondary" onClick={() => setEditing('new')}>
          {t('New budget')}
        </button>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {budgets === null ? (
        <p className="muted">{t('Loading…')}</p>
      ) : budgets.length === 0 ? (
        <div className="card stack empty">
          <span className="empty-mark" aria-hidden="true">
            <Icon d={icons.budgets} className="glyph" />
          </span>
          <h2>{t('No budgets yet')}</h2>
          <p className="muted small">{t('Set a monthly limit for a category, or for all your spending, and watch it fill up.')}</p>
          <button type="button" onClick={() => setEditing('new')}>
            {t('Create a budget')}
          </button>
        </div>
      ) : (
        <ul className="list" aria-label={t('Budgets')}>
          {budgets.map((b) => {
            const c = w.category(b.category_id);
            return (
              <li key={b.id} className="card stack tight">
                <div className="row">
                  {c ? (
                    <CategoryIcon icon={c.icon} name={c.name} color={c.color} />
                  ) : (
                    <span className="cat-icon" aria-hidden="true">
                      <Icon d={icons.budgets} className="glyph" />
                    </span>
                  )}
                  <span className="grow">
                    <strong className="block">{b.name}</strong>
                    <span className="muted small">{c ? c.name : t('All spending')}</span>
                  </span>
                  <button type="button" className="link" aria-label={t('Edit budget {name}', { name: b.name })} onClick={() => setEditing(b)}>
                    {t('Edit')}
                  </button>
                </div>
                <Meter spent={budgetSpent(b, records ?? [], w.categories)} limit={b.amount} format={(n) => w.money(n)} />
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
  const [picking, setPicking] = useState(false);
  const [error, setError] = useState('');
  const catId = useId();
  const chosen = w.category(categoryId);

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(amount, exp);
    if (!minor) {
      setError(t('Enter a monthly limit.'));
      return;
    }
    const input = { name: name || w.category(categoryId)?.name || t('All spending'), category_id: categoryId, amount: minor };
    try {
      if (budget) await api.updateBudget(budget.id, input);
      else await api.createBudget(input);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!budget || !confirm(t('Delete the budget "{name}"?', { name: budget.name }))) return;
    try {
      await api.deleteBudget(budget.id);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <>
      <Dialog title={budget ? t('Edit budget') : t('New budget')} onClose={onClose}>
        <form className="stack" onSubmit={save}>
          <div className="field">
            <label htmlFor={catId}>{t('Category')}</label>
            <button id={catId} type="button" className="picker-trigger" aria-haspopup="dialog" aria-describedby={`${catId}-val`} onClick={() => setPicking(true)}>
              {chosen ? (
                <CategoryIcon icon={chosen.icon} name={chosen.name} color={chosen.color} size="small" />
              ) : (
                <span className="cat-icon small" aria-hidden="true">
                  <Icon d={icons.budgets} className="glyph" />
                </span>
              )}
              <span id={`${catId}-val`} className="ellipsis grow">
                {categoryPath(categoryId, w.categories) ?? t('All spending')}
              </span>
              <Icon d={icons.expand} className="picker-caret" />
            </button>
          </div>
          <label className="field">
            {t('Monthly limit ({currency})', { currency: w.settings.currency })}
            <input inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <label className="field">
            {t('Name (optional)')}
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
                {t('Delete')}
              </button>
            )}
            <span className="grow" />
            <button type="submit">{t('Save')}</button>
          </div>
        </form>
      </Dialog>
      {picking && (
        <CategoryPicker
          categories={w.categories}
          kind="expense"
          selected={categoryId}
          title={t('Category')}
          mainTakesInSubs
          extras={[{ label: t('All spending'), selected: categoryId === null, onPick: () => setCategoryId(null) }]}
          onPick={(c) => setCategoryId(c.id)}
          onClose={() => setPicking(false)}
        />
      )}
    </>
  );
}
