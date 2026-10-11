// Add, edit, or review a record.
import { useState, type FormEvent } from 'react';
import { api, errorMessage, type Transaction, type TransactionKind } from '../api';
import { amountInput, parseAmount, toLocalInput } from '../money';
import { useWallet } from '../wallet';
import { Dialog } from './Dialog';

interface Props {
  /** The record to edit; omit to add a new one. */
  record?: Transaction;
  onClose: () => void;
  /** Called after a save or delete, so the page can reload. */
  onSaved: () => void;
  /** Starting values for a new record, e.g. from an email. */
  draft?: { payee?: string; note?: string; occurred_at?: string };
}

export function TransactionForm({ record, onClose, onSaved, draft }: Props) {
  const w = useWallet();
  const exp = w.settings.currency_exponent;
  const reviewing = record?.status === 'review';
  const accounts = w.accounts.filter((a) => !a.archived || a.id === record?.account_id || a.id === record?.to_account_id);

  const [kind, setKind] = useState<TransactionKind>(record?.kind ?? 'expense');
  const [amount, setAmount] = useState(record && record.amount > 0 ? amountInput(record.amount, exp) : '');
  const [accountId, setAccountId] = useState(record?.account_id ?? accounts[0]?.id ?? 0);
  const [toAccountId, setToAccountId] = useState(
    record?.to_account_id ?? accounts.find((a) => a.id !== accountId)?.id ?? 0,
  );
  const [categoryId, setCategoryId] = useState<number | null>(record?.category_id ?? null);
  const [when, setWhen] = useState(toLocalInput(new Date(record?.occurred_at ?? draft?.occurred_at ?? Date.now())));
  const [payee, setPayee] = useState(record?.payee ?? draft?.payee ?? '');
  const [note, setNote] = useState(record?.note ?? draft?.note ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const categories = w.categories.filter((c) => c.kind === kind);
  const duplicate = record?.duplicate_of_id ? record.duplicate_of_id : null;

  function changeKind(k: TransactionKind) {
    setKind(k);
    if (w.category(categoryId)?.kind !== k) setCategoryId(null);
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(amount, exp);
    if (minor === null || minor <= 0) {
      setError(exp === 0 ? 'Enter an amount, like 1200.' : 'Enter an amount, like 12.50.');
      return;
    }
    setBusy(true);
    setError('');
    const input = {
      kind,
      amount: minor,
      account_id: accountId,
      to_account_id: kind === 'transfer' ? toAccountId : null,
      category_id: kind === 'transfer' ? null : categoryId,
      occurred_at: new Date(when).toISOString(),
      payee: kind === 'transfer' ? '' : payee,
      note,
      status: 'confirmed' as const,
    };
    try {
      if (record) await api.updateTransaction(record.id, input);
      else await api.createTransaction(input);
      await w.refresh();
      onSaved();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!record || !confirm('Delete this record?')) return;
    setBusy(true);
    try {
      await api.deleteTransaction(record.id);
      await w.refresh();
      onSaved();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const title = reviewing ? 'Review payment' : record ? 'Edit record' : 'New record';
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        {reviewing && (
          <p className="notice">
            From an email{record.source_label ? ` (${record.source_label})` : ''}. Check it, pick a category, and confirm.
            Next time, this shop and card are filled in for you.
          </p>
        )}
        {duplicate && (
          <p className="notice warn" role="status">
            ⚠ You already have a record of the same amount around this time. If it's the same payment, delete this one.
          </p>
        )}
        <div className="segmented" role="radiogroup" aria-label="Type">
          {(['expense', 'income', 'transfer'] as const).map((k) => (
            <label key={k} className={kind === k ? 'on' : ''}>
              <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => changeKind(k)} />
              {k === 'expense' ? 'Expense' : k === 'income' ? 'Income' : 'Transfer'}
            </label>
          ))}
        </div>
        <label className="field">
          Amount ({w.settings.currency})
          <input
            inputMode={exp === 0 ? 'numeric' : 'decimal'}
            autoComplete="off"
            required
            autoFocus={!record}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <div className="grid2">
          <label className="field">
            {kind === 'transfer' ? 'From' : 'Account'}
            <select value={accountId} onChange={(e) => setAccountId(Number(e.target.value))}>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
          {kind === 'transfer' ? (
            <label className="field">
              To
              <select value={toAccountId} onChange={(e) => setToAccountId(Number(e.target.value))}>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <label className="field">
              Category
              <select
                value={categoryId ?? ''}
                onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">No category</option>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.icon} {c.name}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        <label className="field">
          Date
          <input type="datetime-local" required value={when} onChange={(e) => setWhen(e.target.value)} />
        </label>
        {kind !== 'transfer' && (
          <label className="field">
            {kind === 'income' ? 'From whom' : 'Payee'}
            <input value={payee} maxLength={500} onChange={(e) => setPayee(e.target.value)} />
          </label>
        )}
        <label className="field">
          Note
          <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          {record && (
            <button type="button" className="link danger" disabled={busy} onClick={remove}>
              Delete
            </button>
          )}
          <span className="grow" />
          <button type="submit" disabled={busy}>
            {reviewing ? 'Confirm' : 'Save'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
