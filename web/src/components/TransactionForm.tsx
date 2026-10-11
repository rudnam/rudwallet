// Add, edit, or review a record.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Transaction, type TransactionKind } from '../api';
import { amountInput, currencySymbol, parseAmount, toLocalInput } from '../money';
import { useToast } from '../toast';
import { useWallet } from '../wallet';
import { Dialog } from './Dialog';
import { CategoryIcon, Icon, icons } from './Icon';

interface Props {
  /** The record to edit; omit to add a new one. */
  record?: Transaction;
  onClose: () => void;
  /** Called after a save or delete, so the page can reload. */
  onSaved: () => void;
  /** Starting values for a new record, e.g. from an email. */
  draft?: { payee?: string; note?: string; occurred_at?: string };
}

// New records start in the account used last time.
const lastAccountKey = 'wallet:last-account';

/** What the user did last time at each payee, from recent records. */
type Habit = { category_id: number | null; account_id: number; kind: TransactionKind };

export function TransactionForm({ record, onClose, onSaved, draft }: Props) {
  const w = useWallet();
  const toast = useToast();
  const exp = w.settings.currency_exponent;
  const reviewing = record?.status === 'review';
  const accounts = w.accounts.filter((a) => !a.archived || a.id === record?.account_id || a.id === record?.to_account_id);
  const remembered = Number(localStorage.getItem(lastAccountKey));

  const [kind, setKind] = useState<TransactionKind>(record?.kind ?? 'expense');
  const [amount, setAmount] = useState(record && record.amount > 0 ? amountInput(record.amount, exp) : '');
  const [accountId, setAccountId] = useState(
    record?.account_id ?? accounts.find((a) => a.id === remembered)?.id ?? accounts[0]?.id ?? 0,
  );
  const [toAccountId, setToAccountId] = useState(
    record?.to_account_id ?? accounts.find((a) => a.id !== accountId)?.id ?? 0,
  );
  const [categoryId, setCategoryId] = useState<number | null>(record?.category_id ?? null);
  const [when, setWhen] = useState(toLocalInput(new Date(record?.occurred_at ?? draft?.occurred_at ?? Date.now())));
  const [payee, setPayee] = useState(record?.payee ?? draft?.payee ?? '');
  const [note, setNote] = useState(record?.note ?? draft?.note ?? '');
  const [habits, setHabits] = useState<Map<string, Habit>>(new Map());
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Suggest payees from the last few months; picking one fills in its category.
  useEffect(() => {
    api.transactions({ from: new Date(Date.now() - 120 * 86_400_000), status: 'confirmed' }).then(
      (list) => {
        const m = new Map<string, Habit>();
        for (const t of list) if (t.payee && !m.has(t.payee)) m.set(t.payee, t);
        setHabits(m);
      },
      () => {},
    );
  }, []);

  const categories = w.categories.filter((c) => c.kind === kind);
  const duplicate = record?.duplicate_of_id ? record.duplicate_of_id : null;

  function changeKind(k: TransactionKind) {
    setKind(k);
    if (w.category(categoryId)?.kind !== k) setCategoryId(null);
  }

  function changePayee(p: string) {
    setPayee(p);
    const h = habits.get(p);
    if (!h || record) return;
    if (h.kind !== 'transfer') setKind(h.kind);
    if (categoryId === null) setCategoryId(h.category_id);
    if (accounts.some((a) => a.id === h.account_id)) setAccountId(h.account_id);
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
      if (!record) localStorage.setItem(lastAccountKey, String(accountId));
      await w.refresh();
      toast(reviewing ? 'Confirmed' : record ? 'Saved' : `Added ${w.money(minor)}`);
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
      toast('Deleted');
      onSaved();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const accountSelect = (label: string, value: number, set: (id: number) => void) => (
    <label className="field">
      {label}
      <select value={value} onChange={(e) => set(Number(e.target.value))}>
        {accounts.map((a) => (
          <option key={a.id} value={a.id}>
            {a.name}
          </option>
        ))}
      </select>
    </label>
  );

  const title = reviewing ? 'Review payment' : record ? 'Edit record' : 'New record';
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        {reviewing && (
          <p className="notice small">
            From an email{record.source_label ? ` (${record.source_label})` : ''}. Check it, pick a category, and confirm.
            Next time, this shop and card are filled in for you.
          </p>
        )}
        {duplicate && (
          <p className="notice warn small" role="status">
            <Icon d={icons.warning} className="inline-icon" /> You already have a record of the same amount around this time. If it's the same payment, delete this one.
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
        <label className="amount-field">
          <span className="sr-only">Amount ({w.settings.currency})</span>
          <span className="currency" aria-hidden="true">
            {currencySymbol(w.settings)}
          </span>
          <input
            inputMode={exp === 0 ? 'numeric' : 'decimal'}
            autoComplete="off"
            placeholder="0"
            // Grows with the number, so the currency sign stays beside it.
            size={Math.max(amount.length, 1)}
            required
            autoFocus={!record}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        {kind === 'transfer' ? (
          <div className="grid2">
            {accountSelect('From', accountId, setAccountId)}
            {accountSelect('To', toAccountId, setToAccountId)}
          </div>
        ) : (
          <fieldset className="chip-pick">
            <legend>Category</legend>
            <div className="chips">
              {categories.map((c) => (
                <label key={c.id} className={categoryId === c.id ? 'chip on' : 'chip'}>
                  <input
                    type="radio"
                    name="category"
                    checked={categoryId === c.id}
                    onChange={() => setCategoryId(c.id)}
                    // Tapping the chosen category again clears it.
                    onClick={() => categoryId === c.id && setCategoryId(null)}
                  />
                  <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" />
                  {c.name}
                </label>
              ))}
            </div>
          </fieldset>
        )}
        {kind !== 'transfer' && (
          <label className="field">
            {kind === 'income' ? 'From whom' : 'Payee'}
            <input
              value={payee}
              maxLength={500}
              list="payees"
              autoComplete="off"
              placeholder={kind === 'income' ? 'Employer, friend…' : 'Shop or person'}
              onChange={(e) => changePayee(e.target.value)}
            />
            <datalist id="payees">
              {[...habits.keys()].map((p) => (
                <option key={p} value={p} />
              ))}
            </datalist>
          </label>
        )}
        <div className="grid2">
          {kind !== 'transfer' && accountSelect('Account', accountId, setAccountId)}
          <label className="field">
            Date
            <input type="datetime-local" required value={when} onChange={(e) => setWhen(e.target.value)} />
          </label>
        </div>
        <label className="field">
          Note
          <input value={note} maxLength={500} placeholder="Optional" onChange={(e) => setNote(e.target.value)} />
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
