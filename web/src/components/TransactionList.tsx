// Records grouped by day, newest first.
import type { Transaction } from '../api';
import { dayKey, formatDay, formatTime } from '../money';
import { useWallet } from '../wallet';

export function TransactionList({
  records,
  onOpen,
  label = 'Records',
}: {
  records: Transaction[];
  onOpen: (t: Transaction) => void;
  label?: string;
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
    <div className="stack tight" aria-label={label} role="list">
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
                <Row key={t.id} t={t} onOpen={onOpen} />
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

function Row({ t, onOpen }: { t: Transaction; onOpen: (t: Transaction) => void }) {
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

  return (
    <li>
      <button type="button" className="record" onClick={() => onOpen(t)} aria-label={`${title}, ${amount}`}>
        <span className="cat-icon" aria-hidden="true">
          {t.kind === 'transfer' ? '⇄' : (cat?.icon ?? '•')}
        </span>
        <span className="grow">
          <span className="record-title">
            {title}
            {t.status === 'review' && <span className="badge">To review</span>}
            {t.source === 'email' && t.status === 'confirmed' && (
              <span className="badge quiet" title="Recorded from an email">
                ✉ auto
              </span>
            )}
          </span>
          <span className="muted small">{sub}</span>
        </span>
        <span className={`amount ${t.kind}`}>{t.amount === 0 && t.status === 'review' ? 'Amount?' : amount}</span>
      </button>
    </li>
  );
}
