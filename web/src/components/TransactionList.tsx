// Records grouped by day, newest first.
import type { Transaction } from '../api';
import { dayKey, formatDay, formatTime } from '../money';
import { useWallet } from '../wallet';
import { CategoryIcon, Icon, icons } from './Icon';

export function TransactionList({
  records,
  onOpen,
  label = 'Records',
  selected,
  onToggle,
}: {
  records: Transaction[];
  onOpen: (t: Transaction) => void;
  label?: string;
  /** While choosing records: the chosen IDs. Rows become checkboxes. */
  selected?: Set<number>;
  onToggle?: (t: Transaction) => void;
}) {
  const w = useWallet();
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
              <span>{w.money(net, true)}</span>
            </div>
            <ul className="list card flush">
              {day.items.map((t) => (
                <Row key={t.id} t={t} onOpen={onOpen} selected={selected?.has(t.id)} onToggle={selected && onToggle} />
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
}: {
  t: Transaction;
  onOpen: (t: Transaction) => void;
  selected?: boolean;
  onToggle?: (t: Transaction) => void;
}) {
  const w = useWallet();
  const cat = w.category(t.category_id);
  const account = w.account(t.account_id);
  const title =
    t.kind === 'transfer'
      ? `Transfer to ${w.account(t.to_account_id)?.name ?? '?'}`
      : t.payee || cat?.name || (t.kind === 'income' ? 'Income' : 'Expense');
  const sub = [
    t.kind === 'transfer' ? `from ${account?.name ?? '?'}` : (cat?.name ?? 'No category'),
    t.kind !== 'transfer' && account?.name,
    formatTime(new Date(t.occurred_at)),
  ]
    .filter(Boolean)
    .join(' · ');
  const amount =
    t.kind === 'transfer' ? w.money(t.amount) : w.money(t.kind === 'income' ? t.amount : -t.amount, true);

  const content = (
    <>
        {t.kind === 'transfer' ? (
          <span className="cat-icon" aria-hidden="true">
            <Icon d={icons.transfer} className="glyph" />
          </span>
        ) : (
          <CategoryIcon icon={cat?.icon} name={cat?.name} color={cat?.color} />
        )}
        <span className="grow">
          <span className="record-title">
            {title}
            {t.status === 'review' && <span className="badge">To review</span>}
            {t.source === 'email' && t.status === 'confirmed' && (
              <span className="badge quiet" title="Recorded from an email">
                <Icon d={icons.mail} className="inline-icon" /> Email
              </span>
            )}
            {t.source === 'notification' && t.status === 'confirmed' && (
              <span className="badge quiet" title="Recorded from a payment notification">
                Phone
              </span>
            )}
          </span>
          <span className="muted small">{sub}</span>
        </span>
        <span className={`amount ${t.kind}`}>{t.amount === 0 && t.status === 'review' ? 'Amount?' : amount}</span>
    </>
  );

  return (
    <li>
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
