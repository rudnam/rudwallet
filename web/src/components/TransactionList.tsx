// Records grouped by day, newest first.
import { useEffect, useRef } from 'react';
import type { Transaction } from '../api';
import { t as tr } from '../i18n';
import { dayKey, formatDay, formatTime } from '../money';
import { useWallet } from '../wallet';
import { CategoryIcon, Icon, icons } from './Icon';

export function TransactionList({
  records,
  onOpen,
  label = tr('Records'),
  selected,
  onToggle,
  detail,
}: {
  records: Transaction[];
  onOpen: (t: Transaction) => void;
  label?: string;
  /** While choosing records: the chosen IDs. Rows become checkboxes. */
  selected?: Set<number>;
  onToggle?: (t: Transaction) => void;
  /** Under each amount: the account (the default), or the time on an account's own page. */
  detail?: 'account' | 'time';
}) {
  const w = useWallet();
  // Records this list didn't show last time slide in; on first draw, none do.
  const shown = useRef<Set<number> | null>(null);
  const fresh = (id: number) => shown.current !== null && !shown.current.has(id);
  useEffect(() => {
    shown.current = new Set(records.map((t) => t.id));
  }, [records]);
  const days: { key: string; date: Date; items: Transaction[] }[] = [];
  for (const t of records) {
    const date = new Date(t.occurred_at);
    const key = dayKey(date);
    const last = days[days.length - 1];
    if (last?.key === key) last.items.push(t);
    else days.push({ key, date, items: [t] });
  }

  return (
    <div className="stack days" aria-label={label} role="list">
      {days.map((day) => {
        const net = day.items.reduce((sum, t) => sum + signed(t), 0);
        return (
          <section key={day.key} className="day" role="listitem" aria-label={formatDay(day.date)}>
            <div className="day-head">
              <span>{formatDay(day.date)}</span>
              {/* Nothing confirmed yet that day: no total, rather than ¥0. */}
              {net !== 0 && <span>{w.money(net, true)}</span>}
            </div>
            <ul className="list card flush">
              {day.items.map((t) => (
                <Row key={t.id} t={t} onOpen={onOpen} selected={selected?.has(t.id)} onToggle={selected && onToggle} fresh={fresh(t.id)} detail={detail} />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

/** The record's effect on the user's total: transfers move money, not change it. */
export function signed(t: Transaction): number {
  if (t.status !== 'confirmed' || t.kind === 'transfer') return 0;
  return t.kind === 'income' ? t.amount : -t.amount;
}

function Row({
  t,
  onOpen,
  selected,
  onToggle,
  fresh,
  detail = 'account',
}: {
  t: Transaction;
  onOpen: (t: Transaction) => void;
  selected?: boolean;
  onToggle?: (t: Transaction) => void;
  /** Just arrived: it slides in. */
  fresh?: boolean;
  detail?: 'account' | 'time';
}) {
  const w = useWallet();
  const cat = w.category(t.category_id);
  const account = w.account(t.account_id);
  const title =
    t.kind === 'transfer'
      ? tr('Transfer to {account}', { account: w.account(t.to_account_id)?.name ?? '?' })
      : t.payee || cat?.name || (t.kind === 'income' ? tr('Income') : tr('Expense'));
  // Under the name, what it was; under the amount, where it was paid from
  // (or, on an account's own page, when).
  const waiting = t.status === 'review' && t.kind !== 'transfer' && !cat;
  const sub =
    t.kind === 'transfer'
      ? tr('from {account}', { account: account?.name ?? '?' })
      : waiting
        ? tr('Choose a category')
        : (cat?.name ?? tr('No category'));
  const side = detail === 'time' ? formatTime(new Date(t.occurred_at)) : account?.name;
  const amount =
    t.kind === 'transfer' ? w.money(t.amount) : w.money(t.kind === 'income' ? t.amount : -t.amount, true);

  const content = (
    <>
        {t.kind === 'transfer' ? (
          <span className="cat-icon" aria-hidden="true">
            <Icon d={icons.transfer} className="glyph" />
          </span>
        ) : (
          <CategoryIcon icon={cat?.icon} name={cat?.name} color={cat?.color} payee={t.payee} />
        )}
        <span className="grow">
          <span className="record-title">
            {title}
            {t.unsent &&
              (t.unsent.error ? (
                <span className="badge refused">{tr('Couldn’t send')}</span>
              ) : (
                <span className="badge quiet" title={tr('Saved on this phone; it’s sent when you’re online.')}>
                  {tr('Not sent yet')}
                </span>
              ))}
          </span>
          <span className={waiting ? 'small attention' : 'muted small'}>{sub}</span>
          {t.tags?.length ? (
            <span className="record-tags" aria-label={tr('Tags')}>
              {t.tags.map((tag) => (
                <span key={tag} className="tag-label">
                  #{tag}
                </span>
              ))}
            </span>
          ) : null}
        </span>
        <span className="record-side">
          <span className={`amount ${t.kind}`}>{t.amount === 0 && t.status === 'review' ? tr('Amount?') : amount}</span>
          {side && <span className="muted small">{side}</span>}
        </span>
    </>
  );

  return (
    <li className={fresh ? 'record-new' : undefined}>
      {onToggle ? (
        <label className={selected ? 'record selectable on' : 'record selectable'}>
          <input type="checkbox" checked={!!selected} onChange={() => onToggle(t)} aria-label={`${title}, ${amount}`} />
          {content}
        </label>
      ) : (
        <button type="button" className="record" onClick={() => onOpen(t)} aria-label={`${title}, ${amount}`}>
          {content}
        </button>
      )}
    </li>
  );
}
