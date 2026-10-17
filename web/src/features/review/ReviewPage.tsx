// Payments read from the phone's notifications, waiting for the user:
// confirm them one by one or all at once (which teaches the app the shop's
// category and the card's account), or fix them.
import { useState } from 'react';
import { api, errorMessage, type Transaction } from '../../api';
import { TransactionForm } from '../../components/TransactionForm';
import { formatDay, formatTime } from '../../money';
import { href } from '../../router';
import { useRecords } from '../../useRecords';
import { Icon, icons } from '../../components/Icon';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { t, t as tr, tn, tx } from '../../i18n';

export function ReviewPage() {
  const w = useWallet();
  const { records, reload } = useRecords({ status: 'review' });
  const [open, setOpen] = useState<Transaction | null>(null);
  const [error, setError] = useState('');
  // Categories picked here but not confirmed yet, by record.
  const [picked, setPicked] = useState<Record<number, number | null>>({});
  const [confirmingAll, setConfirmingAll] = useState(false);
  const toast = useToast();

  const categoryOf = (t: Transaction) => (t.id in picked ? picked[t.id] : t.category_id);
  // What "Confirm all" confirms: what the server's bulk confirm accepts.
  const ready = (records ?? []).filter((t) => t.amount > 0 && !t.duplicate_of_id && t.kind !== 'transfer' && categoryOf(t) !== null);

  async function confirmAll() {
    setConfirmingAll(true);
    setError('');
    try {
      // Categories picked here first, one request per category.
      const changed = new Map<number, number[]>();
      for (const t of ready) {
        const c = categoryOf(t)!;
        if (c !== t.category_id) changed.set(c, [...(changed.get(c) ?? []), t.id]);
      }
      for (const [category_id, ids] of changed) await api.bulkTransactions({ ids, action: 'categorize', category_id });
      const { changed: n } = await api.bulkTransactions({ ids: ready.map((t) => t.id), action: 'confirm' });
      toast(tn(n, 'Confirmed {n} payment', 'Confirmed {n} payments'));
      setPicked({});
      await Promise.all([reload(), w.refresh()]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setConfirmingAll(false);
    }
  }

  const empty = records !== null && records.length === 0;
  return (
    <section className="stack loose">
      <header className="stack tight">
        <h1>{t('To review')}</h1>
        <p className="muted lede">
          {t("Payments read from your phone's notifications. Pick a category and confirm; next time it's remembered.")}
        </p>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {empty && (
        <div className="card stack empty">
          <span className="empty-mark" aria-hidden="true">
            <Icon d={icons.check} className="glyph" />
          </span>
          <h2>{t('All caught up.')}</h2>
          <p className="muted small">
            {tx('Payments from PayPay and Google Wallet appear here. Set up {notifications}.', {
              notifications: <a href={href('settings', { section: 'notifications' })}>{t('payment notifications')}</a>,
            })}
          </p>
        </div>
      )}

      {ready.length > 1 && (
        <div className="row wrap confirm-all">
          <span className="grow muted small">
            {ready.length === records?.length
              ? t('Every payment here has a category.')
              : t('{ready} of {total} payments have a category. The rest stay for you.', { ready: ready.length, total: records?.length ?? 0 })}
          </span>
          <button type="button" disabled={confirmingAll} onClick={confirmAll}>
            {t('Confirm {n} with a category', { n: ready.length })}
          </button>
        </div>
      )}

      {records && records.length > 0 && (
        <ul className="list" aria-label={t('Payments to review')}>
          {records.map((t) => (
            <ReviewItem
              key={t.id}
              t={t}
              categoryId={categoryOf(t)}
              onPick={(id) => setPicked((p) => ({ ...p, [t.id]: id }))}
              onEdit={() => setOpen(t)}
              onDone={reload}
            />
          ))}
        </ul>
      )}

      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
    </section>
  );
}

/**
 * One payment: pick a category and confirm in place, or open it to edit.
 * Changing a category the shop's rule filled in asks whether to keep the new
 * one for the shop, so a one-off doesn't change every later payment there.
 */
function ReviewItem({
  t,
  categoryId,
  onPick,
  onEdit,
  onDone,
}: {
  t: Transaction;
  categoryId: number | null;
  onPick: (id: number | null) => void;
  onEdit: () => void;
  onDone: () => void;
}) {
  const w = useWallet();
  const toast = useToast();
  const [remember, setRemember] = useState(false);
  const [error, setError] = useState('');
  const overriding = t.payee !== '' && t.category_id !== null && categoryId !== null && categoryId !== t.category_id;
  const [busy, setBusy] = useState(false);
  const when = new Date(t.occurred_at);
  const title = t.payee || tr('Payment');

  async function confirm() {
    if (t.amount <= 0) {
      onEdit();
      return;
    }
    setBusy(true);
    try {
      await api.updateTransaction(t.id, {
        kind: t.kind,
        amount: t.amount,
        account_id: t.account_id,
        to_account_id: null,
        category_id: categoryId,
        occurred_at: t.occurred_at,
        payee: t.payee,
        note: t.note,
        status: 'confirmed',
        ...(overriding ? { remember } : {}),
      });
      await w.refresh();
      toast(tr('Confirmed {payee}', { payee: title }));
      onDone();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <li className="card stack tight review-item" aria-label={title}>
      <div className="row">
        <span className="grow">
          <strong>{title}</strong>
          <span className="muted small block">
            {formatDay(when)} {formatTime(when)} · {w.account(t.account_id)?.name}
            {/* The card, unless the account is already called that. */}
            {t.source_label && t.source_label !== w.account(t.account_id)?.name ? ` · ${t.source_label}` : ''}
          </span>
        </span>
        <strong className={`amount review-amount ${t.kind}`}>
          {t.amount > 0 ? w.money(t.kind === 'income' ? t.amount : -t.amount, true) : tr('Amount?')}
        </strong>
      </div>
      {t.note && <span className="small muted">{t.note}</span>}
      {t.duplicate_of_id && (
        <span className="notice warn small" role="status">
          <Icon d={icons.warning} className="inline-icon" /> {tr('Possible duplicate: you already have a record of this amount around this time.')}
        </span>
      )}
      <div className="row wrap">
        <select
          aria-label={tr('Category for {name}', { name: title })}
          value={categoryId ?? ''}
          onChange={(e) => onPick(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">{tr('No category')}</option>
          {w.categories
            .filter((c) => c.kind === t.kind)
            .map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
        </select>
        <button type="button" disabled={busy} onClick={confirm}>
          {tr('Confirm')}
        </button>
        <button type="button" className="link" onClick={onEdit}>
          {tr('Edit')}
        </button>
      </div>
      {overriding && (
        <label className="check small">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
          {tr('Use {category} for {payee} from now on, instead of {old}', {
            category: w.category(categoryId)?.name ?? '',
            payee: t.payee,
            old: w.category(t.category_id)?.name ?? '',
          })}
        </label>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </li>
  );
}

