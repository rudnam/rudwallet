// The overview: balances, this month's spending, budgets, latest records.
import { useEffect, useState, type CSSProperties } from 'react';
import { api, type Account, type Budget, type Transaction } from '../../api';
import { Meter, type BarItem } from '../../components/charts';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, daysInMonth, monthStart } from '../../money';
import { href } from '../../router';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { Figure } from '../../components/Figure';
import { AccountForm, accountKinds } from '../settings/SettingsPage';
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

  return (
    <section className="stack loose">
      <header className="wallet-head">
        <span className="muted">{new Date().toLocaleDateString(locale(), { weekday: 'long', month: 'long', day: 'numeric' })}</span>
        <a className="icon-button" href={href('records')} aria-label={t('Search records')}>
          <Icon d={icons.search} />
        </a>
      </header>

      {w.review_count > 0 && (
        <a className="banner" href={href('review')}>
          <span className="attention-dot" aria-hidden="true" />
          <span className="grow">
            <strong>{tn(w.review_count, '{n} payment to review', '{n} payments to review')}</strong>
            <span className="muted small block">{t('Waiting for a quick check')}</span>
          </span>
          <span className="chev" aria-hidden="true">
            <Icon d={icons.chevron} />
          </span>
        </a>
      )}

      {records !== null && budgets !== null && !w.me.demo && (
        <SetUp hasRecords={(records ?? []).length > 0} hasBudget={budgets.length > 0} />
      )}

      <section className="balance" aria-label={t('Total balance')}>
        <span className="balance-label">{t('Total balance')}</span>
        <span className="hero-figure">
          <Figure amount={total} animate />
        </span>
        <CardStack accounts={accounts} />
      </section>

      <section className="stack tight" aria-label={month.toLocaleDateString(locale(), { month: 'long' })}>
        <div className="section-head">
          <h2 className="section-label">{month.toLocaleDateString(locale(), { month: 'long' })}</h2>
          <a href={href('stats')}>{t('Insights')}</a>
        </div>
        <div className="card flush">
          <div className="month-row">
            <span className="muted small">{t('Spent this month')}</span>
            <span className="month-figure">{w.money(spent)}</span>
            <span className="muted small">
              {spent === 0 && records !== null
                ? t('No spending recorded this month yet.')
                : headline(records === null ? null : spent, budgets, confirmed, month, w.money)}
            </span>
          </div>
          {income > 0 && (
            <div className="month-row inline">
              <span>{t('Income')}</span>
              <strong className="income">{w.money(income)}</strong>
            </div>
          )}
          {(budgets ?? []).map((b) => (
            <div key={b.id} className="month-row">
              <span>{b.name}</span>
              <Meter spent={budgetSpent(b, confirmed)} limit={b.amount} format={(n) => w.money(n)} />
            </div>
          ))}
        </div>
      </section>

      <div className="stack">
        <div className="section-head">
          <h2 className="section-label">{t('Latest records')}</h2>
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
          <TransactionList records={records.slice(0, 5)} onOpen={setOpen} label={t('Latest records')} />
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

/**
 * The line at the top of Home: what the month means for the user today, from
 * their overall budget if they have one (a budget with no category), else
 * what they've spent so far. A greeting only before there's anything to say.
 */
function headline(
  spent: number | null,
  budgets: Budget[] | null,
  confirmed: Transaction[],
  month: Date,
  money: (n: number) => string,
): string {
  if (spent === null || budgets === null) return greeting();
  const overall = budgets.find((b) => b.category_id === null);
  if (overall) {
    const used = budgetSpent(overall, confirmed);
    if (used > overall.amount) return t('{amount} over budget this month', { amount: money(used - overall.amount) });
    // Days left, today included, as Stats counts them.
    const left = daysInMonth(month) - new Date().getDate() + 1;
    return t('{amount} a day keeps you within budget', { amount: money(Math.floor((overall.amount - used) / left)) });
  }
  if (spent > 0) {
    return t('{amount} spent so far in {month}', {
      amount: money(spent),
      month: month.toLocaleDateString(locale(), { month: 'long' }),
    });
  }
  return greeting();
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

/**
 * The accounts as a stack of cards, the first in front with its balance in
 * full and the others showing their top edge: a name and a balance. Each
 * opens its account.
 */
function CardStack({ accounts }: { accounts: Account[] }) {
  const w = useWallet();
  if (accounts.length === 0) return null;
  const [front, ...rest] = accounts;
  // Back to front: the last drawn is on top.
  const order = [...rest].reverse();
  const edge = 54;
  return (
    <div className="card-stack" role="list" aria-label={t('Accounts')} style={{ height: order.length * edge + 200 }}>
      {order.map((a, i) => (
        <a
          key={a.id}
          role="listitem"
          className={`wallet-card ${inkOn(a.color)}`}
          style={{ top: i * edge, '--c': a.color } as CSSProperties}
          href={href('account', { id: a.id })}
        >
          <span className="wallet-card-head">
            <span className="ellipsis">{a.name}</span>
            <span className="wallet-card-amount">{w.money(a.balance)}</span>
          </span>
        </a>
      ))}
      <a
        role="listitem"
        className={`wallet-card front ${inkOn(front.color)}`}
        style={{ top: order.length * edge, '--c': front.color } as CSSProperties}
        href={href('account', { id: front.id })}
      >
        <span className="wallet-card-head">
          <span className="ellipsis">{front.name}</span>
          {t(accountKinds[front.kind]?.label ?? 'Other') !== front.name && (
            <span className="wallet-card-kind">{t(accountKinds[front.kind]?.label ?? 'Other')}</span>
          )}
        </span>
        <span className="wallet-card-big">{w.money(front.balance)}</span>
      </a>
    </div>
  );
}

/** Ink for text on a card's colour: light on dark colours, dark on light ones. */
function inkOn(color: string): 'ink-light' | 'ink-dark' {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color);
  if (!m) return 'ink-light';
  const [r, g, b] = m.slice(1).map((h) => {
    const c = parseInt(h, 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  const l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  // White unless the colour is too light for it (3:1 is enough at this size).
  return 1.05 / (l + 0.05) >= 3 ? 'ink-light' : 'ink-dark';
}
