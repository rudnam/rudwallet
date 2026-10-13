// Email automation: the user's forwarding address, how to set up Gmail,
// a way to test with a pasted email, and what arrived recently.
import { useEffect, useState } from 'react';
import { api, errorMessage, type InboundEmail, type Inbox } from '../../api';
import { formatDay, formatTime } from '../../money';
import { Icon, icons } from '../../components/Icon';
import { href } from '../../router';
import { useWallet } from '../../wallet';

// Only the card's usage alerts: adverts and statements stay in Gmail.
const gmailFilter = 'from:(paypay-card.co.jp) subject:(利用速報)';

const statusText: Record<InboundEmail['status'], string> = {
  payment: 'Recorded',
  ignored: 'Not a payment',
  unparsed: 'Needs you',
  forwarding: 'Gmail confirmation',
};

export function EmailSettings() {
  const w = useWallet();
  const [inbox, setInbox] = useState<Inbox | null>(() => api.seen.inbox() ?? null);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState('');
  const [raw, setRaw] = useState('');
  const [tested, setTested] = useState<InboundEmail | null>(null);
  // Shown at once; the server's answer follows.
  const [autoConfirm, setAutoConfirm] = useState(w.settings.auto_confirm);

  useEffect(() => {
    api.inbox().then(setInbox, (err) => setError(errorMessage(err)));
  }, []);

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(what);
      setTimeout(() => setCopied(''), 2000);
    } catch {
      setCopied('');
    }
  }

  async function test() {
    setError('');
    setTested(null);
    try {
      const e = await api.uploadEmail(raw);
      setTested(e);
      setRaw('');
      setInbox(await api.inbox());
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function newAddress() {
    if (!confirm('Get a new address? Mail sent to the old one will be refused, so update your Gmail forwarding too.')) return;
    try {
      setInbox(await api.newAddress());
    } catch (err) {
      setError(errorMessage(err));
    }
  }

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

  return (
    <div className="card stack">
      <p className="small">
        Forward your payment emails here and each one becomes a record for you to confirm.
      </p>

      {inbox === null ? (
        <p className="muted">Loading…</p>
      ) : inbox.address ? (
        <>
          <div className="address">
            <span className="muted small">Your private forwarding address</span>
            <code data-testid="inbox-address">{inbox.address}</code>
            <div className="row">
              <button type="button" onClick={() => copy(inbox.address, 'address')}>
                {copied === 'address' ? 'Copied' : 'Copy address'}
              </button>
              <button type="button" className="link" onClick={newAddress}>
                Get a new address
              </button>
            </div>
          </div>
          <ol className="steps small">
            <li>
              In Gmail on a computer, open <strong>Settings → See all settings → Forwarding and POP/IMAP</strong>, choose{' '}
              <strong>Add a forwarding address</strong> and paste your address.
            </li>
            <li>
              Gmail sends a confirmation to it. It shows up under <a href={href('review')}>To review</a> with a{' '}
              <strong>Confirm forwarding</strong> button. Leave Gmail's own forwarding <strong>off</strong>: only the
              filter below should forward.
            </li>
            <li>
              Search Gmail for (tap to copy):
              <button type="button" className="code-button" onClick={() => copy(gmailFilter, 'filter')}>
                <code>{gmailFilter}</code> <Icon d={copied === 'filter' ? icons.check : icons.copy} className="inline-icon" />
              </button>
              Open the search options (☰), click <strong>Create filter</strong>, tick{' '}
              <strong>Forward it to</strong>, and pick your address.
            </li>
          </ol>
          <p className="small muted">
            Each PayPay Card usage alert (利用速報) becomes a record, including online and tap-to-pay purchases. If other
            PayPay Card emails arrive, purchase verifications and monthly statements are recognised and skipped, so
            nothing is counted twice.
            {inbox.ai_enabled
              ? ' Receipts from other shops are read by the AI reader, so you can forward those too.'
              : ' Other senders need a rule; until then they wait for you under To review.'}
            {!inbox.ai_enabled && (
              <>
                {' '}
                Or <a href={href('settings', { section: 'claude' })}>add your Claude key</a> and Claude reads them.
              </>
            )}
          </p>
        </>
      ) : (
        <p className="notice small">
          Forwarding isn't switched on for this server yet (it needs an inbound mail domain; see docs/email.md). You can
          still test with an email below.
        </p>
      )}

      <div className="stack tight">
        <label className="check">
          <input
            type="checkbox"
            checked={autoConfirm}
            onChange={(e) => setSetting({ auto_confirm: e.target.checked })}
          />
          Confirm payments automatically when the shop's category is already known
        </label>
        <label className="field">
          Emailed payments go to
          <select
            value={w.settings.default_account_id ?? ''}
            onChange={(e) => setSetting({ default_account_id: e.target.value ? Number(e.target.value) : null })}
          >
            <option value="">The first account, until you pick one for a card</option>
            {w.accounts
              .filter((a) => !a.archived)
              .map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
          </select>
        </label>
        <p className="muted small">When you confirm a payment in another account, that card is remembered for next time.</p>
      </div>

      <details className="stack tight">
        <summary>Test with an email</summary>
        <p className="small muted">
          In Gmail, open a payment email, choose <strong>⋮ → Show original</strong>, copy everything, and paste it here.
        </p>
        <textarea
          aria-label="Email source"
          rows={6}
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
          placeholder="Delivered-To: …"
        />
        <div className="row">
          <button type="button" disabled={!raw.trim()} onClick={test}>
            Read email
          </button>
        </div>
      </details>
      {tested && (
        <p className="notice" role="status">
          {tested.duplicate
            ? 'This email was already received.'
            : tested.status === 'payment'
              ? 'Found a payment. It is waiting under To review.'
              : `${statusText[tested.status]}: ${tested.detail}`}
        </p>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {inbox && inbox.emails.length > 0 && (
        <div className="stack tight">
          <h3>Recent emails</h3>
          <ul className="list rows" aria-label="Recent emails">
            {inbox.emails.slice(0, 10).map((e) => (
              <li key={e.id} className="row">
                <span className="grow">
                  <span className="block ellipsis">{e.subject || '(no subject)'}</span>
                  <span className="muted small">
                    {formatDay(new Date(e.received_at))} {formatTime(new Date(e.received_at))} · {e.from}
                  </span>
                </span>
                <span className={`badge status-${e.status}`}>{statusText[e.status]}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
