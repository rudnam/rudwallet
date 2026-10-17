// Settings › Payment notifications: the Android app reads PayPay's and
// Google Wallet's payment notifications once the user gives it notification
// access, and each payment becomes a record to review. Where those records
// go, and what arrived, are here too.
import { useEffect, useState } from 'react';
import { api, errorMessage, type PaymentNotification } from '../../api';
import { formatDay, formatTime } from '../../money';
import { isApp, notificationAccess, openNotificationAccess } from '../../native';
import { href } from '../../router';
import { useWallet } from '../../wallet';
import { serverText, t, tx, type Key } from '../../i18n';

const statusText: Record<PaymentNotification['status'], Key> = {
  payment: 'Recorded',
  matched: 'Already recorded',
  ignored: 'Not a payment',
  unparsed: 'Needs you',
};

export function NotificationSettings() {
  const w = useWallet();
  const [access, setAccess] = useState<boolean | null>(null);
  // Shown at once; the server's answer follows.
  const [autoConfirm, setAutoConfirm] = useState(w.settings.auto_confirm);
  const [recent, setRecent] = useState<PaymentNotification[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api.notifications().then((r) => setRecent(r.notifications), (err) => setError(errorMessage(err)));
    if (!isApp) return;
    // Check again on coming back from Android's settings.
    const check = () => {
      if (document.visibilityState === 'visible') notificationAccess().then(setAccess);
    };
    check();
    document.addEventListener('visibilitychange', check);
    return () => document.removeEventListener('visibilitychange', check);
  }, []);

  async function setSetting(changes: { auto_confirm?: boolean; default_account_id?: number | null }) {
    if (changes.auto_confirm !== undefined) setAutoConfirm(changes.auto_confirm);
    try {
      await api.updateSettings({ ...w.settings, ...changes });
      await w.refresh();
    } catch (err) {
      setAutoConfirm(w.settings.auto_confirm);
      setError(errorMessage(err));
    }
  }

  async function open() {
    try {
      await openNotificationAccess();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="card stack">
      <p className="small">
        {tx('Pay with PayPay or Google Wallet and the payment waits under {review} moments later, with the shop, amount and card filled in.', {
          review: <a href={href('review')}>{t('To review')}</a>,
        })}
      </p>

      {!isApp ? (
        <p className="notice small">
          {tx("This works in the RudWallet app for Android, which reads your phone's payment notifications. Open {where} there to switch it on.", {
            where: <strong>{t('Settings › Payment notifications')}</strong>,
          })}
        </p>
      ) : access === null ? (
        <p className="muted">{t('Checking…')}</p>
      ) : (
        <div className="stack tight">
          <div className="row">
            <span className="grow" data-testid="notification-access">
              {access
                ? tx("{on} RudWallet reads PayPay's and Google Wallet's notifications.", { on: <strong>{t('On.')}</strong> })
                : tx('{off} RudWallet needs notification access to read them.', { off: <strong>{t('Off.')}</strong> })}
            </span>
            <button type="button" className={access ? 'link' : undefined} onClick={open}>
              {access ? t('Turn off') : t('Allow access')}
            </button>
          </div>
          {!access && (
            <p className="muted small">
              {tx('Android shows a list of apps: turn on {wallet}. If the switch is greyed out, open {where} for RudWallet, then try again.', {
                wallet: <strong>RudWallet</strong>,
                where: <strong>{t('App info › ⋮ › Allow restricted settings')}</strong>,
              })}
            </p>
          )}
        </div>
      )}

      <p className="muted small">
        {t(
          "Only PayPay's and Google Wallet's notifications are read; everything else stays on your phone. Each payment is recorded once: if you already added it, the two are matched instead of counted twice. Notifications that arrive while you're offline are sent later.",
        )}
      </p>

      <div className="stack tight">
        <label className="check">
          <input type="checkbox" checked={autoConfirm} onChange={(e) => setSetting({ auto_confirm: e.target.checked })} />
          {t("Confirm payments automatically when the shop's category is already known")}
        </label>
        <label className="field">
          {t('Payments go to')}
          <select
            value={w.settings.default_account_id ?? ''}
            onChange={(e) => setSetting({ default_account_id: e.target.value ? Number(e.target.value) : null })}
          >
            <option value="">{t('The first account, until you pick one for a card')}</option>
            {w.accounts
              .filter((a) => !a.archived)
              .map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
          </select>
        </label>
        <p className="muted small">
          {t('When you confirm a payment in another account, that card is remembered for next time.')}{' '}
          {tx('See and change what was remembered under {rules}.', {
            rules: <a href={href('settings', { section: 'rules' })}>{t('Shops and cards')}</a>,
          })}
        </p>
      </div>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {recent && recent.length > 0 && (
        <div className="stack tight">
          <h3>{t('Recent notifications')}</h3>
          <ul className="list rows" aria-label={t('Recent notifications')}>
            {recent.slice(0, 10).map((n) => (
              <li key={n.id} className="row">
                <span className="grow">
                  <span className="block ellipsis">
                    {n.title || n.app}
                    {n.text ? ` · ${n.text}` : ''}
                  </span>
                  <span className="muted small">
                    {formatDay(new Date(n.posted_at))} {formatTime(new Date(n.posted_at))} · {n.app || n.package}
                    {n.detail && n.status !== 'matched' ? ` · ${serverText(n.detail)}` : ''}
                  </span>
                </span>
                <span className={`badge status-${n.status}`}>{t(statusText[n.status])}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
