// Payments read from the phone's notifications, waiting for the user:
// confirm them one by one or all at once (which teaches the app the shop's
// category and the card's account), or fix them. A possible duplicate shows
// the record it matches, and can be deleted in one tap.
import { useEffect, useState } from 'react';
import { api, errorMessage, type Transaction } from '../../api';
import { TransactionForm } from '../../components/TransactionForm';
import { formatDay, formatTime } from '../../money';
import { href } from '../../router';
import { useRecords } from '../../useRecords';
import { Icon, icons } from '../../components/Icon';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { hapticSuccess } from '../../native';
import { t, t as tr, tn, tx } from '../../i18n';

/**
 * The payments waiting for a check. On its own (compact unset) it's a page;
 * in Activity (compact) it's the "N to check" section above the records,
 * and nothing at all when nothing is waiting.
 */
export function ReviewPage({ compact = false }: { compact?: boolean }) {
  const w = useWallet();
  const { records, reload } = useRecords({ status: 'review' });
  const [open, setOpen] = useState<Transaction | null>(null);
  const [error, setError] = useState('');
  // Categories picked here but not confirmed yet, by record.
  const [picked, setPicked] = useState<Record<number, number | null>>({});
  const [confirmingAll, setConfirmingAll] = useState(false);
  const toast = useToast();
  // Records that possible duplicates match, by ID.
  const [matches, setMatches] = useState<Map<number, Transaction>>(new Map());

  // A duplicate is flagged when it's within a day of the record it matches:
  // fetch the days around the flagged ones, with a day to spare in case the
  // match was moved since.
  useEffect(() => {
    const times = (records ?? []).filter((t) => t.duplicate_of_id).map((t) => new Date(t.occurred_at).getTime());
    if (times.length === 0) return;
    const day = 86_400_000;
    api.transactions({ from: new Date(Math.min(...times) - 2 * day), to: new Date(Math.max(...times) + 2 * day) }).then(
      (list) => setMatches(new Map(list.map((t) => [t.id, t]))),
      () => {},
    );
  }, [records]);

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
      hapticSuccess();
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
  if (compact && (records === null || empty)) return null;
  return (
    <section className={compact ? 'stack to-check' : 'stack loose'} aria-label={compact ? t('Payments to review') : undefined}>
      {compact ? (
        <h2 className="to-check-head">
          <span className="attention-dot" aria-hidden="true" />
          {t('{n} to check', { n: records?.length ?? 0 })}
        </h2>
      ) : (
        <header className="stack tight">
          <h1>{t('To review')}</h1>
          <p className="muted lede">
            {t("Payments read from your phone's notifications. Pick a category and confirm; next time it's remembered.")}
          </p>
        </header>
      )}
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
        <ul className="list" aria-label={compact ? undefined : t('Payments to review')}>
          {records.map((t) => (
            <ReviewItem
              key={t.id}
              t={t}
              categoryId={categoryOf(t)}
              match={t.duplicate_of_id ? matches.get(t.duplicate_of_id) : undefined}
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
 * One payment: pick a category and confirm in place, or open it to edit. A
 * possible duplicate shows the record it matches and can be deleted here.
 * Changing a category the shop's rule filled in asks whether to keep the new
 * one for the shop, so a one-off doesn't change every later payment there.
 */
function ReviewItem({
  t,
  categoryId,
  match,
  onPick,
  onEdit,
  onDone,
}: {
  t: Transaction;
  categoryId: number | null;
  /** The record this may duplicate, once it's loaded. */
  match?: Transaction;
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
      hapticSuccess();
      toast(tr('Confirmed {payee}', { payee: title }));
      onDone();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  // No question first: the toast's Undo brings it back.
  async function remove() {
    setBusy(true);
    try {
      await api.deleteTransaction(t.id);
      await w.refresh();
      toast(tr('Deleted {payee}', { payee: title }), { label: tr('Undo'), run: restore });
      onDone();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  async function restore() {
    try {
      await api.restoreTransactions([t.id]);
      await w.refresh();
      toast(tr('Record restored'));
      onDone();
    } catch (err) {
      toast(tr("Couldn't restore: {error}", { error: errorMessage(err) }));
    }
  }

  const matchedAt = match && new Date(match.occurred_at);
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
        <div className="notice warn small duplicate" role="status">
          <span className="grow">
            <Icon d={icons.warning} className="inline-icon" />{' '}
            {match && matchedAt ? (
              <>
                {tr('Possible duplicate of a record you already have:')}
                <span className="block">
                  <strong>{match.payee || tr('Payment')}</strong> · {w.money(match.kind === 'income' ? match.amount : -match.amount, true)} ·{' '}
                  {formatDay(matchedAt)} {formatTime(matchedAt)} · {w.account(match.account_id)?.name}
                </span>
              </>
            ) : (
              tr('Possible duplicate: you already have a record of this amount around this time.')
            )}
          </span>
          <button type="button" className="secondary" disabled={busy} onClick={remove}>
            {tr('Delete duplicate')}
          </button>
        </div>
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

