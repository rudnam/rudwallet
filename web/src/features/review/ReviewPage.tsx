// Payments read from emails and phone notifications, waiting for the user:
// confirm them one by one or all at once (which teaches the app the shop's
// category and the card's account), fix them, or handle emails it couldn't
// read.
import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, type InboundEmail, type Transaction } from '../../api';
import { TransactionForm } from '../../components/TransactionForm';
import { formatDay, formatTime } from '../../money';
import { href } from '../../router';
import { useRecords } from '../../useRecords';
import { Icon, icons } from '../../components/Icon';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';

export function ReviewPage() {
  const w = useWallet();
  const { records, reload } = useRecords({ status: 'review' });
  const [emails, setEmails] = useState<InboundEmail[]>(() => needsUser(api.seen.inbox()?.emails ?? []));
  const [open, setOpen] = useState<Transaction | null>(null);
  const [addFrom, setAddFrom] = useState<InboundEmail | null>(null);
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
      toast(n === 1 ? 'Confirmed 1 payment' : `Confirmed ${n} payments`);
      setPicked({});
      await Promise.all([reload(), w.refresh()]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setConfirmingAll(false);
    }
  }

  const loadEmails = useCallback(() => {
    api.inbox().then(
      (i) => setEmails(needsUser(i.emails)),
      (err) => setError(errorMessage(err)),
    );
  }, []);
  useEffect(loadEmails, [loadEmails]);

  async function dismiss(e: InboundEmail) {
    try {
      await api.dismissEmail(e.id);
      loadEmails();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const empty = records !== null && records.length === 0 && emails.length === 0;
  return (
    <section className="stack loose">
      <header className="stack tight">
        <h1>To review</h1>
        <p className="muted lede">
          Payments read from your emails and your phone's notifications. Pick a category and confirm; next time it's remembered.
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
          <h2>All caught up.</h2>
          <p className="muted small">
            Payments from forwarded emails and Google Wallet appear here. Set up{' '}
            <a href={href('settings', { section: 'email' })}>email automation</a> or{' '}
            <a href={href('settings', { section: 'notifications' })}>payment notifications</a>.
          </p>
        </div>
      )}

      {emails
        .filter((e) => e.status === 'forwarding')
        .map((e) => (
          <div key={e.id} className="card stack notice-card">
            <strong>Confirm Gmail forwarding</strong>
            <p className="small">
              Gmail sent a confirmation to your forwarding address. Open the link to allow forwarding
              {e.detail ? (
                <>
                  , or enter code <strong>{e.detail}</strong> in Gmail's settings
                </>
              ) : null}
              .
            </p>
            <div className="row">
              {e.confirm_url && (
                <a className="button" href={e.confirm_url} target="_blank" rel="noreferrer noopener">
                  Confirm forwarding
                </a>
              )}
              <button type="button" className="link" onClick={() => dismiss(e)}>
                Done
              </button>
            </div>
          </div>
        ))}

      {ready.length > 1 && (
        <div className="row wrap confirm-all">
          <span className="grow muted small">
            {ready.length === records?.length
              ? 'Every payment here has a category.'
              : `${ready.length} of ${records?.length} payments have a category. The rest stay for you.`}
          </span>
          <button type="button" disabled={confirmingAll} onClick={confirmAll}>
            Confirm {ready.length} with a category
          </button>
        </div>
      )}

      {records && records.length > 0 && (
        <ul className="list" aria-label="Payments to review">
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

      {emails.filter((e) => e.status === 'unparsed').length > 0 && (
        <>
          <h2>Emails that need you</h2>
          <p className="muted small lede">
            These couldn't be read automatically. Add them yourself, or set them aside.
          </p>
          <ul className="list" aria-label="Unread emails">
            {emails
              .filter((e) => e.status === 'unparsed')
              .map((e) => (
                <li key={e.id} className="card stack tight">
                  <strong>{e.subject || '(no subject)'}</strong>
                  <span className="muted small">
                    {e.from} · {formatDay(new Date(e.received_at))}
                  </span>
                  <span className="small">{e.detail}</span>
                  <details>
                    <summary className="small">Show email</summary>
                    <pre className="email-body">{e.body_text}</pre>
                  </details>
                  <div className="row">
                    <button type="button" onClick={() => setAddFrom(e)}>
                      Add as record
                    </button>
                    <button type="button" className="link" onClick={() => dismiss(e)}>
                      Not a payment
                    </button>
                  </div>
                </li>
              ))}
          </ul>
        </>
      )}

      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
      {addFrom && (
        <TransactionForm
          draft={{ payee: '', note: addFrom.subject, occurred_at: addFrom.received_at }}
          onClose={() => setAddFrom(null)}
          onSaved={() => {
            dismiss(addFrom);
            w.refresh();
          }}
        />
      )}
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
  const title = t.payee || 'Payment';

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
      toast(`Confirmed ${title}`);
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
            {t.source_label ? ` · ${t.source_label}` : ''}
          </span>
        </span>
        <strong className={`amount review-amount ${t.kind}`}>
          {t.amount > 0 ? w.money(t.kind === 'income' ? t.amount : -t.amount, true) : 'Amount?'}
        </strong>
      </div>
      {t.note && <span className="small muted">{t.note}</span>}
      {t.duplicate_of_id && (
        <span className="notice warn small" role="status">
          <Icon d={icons.warning} className="inline-icon" /> Possible duplicate: you already have a record of this amount around this time.
        </span>
      )}
      <div className="row wrap">
        <select
          aria-label={`Category for ${title}`}
          value={categoryId ?? ''}
          onChange={(e) => onPick(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">No category</option>
          {w.categories
            .filter((c) => c.kind === t.kind)
            .map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
        </select>
        <button type="button" disabled={busy} onClick={confirm}>
          Confirm
        </button>
        <button type="button" className="link" onClick={onEdit}>
          Edit
        </button>
      </div>
      {overriding && (
        <label className="check small">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
          Use {w.category(categoryId)?.name} for {t.payee} from now on, instead of {w.category(t.category_id)?.name}
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

/** Emails the app couldn't act on by itself, and the user hasn't dismissed. */
function needsUser(emails: InboundEmail[]): InboundEmail[] {
  return emails.filter((e) => !e.dismissed && (e.status === 'unparsed' || e.status === 'forwarding'));
}
