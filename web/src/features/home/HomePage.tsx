// The overview: balances, this month's spending, budgets, latest records.
import { useEffect, useState } from 'react';
import { api, type Budget, type Transaction } from '../../api';
import { CategoryBars, DailyColumns, Meter, type BarItem } from '../../components/charts';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, daysInMonth, formatCompact, monthPace, monthStart } from '../../money';
import { href, navigate } from '../../router';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { AccountForm } from '../settings/SettingsPage';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { t, tn, tx } from '../../i18n';
import { locale } from '../../language';

export function HomePage({ onAdd }: { onAdd: () => void }) {
  const w = useWallet();
  const [month] = useState(() => monthStart(new Date()));
  const { records, reload } = useRecords({ from: month, to: addMonths(month, 1) });
  const [budgets, setBudgets] = useState<Budget[] | null>(() => api.seen.budgets() ?? null);
  const [open, setOpen] = useState<Transaction | null>(null);

  useEffect(() => {
    api.budgets().then(setBudgets, () => setBudgets([]));
  }, []);

  const confirmed = (records ?? []).filter((t) => t.status === 'confirmed');
  const spent = sum(confirmed.filter((t) => t.kind === 'expense'));
  const income = sum(confirmed.filter((t) => t.kind === 'income'));
  const accounts = w.accounts.filter((a) => !a.archived);
  const total = accounts.reduce((s, a) => s + a.balance, 0);
  const byCategory = spendingByCategory(confirmed, w);
  const pace = spent > 0 ? monthPace(spent, month) : null;
  const daily = new Array<number>(daysInMonth(month)).fill(0);
  for (const t of confirmed) if (t.kind === 'expense') daily[new Date(t.occurred_at).getDate() - 1] += t.amount;

  return (
    <section className="stack loose">
      <header className="page-head">
        <div>
          <span className="eyebrow">
            {new Date().toLocaleDateString(locale(), { weekday: 'long', month: 'long', day: 'numeric' })}
          </span>
          <h1>{greeting()}</h1>
        </div>
      </header>

      {w.review_count > 0 && (
        <a className="banner" href={href('review')}>
          <span className="banner-icon" aria-hidden="true">
            <Icon d={icons.review} />
          </span>
          <span className="grow">
            <strong>{tn(w.review_count, '{n} payment to review', '{n} payments to review')}</strong>
            <span className="muted small block">{t('Waiting for a quick check')}</span>
          </span>
          <span className="chev" aria-hidden="true">
            ›
          </span>
        </a>
      )}

      {records !== null && budgets !== null && !w.me.demo && (
        <SetUp hasRecords={(records ?? []).length > 0} hasBudget={budgets.length > 0} />
      )}

      <div className="card hero">
        <span className="muted">{t('Total balance')}</span>
        <span className="hero-figure">{w.money(total)}</span>
        <div className="tiles">
          <Tile
            label={t('Spent this month')}
            value={w.money(spent)}
            hint={pace !== null ? t('On pace for {amount}', { amount: formatCompact(pace, w.settings) }) : undefined}
          />
          <Tile label={t('Income')} value={w.money(income)} className="income" />
          <Tile label={t('Net')} value={w.money(income - spent, true)} />
        </div>
      </div>

      <div className="accounts" aria-label={t('Accounts')} role="list">
        {accounts.map((a) => (
          <a key={a.id} role="listitem" className="card account-card" href={href('records', { account: a.id })}>
            <span className="row">
              <span className="swatch" style={{ background: a.color }} aria-hidden="true" />
              <span className="muted small ellipsis">{a.name}</span>
            </span>
            <strong>{w.money(a.balance)}</strong>
          </a>
        ))}
      </div>

      <div className="card stack">
        <div className="section-head">
          <h2>{t('Where it went in {month}', { month: month.toLocaleDateString(locale(), { month: 'long' }) })}</h2>
          <a href={href('stats')}>{t('More stats')}</a>
        </div>
        {byCategory.length === 0 ? (
          <p className="muted">{t('No spending recorded this month yet.')}</p>
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
                new Date(month.getFullYear(), month.getMonth(), i + 1).toLocaleDateString(locale(), {
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

      {budgets && budgets.length > 0 && (
        <div className="card stack">
          <div className="section-head">
            <h2>{t('Budgets')}</h2>
            <a href={href('budgets')}>{t('All budgets')}</a>
          </div>
          {budgets.map((b) => (
            <div key={b.id} className="stack tight">
              <span>{b.name}</span>
              <Meter
                spent={budgetSpent(b, confirmed)}
                limit={b.amount}
                pace={monthPace(budgetSpent(b, confirmed), month)}
                format={(n) => w.money(n)}
              />
            </div>
          ))}
        </div>
      )}

      <div className="stack">
        <div className="section-head">
          <h2>{t('Latest records')}</h2>
          <a href={href('records')}>{t('All records')}</a>
        </div>
        {records === null ? (
          <p className="muted">{t('Loading…')}</p>
        ) : records.length === 0 ? (
          <div className="card stack empty">
            <p className="muted">{t('Nothing recorded this month yet.')}</p>
            <button type="button" className="secondary" onClick={onAdd}>
              {t('Add a record')}
            </button>
          </div>
        ) : (
          <TransactionList records={records.slice(0, 6)} onOpen={setOpen} label={t('Latest records')} />
        )}
      </div>

      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
    </section>
  );
}

function greeting(): string {
  const h = new Date().getHours();
  return h < 5 ? t('Good evening') : h < 12 ? t('Good morning') : h < 18 ? t('Good afternoon') : t('Good evening');
}

const setupDoneKey = 'wallet:setup-dismissed';

/**
 * The first things a new user does, ticked off as they go: their real
 * accounts, payment notifications and a budget. Hidden once done or dismissed.
 */
function SetUp({ hasRecords, hasBudget }: { hasRecords: boolean; hasBudget: boolean }) {
  const w = useWallet();
  const [hidden, setHidden] = useState(() => localStorage.getItem(setupDoneKey) === '1');
  // Done once the phone has sent a payment app's notification.
  const [notified, setNotified] = useState<boolean | null>(null);
  const [addingAccount, setAddingAccount] = useState(false);

  useEffect(() => {
    if (!hidden) api.notifications().then((r) => setNotified(r.notifications.length > 0), () => setNotified(false));
  }, [hidden]);

  const steps = [
    {
      done: w.accounts.length > 1,
      title: t('Add your accounts'),
      detail: t('Your bank, cards and e-money, with what’s in them today.'),
      action: (
        <button type="button" className="link" onClick={() => setAddingAccount(true)}>
          {t('Add account')}
        </button>
      ),
    },
    {
      done: !!notified,
      title: t('Turn on payment notifications'),
      detail: t('PayPay and Google Wallet payments become records by themselves.'),
      action: <a href={href('settings', { section: 'notifications' })}>{t('Set up')}</a>,
    },
    {
      done: hasBudget,
      title: t('Set a monthly budget'),
      detail: t('See at a glance how much is left.'),
      action: <a href={href('budgets')}>{t('Add budget')}</a>,
    },
  ];
  const left = steps.filter((s) => !s.done).length;
  if (hidden || notified === null || left === 0) return null;

  return (
    <div className="card stack setup" aria-label={t('Get set up')}>
      <div className="section-head">
        <h2>{t('Get set up')}</h2>
        <span className="muted small">{t('{done} of {total}', { done: steps.length - left, total: steps.length })}</span>
        <button
          type="button"
          className="link"
          onClick={() => {
            localStorage.setItem(setupDoneKey, '1');
            setHidden(true);
          }}
        >
          {t('Hide')}
        </button>
      </div>
      <ol className="setup-steps">
        {steps.map((s) => (
          <li key={s.title} className={s.done ? 'done' : ''}>
            <span className="setup-mark" aria-hidden="true">
              {s.done && <Icon d={icons.check} className="inline-icon" />}
            </span>
            <span className="grow">
              <strong>{s.title}</strong>
              {s.done && <span className="sr-only"> {t('(done)')}</span>}
              <span className="muted small block">{s.detail}</span>
            </span>
            {!s.done && s.action}
          </li>
        ))}
      </ol>
      {!hasRecords && (
        <p className="muted small">
          {tx('Amounts are in {currency}. {change} before your first record. Coming from BudgetBakers? {import}.', {
            currency: w.settings.currency,
            change: <a href={href('settings', { section: 'general' })}>{t('Change currency')}</a>,
            import: <a href={href('settings', { section: 'import' })}>{t('Import your history')}</a>,
          })}
        </p>
      )}
      {addingAccount && <AccountForm onClose={() => setAddingAccount(false)} />}
    </div>
  );
}

function Tile({ label, value, hint, className }: { label: string; value: string; hint?: string; className?: string }) {
  return (
    <div className="tile">
      <span className="muted small">{label}</span>
      <strong className={className}>{value}</strong>
      {hint && <span className="muted small tile-hint">{hint}</span>}
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
      return {
        key: k,
        label: c?.name ?? t('No category'),
        icon: <CategoryIcon icon={c?.icon} name={c?.name} color={c?.color} size="small" />,
        amount,
      };
    })
    .sort((a, b) => b.amount - a.amount);
}

export function budgetSpent(b: Budget, list: Transaction[]): number {
  return list
    .filter((t) => t.kind === 'expense' && t.status === 'confirmed' && (b.category_id === null || t.category_id === b.category_id))
    .reduce((s, t) => s + t.amount, 0);
}
