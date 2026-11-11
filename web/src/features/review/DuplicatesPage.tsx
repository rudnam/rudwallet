// Caught duplicates: payments from the phone's notifications that looked
// like a record already there (the same amount within a day), so the server
// set them aside instead of adding them to review. Each shows what it
// matched; "Not a duplicate" puts it back in review.
import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, type SetAsideRecord } from '../../api';
import { Icon, icons } from '../../components/Icon';
import { formatDay, formatTime } from '../../money';
import { href } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { t } from '../../i18n';

export function DuplicatesPage() {
  const w = useWallet();
  const toast = useToast();
  const [records, setRecords] = useState<SetAsideRecord[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(() => {
    setError('');
    api.setAside().then(
      (r) => setRecords(r.duplicates),
      (err) => setError(errorMessage(err)),
    );
  }, []);
  useEffect(load, [load]);

  async function bringBack(r: SetAsideRecord) {
    setBusy(r.id);
    try {
      await api.restoreTransactions([r.id]);
      setRecords((list) => list?.filter((x) => x.id !== r.id) ?? null);
      await w.refresh();
      toast(t('{payee} is back in review', { payee: r.payee || t('Payment') }), { label: t('Review'), run: () => (location.hash = '#/review') });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  const money = (x: { kind: string; amount: number }) => w.money(x.kind === 'income' ? x.amount : -x.amount, true);
  const when = (iso: string) => {
    const d = new Date(iso);
    return `${formatDay(d)} ${formatTime(d)}`;
  };

  return (
    <section className="stack">
      <a className="back" href={href('records')}>
        <Icon d={icons.back} />
        {t('Activity')}
      </a>
      <header className="stack tight">
        <h1>{t('Caught duplicates')}</h1>
        <p className="muted lede">
          {t(
            'Payments from notifications that looked like a record you already have (the same amount within a day) are set aside here instead of waiting for review. They’re kept for 60 days. If one was a separate payment, bring it back.',
          )}
        </p>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {records === null && !error && <p className="muted">{t('Loading…')}</p>}
      {records?.length === 0 && (
        <div className="card stack empty">
          <span className="empty-mark" aria-hidden="true">
            <Icon d={icons.check} className="glyph" />
          </span>
          <h2>{t('No duplicates caught lately.')}</h2>
        </div>
      )}
      {records && records.length > 0 && (
        <ul className="list card flush review-list" aria-label={t('Caught duplicates')}>
          {records.map((r) => {
            const m = r.duplicate_of;
            const title = r.payee || t('Payment');
            return (
              <li key={r.id} className="review-item" aria-label={title}>
                <div className="review-row">
                  <div className="grow review-main">
                    <strong className="review-title">{title}</strong>
                    <span className="review-when muted small">
                      {when(r.occurred_at)}
                      {r.source_label && ` · ${r.source_label}`}
                    </span>
                  </div>
                  <span className="record-side">
                    <strong className={`amount ${r.kind}`}>{money(r)}</strong>
                    <span className="muted small">{w.account(r.account_id)?.name}</span>
                  </span>
                </div>
                <div className="notice small duplicate" role="status">
                  <span className="grow">
                    {m ? (
                      <>
                        {t('Looked like this record you already have:')}
                        <span className="block">
                          <strong>{m.payee || t('Payment')}</strong> · {money(m)} · {when(m.occurred_at)} · {w.account(m.account_id)?.name}
                        </span>
                      </>
                    ) : (
                      t('The record it looked like has since been deleted.')
                    )}
                  </span>
                  <button type="button" className="secondary" disabled={busy !== null} onClick={() => bringBack(r)}>
                    {t('Not a duplicate')}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** "N possible duplicates were set aside automatically", in Activity, leading to them. */
export function CaughtNote() {
  const w = useWallet();
  const n = w.set_aside_count ?? 0;
  if (n === 0) return null;
  return (
    <p className="row muted small caught-note">
      <span className="grow">
        {n === 1 ? t('1 possible duplicate was set aside automatically.') : t('{n} possible duplicates were set aside automatically.', { n })}
      </span>
      <a href={href('duplicates')}>{t('Review')}</a>
    </p>
  );
}
