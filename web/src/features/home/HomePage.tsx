// The overview: balances, this month's spending, budgets, latest records.
import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import { withSubcategories } from '../../categories';
import { api, type Account, type Budget, type Category, type Transaction } from '../../api';
import type { BarItem } from '../../components/charts';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { addMonths, daysInMonth, monthStart } from '../../money';
import { href, openingCard } from '../../router';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { Figure } from '../../components/Figure';
import { AccountForm, accountKinds } from '../settings/SettingsPage';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { hapticSelect, hapticThreshold } from '../../native';
import { t, tx } from '../../i18n';
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
  const accounts = w.accounts.filter((a) => !a.archived);
  const total = accounts.reduce((s, a) => s + a.balance, 0);

  return (
    <section className="stack loose">
      {records !== null && budgets !== null && !w.me.demo && (
        <SetUp hasRecords={(records ?? []).length > 0} hasBudget={budgets.length > 0} />
      )}

      <section className="balance" aria-label={t('Total balance')}>
        <span className="balance-head">
          <span className="balance-label">{t('Total balance')}</span>
          <a className="icon-button" href={href('records')} aria-label={t('Search records')}>
            <Icon d={icons.search} />
          </a>
        </span>
        <span className="hero-figure">
          <Figure amount={total} animate />
        </span>
        <CardStack accounts={accounts} />
      </section>

      <section className="stack tight" aria-label={month.toLocaleDateString(locale(), { month: 'long' })}>
        <div className="section-head">
          <h2 className="section-label">{month.toLocaleDateString(locale(), { month: 'long' })}</h2>
        </div>
        {/* One line: how the month stands. Budgets, trends and where it went are on Insights. */}
        <a className="card month-row month-link" href={href('stats')}>
          <span className="row">
            <span className="grow muted small">{t('Spent this month')}</span>
            <span className="month-figure">{w.money(spent)}</span>
          </span>
          <span className="muted small">
            {spent === 0 && records !== null
              ? t('No spending recorded this month yet.')
              : headline(records === null ? null : spent, budgets, confirmed, month, w.money)}
          </span>
        </a>
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

/** A main category's budget counts its subcategories too ([categories]). */
export function budgetSpent(b: Budget, list: Transaction[], categories: Category[] = []): number {
  const ids = b.category_id === null ? null : withSubcategories(b.category_id, categories);
  return list
    .filter((t) => t.kind === 'expense' && t.status === 'confirmed' && (ids === null || (t.category_id !== null && ids.has(t.category_id))))
    .reduce((s, t) => s + t.amount, 0);
}

/**
 * The accounts as a stack of cards, the first in front with its balance in
 * full and the others showing their top edge: a name and a balance. Each
 * opens its account.
 */
function CardStack({ accounts }: { accounts: Account[] }) {
  const w = useWallet();
  // Top to bottom as drawn: the back card first, the front card (accounts[0]) last.
  const drawn = [...accounts].reverse();
  // The order just dropped, shown until the wallet comes back with it.
  const [held, setHeld] = useState<number[] | null>(null);
  const [drag, setDrag] = useState<{ id: number; from: number; dy: number } | null>(null);
  const press = useRef<{ id: number; from: number; x: number; y: number; timer: number; pointer: number } | null>(null);
  const dragged = useRef(false); // a drag just ended: the click it makes doesn't open the card
  const stack = useRef<HTMLDivElement>(null);
  const slot = useRef(-1);

  const ids = held && held.length === drawn.length && drawn.every((a) => held.includes(a.id)) ? held : drawn.map((a) => a.id);
  const byId = new Map(accounts.map((a) => [a.id, a]));
  const edge = 54;
  const last = ids.length - 1;
  const to = drag ? Math.max(0, Math.min(last, Math.round((drag.from * edge + drag.dy) / edge))) : -1;
  const order = drag ? move(ids, drag.from, to) : ids;

  // While a card is held, the finger moves it, not the page.
  useEffect(() => {
    const el = stack.current;
    if (!el) return;
    const stop = (e: TouchEvent) => {
      if (drag) e.preventDefault();
    };
    el.addEventListener('touchmove', stop, { passive: false });
    return () => el.removeEventListener('touchmove', stop);
  }, [drag]);

  useEffect(() => {
    if (held && held.join() === drawn.map((a) => a.id).join()) setHeld(null);
  }, [held, drawn]);

  if (accounts.length === 0) return null;

  function down(e: PointerEvent<HTMLAnchorElement>, id: number, from: number) {
    if (e.button !== 0 || accounts.length < 2) return;
    const p = { id, from, x: e.clientX, y: e.clientY, pointer: e.pointerId, timer: 0 };
    p.timer = window.setTimeout(() => {
      stack.current?.setPointerCapture(p.pointer);
      slot.current = from;
      setDrag({ id, from, dy: 0 });
      hapticThreshold();
    }, 400);
    press.current = p;
  }

  function moveTo(e: PointerEvent<HTMLDivElement>) {
    const p = press.current;
    if (!p) return;
    if (!drag) {
      // Moved before the hold: a scroll or a swipe, not a drag.
      if (Math.hypot(e.clientX - p.x, e.clientY - p.y) > 8) cancel();
      return;
    }
    const dy = e.clientY - p.y;
    const next = Math.max(0, Math.min(last, Math.round((drag.from * edge + dy) / edge)));
    if (next !== slot.current) {
      slot.current = next;
      hapticSelect();
    }
    setDrag({ ...drag, dy });
  }

  function up() {
    const p = press.current;
    cancel();
    if (!p || !drag) return;
    dragged.current = true;
    setTimeout(() => (dragged.current = false), 0);
    if (to === drag.from) return;
    setHeld(order);
    // Front first, as the server keeps them.
    api.setAccountOrder([...order].reverse()).then(w.refresh, () => setHeld(null));
  }

  function cancel() {
    if (press.current) clearTimeout(press.current.timer);
    press.current = null;
    setDrag(null);
  }

  return (
    <div
      ref={stack}
      className={drag ? 'card-stack arranging' : 'card-stack'}
      role="list"
      aria-label={t('Accounts')}
      style={{ height: last * edge + 150 }}
      onPointerMove={moveTo}
      onPointerUp={up}
      onPointerCancel={cancel}
    >
      {order.map((id, i) => {
        const a = byId.get(id)!;
        const isFront = i === last;
        const lifted = drag?.id === id;
        const top = lifted ? drag.from * edge + drag.dy : i * edge;
        const kind = t(accountKinds[a.kind]?.label ?? 'Other');
        return (
          <a
            key={id}
            role="listitem"
            className={`wallet-card${isFront ? ' front' : ''}${lifted ? ' lifted' : ''} ${inkOn(a.color)}`}
            style={{ top, zIndex: lifted ? ids.length + 1 : i + 1, '--c': a.color } as CSSProperties}
            data-card={id}
            href={href('account', { id })}
            draggable={false}
            onPointerDown={(e) => down(e, id, ids.indexOf(id))}
            onContextMenu={(e) => e.preventDefault()}
            onClick={(e) => {
              if (dragged.current) e.preventDefault();
              else openingCard(id, e.currentTarget.getBoundingClientRect());
            }}
          >
            <span className="wallet-card-head">
              <span className="ellipsis">{a.name}</span>
              {isFront ? (
                kind !== a.name && <span className="wallet-card-kind">{kind}</span>
              ) : (
                <span className="wallet-card-amount">{w.money(a.balance)}</span>
              )}
            </span>
            {isFront && <span className="wallet-card-big">{w.money(a.balance)}</span>}
          </a>
        );
      })}
    </div>
  );
}

/** ids with the one at from moved to to. */
function move(ids: number[], from: number, to: number): number[] {
  const out = ids.filter((_, i) => i !== from);
  out.splice(to, 0, ids[from]);
  return out;
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
