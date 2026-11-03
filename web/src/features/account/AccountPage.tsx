// One account, opened from its card on Wallet: the card in full, this
// month in and out, and its records. Cash, the one account that can't fill
// itself in, can be counted: what's actually in the wallet becomes the
// balance, and the difference is recorded as an adjustment. Nothing here
// asks on a schedule.
import { useState, type CSSProperties, type FormEvent } from 'react';
import { api, errorMessage, type Transaction } from '../../api';
import { Dialog } from '../../components/Dialog';
import { Icon, icons } from '../../components/Icon';
import { TransactionForm } from '../../components/TransactionForm';
import { TransactionList } from '../../components/TransactionList';
import { t } from '../../i18n';
import { addMonths, amountInput, currencySymbol, monthStart, parseAmount } from '../../money';
import { href, type Route } from '../../router';
import { useToast } from '../../toast';
import { useRecords } from '../../useRecords';
import { useWallet } from '../../wallet';
import { accountKinds } from '../settings/SettingsPage';
import { hapticSuccess } from '../../native';
import { locale } from '../../language';

export function AccountPage({ route }: { route: Route }) {
  const w = useWallet();
  const id = Number(route.params.get('id'));
  const account = w.account(id);
  const [month] = useState(() => monthStart(new Date()));
  const { records, reload } = useRecords({ account_id: id, from: month, to: addMonths(month, 1) });
  const [open, setOpen] = useState<Transaction | null>(null);
  const [counting, setCounting] = useState(false);

  if (!account) {
    return (
      <section className="stack">
        <a className="back" href={href('home')}>
          <Icon d={icons.back} />
          {t('Wallet')}
        </a>
        <p className="muted">{t('This account no longer exists.')}</p>
      </section>
    );
  }

  const confirmed = (records ?? []).filter((r) => r.status === 'confirmed');
  // Money out of and into this account, transfers included.
  const out = confirmed.filter((r) => r.account_id === id && r.kind !== 'income').reduce((s, r) => s + r.amount, 0);
  const into = confirmed
    .filter((r) => (r.kind === 'income' && r.account_id === id) || (r.kind === 'transfer' && r.to_account_id === id))
    .reduce((s, r) => s + r.amount, 0);
  const monthName = month.toLocaleDateString(locale(), { month: 'long' });

  return (
    <section className="stack loose account-page">
      <header className="wallet-head">
        <a className="back" href={href('home')}>
          <Icon d={icons.back} />
          {t('Wallet')}
        </a>
        <a className="icon-button" href={href('settings', { section: 'accounts' })} aria-label={t('Edit {name}', { name: account.name })}>
          <Icon d={icons.edit} />
        </a>
      </header>

      <div className="wallet-card front static ink-light" style={{ '--c': account.color } as CSSProperties}>
        <span className="wallet-card-head">
          <h1 className="ellipsis">{account.name}</h1>
          <span className="wallet-card-kind">{t(accountKinds[account.kind]?.label ?? 'Other')}</span>
        </span>
        <span className="wallet-card-big">{w.money(account.balance)}</span>
      </div>

      {account.kind === 'cash' && (
        <button type="button" className="secondary wide" onClick={() => setCounting(true)}>
          <Icon d={icons.count} />
          {t('Count cash')}
        </button>
      )}

      <div className="card flush split" role="group" aria-label={monthName}>
        <div>
          <span className="muted small">{t('Out in {month}', { month: monthName })}</span>
          <strong>{w.money(out)}</strong>
        </div>
        <div>
          <span className="muted small">{t('In')}</span>
          <strong>{w.money(into)}</strong>
        </div>
      </div>

      {records === null ? (
        <p className="muted">{t('Loading…')}</p>
      ) : records.length === 0 ? (
        <p className="muted">{t('Nothing recorded in this account this month.')}</p>
      ) : (
        <TransactionList records={records} onOpen={setOpen} label={t('Records')} detail="time" />
      )}

      {open && <TransactionForm record={open} onClose={() => setOpen(null)} onSaved={reload} />}
      {counting && <CountCash id={id} onClose={() => setCounting(false)} onCounted={reload} />}
    </section>
  );
}

/** What's in the wallet now: the difference from the app's balance is recorded. */
function CountCash({ id, onClose, onCounted }: { id: number; onClose: () => void; onCounted: () => void }) {
  const w = useWallet();
  const toast = useToast();
  const account = w.account(id)!;
  const exp = w.settings.currency_exponent;
  const [text, setText] = useState(amountInput(account.balance, exp));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(text, exp);
    if (minor === null) {
      setError(exp === 0 ? t('Enter an amount, like 1200.') : t('Enter an amount, like 12.50.'));
      return;
    }
    setBusy(true);
    try {
      const { transaction } = await api.reconcileAccount(id, minor);
      await w.refresh();
      hapticSuccess();
      toast(transaction ? t('Counted: {amount}', { amount: w.money(minor) }) : t('Already right: {amount}', { amount: w.money(minor) }));
      onCounted();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <Dialog title={t('Count cash')} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <p className="muted">{t('Enter what’s in your wallet now. The difference from {amount} is recorded for you.', { amount: w.money(account.balance) })}</p>
        <label className="amount-field">
          <span className="sr-only">{t('Amount ({currency})', { currency: w.settings.currency })}</span>
          <span className="currency" aria-hidden="true">
            {currencySymbol(w.settings)}
          </span>
          <input
            inputMode={exp === 0 ? 'numeric' : 'decimal'}
            autoComplete="off"
            size={Math.max(text.length, 1)}
            data-autofocus
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end dialog-actions">
          <button type="submit" disabled={busy}>
            {t('Save')}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
