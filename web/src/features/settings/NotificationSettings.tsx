// Settings › Payment notifications: the Android app reads Google Wallet's
// payment notifications once the user gives it notification access, and
// each payment becomes a record to review. What arrived is listed here.
import { useEffect, useState } from 'react';
import { api, errorMessage, type PaymentNotification } from '../../api';
import { formatDay, formatTime } from '../../money';
import { isApp, notificationAccess, openNotificationAccess } from '../../native';
import { href } from '../../router';

const statusText: Record<PaymentNotification['status'], string> = {
  payment: 'Recorded',
  matched: 'Already recorded',
  ignored: 'Not a payment',
  unparsed: 'Needs you',
};

export function NotificationSettings() {
  const [access, setAccess] = useState<boolean | null>(null);
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
        Pay with Google Wallet and the payment waits under <a href={href('review')}>To review</a> moments later, with
        the shop, amount and card filled in.
      </p>

      {!isApp ? (
        <p className="notice small">
          This works in the Wallet app for Android, which reads your phone's payment notifications. Open{' '}
          <strong>Settings › Payment notifications</strong> there to switch it on.
        </p>
      ) : access === null ? (
        <p className="muted">Checking…</p>
      ) : (
        <div className="stack tight">
          <div className="row">
            <span className="grow" data-testid="notification-access">
              {access ? (
                <>
                  <strong>On.</strong> Wallet reads Google Wallet's notifications.
                </>
              ) : (
                <>
                  <strong>Off.</strong> Wallet needs notification access to read them.
                </>
              )}
            </span>
            <button type="button" className={access ? 'link' : undefined} onClick={open}>
              {access ? 'Turn off' : 'Allow access'}
            </button>
          </div>
          {!access && (
            <p className="muted small">
              Android shows a list of apps: turn on <strong>Wallet</strong>. If the switch is greyed out, open{' '}
              <strong>App info › ⋮ › Allow restricted settings</strong> for Wallet, then try again.
            </p>
          )}
        </div>
      )}

      <p className="muted small">
        Only Google Wallet's notifications are read; everything else stays on your phone. Each payment is recorded once:
        if its card's email arrives too, or you already added it, the two are matched instead of counted twice.
        Notifications that arrive while you're offline are sent later.
      </p>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {recent && recent.length > 0 && (
        <div className="stack tight">
          <h3>Recent notifications</h3>
          <ul className="list rows" aria-label="Recent notifications">
            {recent.slice(0, 10).map((n) => (
              <li key={n.id} className="row">
                <span className="grow">
                  <span className="block ellipsis">
                    {n.title || n.app}
                    {n.text ? ` · ${n.text}` : ''}
                  </span>
                  <span className="muted small">
                    {formatDay(new Date(n.posted_at))} {formatTime(new Date(n.posted_at))} · {n.app || n.package}
                    {n.detail && n.status !== 'matched' ? ` · ${n.detail}` : ''}
                  </span>
                </span>
                <span className={`badge status-${n.status}`}>{statusText[n.status]}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
