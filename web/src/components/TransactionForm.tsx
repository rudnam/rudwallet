// Add, edit, or review a record.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Transaction, type TransactionInput, type TransactionKind } from '../api';
import { t, type Key } from '../i18n';
import { amountInput, currencySymbol, formatDay, formatTime, parseAmount, toLocalInput } from '../money';
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

/** One field an edit changes, as the user reads it. */
type Change = { field: string; from: string; to: string };

const kindNames: Record<TransactionKind, Key> = { expense: 'Expense', income: 'Income', transfer: 'Transfer' };

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
  // An edit waiting for the user to check what it changes.
  const [pending, setPending] = useState<{ input: TransactionInput; changes: Change[] } | null>(null);

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
      setError(exp === 0 ? t('Enter an amount, like 1200.') : t('Enter an amount, like 12.50.'));
      return;
    }
    setError('');
    const input = {
      kind,
      amount: minor,
      account_id: accountId,
      to_account_id: kind === 'transfer' ? toAccountId : null,
      category_id: kind === 'transfer' ? null : categoryId,
      // An untouched date keeps its seconds, so it doesn't count as a change.
      occurred_at: record && when === toLocalInput(new Date(record.occurred_at)) ? record.occurred_at : new Date(when).toISOString(),
      payee: kind === 'transfer' ? '' : payee,
      note,
      status: 'confirmed' as const,
    };
    // Editing a saved record: show what changes before saving it.
    if (record && !reviewing) {
      const changes = describeChanges(record, input);
      if (changes.length === 0) {
        onClose();
        return;
      }
      setPending({ input, changes });
      return;
    }
    await commit(input);
  }

  async function commit(input: TransactionInput) {
    setBusy(true);
    setError('');
    try {
      if (record) await api.updateTransaction(record.id, input);
      else await api.createTransaction(input);
      if (!record) localStorage.setItem(lastAccountKey, String(accountId));
      await w.refresh();
      if (record && !reviewing) toast(t('Saved'), { label: t('Undo'), run: () => undo(record) });
      else toast(reviewing ? t('Confirmed') : t('Added {amount}', { amount: w.money(input.amount) }));
      onSaved();
      onClose();
    } catch (err) {
      setPending(null);
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  // Puts the record back as it was before the edit. The form is closed by
  // now, so this uses only what it captured.
  async function undo(before: Transaction) {
    try {
      await api.updateTransaction(before.id, {
        kind: before.kind,
        amount: before.amount,
        account_id: before.account_id,
        to_account_id: before.to_account_id,
        category_id: before.category_id,
        occurred_at: before.occurred_at,
        payee: before.payee,
        note: before.note,
        status: before.status,
        // Restoring an old category doesn't teach it to the shop again.
        remember: false,
      });
      await w.refresh();
      toast(t('Change undone'));
      onSaved();
    } catch (err) {
      toast(t("Couldn't undo: {error}", { error: errorMessage(err) }));
    }
  }

  // Brings back the record just deleted, with its id and links.
  async function restore(id: number) {
    try {
      await api.restoreTransactions([id]);
      await w.refresh();
      toast(t('Record restored'));
      onSaved();
    } catch (err) {
      toast(t("Couldn't restore: {error}", { error: errorMessage(err) }));
    }
  }

  /** The fields an edit changes, old and new, in the order the form shows them. */
  function describeChanges(before: Transaction, after: TransactionInput): Change[] {
    const name = (id: number | null) => w.account(id)?.name ?? t('None');
    const category = (id: number | null) => w.category(id)?.name ?? t('None');
    const when = (iso: string) => {
      const d = new Date(iso);
      return `${formatDay(d)}, ${formatTime(d)}`;
    };
    const text = (s: string) => s || t('None');
    const transfer = after.kind === 'transfer' || before.kind === 'transfer';
    const fields: [Key, string, string][] = [
      ['Type', t(kindNames[before.kind]), t(kindNames[after.kind])],
      ['Amount', w.money(before.amount), w.money(after.amount)],
      ['Category', category(before.category_id), category(after.category_id)],
      [after.kind === 'income' ? 'From whom' : 'Payee', text(before.payee), text(after.payee)],
      [transfer ? 'From' : 'Account', name(before.account_id), name(after.account_id)],
      ['To', name(before.to_account_id), name(after.to_account_id)],
      ['Date', when(before.occurred_at), when(after.occurred_at)],
      ['Note', text(before.note), text(after.note)],
    ];
    return fields.filter(([, from, to]) => from !== to).map(([field, from, to]) => ({ field: t(field), from, to }));
  }

  async function remove() {
    if (!record || !confirm(t('Delete this record?'))) return;
    setBusy(true);
    try {
      await api.deleteTransaction(record.id);
      await w.refresh();
      toast(t('Deleted'), { label: t('Undo'), run: () => restore(record.id) });
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

  const title = reviewing ? t('Review payment') : record ? t('Edit record') : t('New record');
  if (pending) {
    return (
      <Dialog title={t('Save changes?')} onClose={onClose}>
        <div className="stack">
          <ul className="changes" aria-label={t('Changes')}>
            {pending.changes.map((c) => (
              <li key={c.field}>
                <span className="muted small">{c.field}</span>
                <span>
                  <s className="muted">{c.from}</s>
                  <Icon d={icons.level} className="inline-icon muted" />
                  <span>{c.to}</span>
                </span>
              </li>
            ))}
          </ul>
          <div className="row end">
            <button type="button" className="secondary" disabled={busy} onClick={() => setPending(null)}>
              {t('Back')}
            </button>
            <button type="button" disabled={busy} autoFocus onClick={() => commit(pending.input)}>
              {t('Save changes')}
            </button>
          </div>
        </div>
      </Dialog>
    );
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        {reviewing && (
          <p className="notice small">
            {record.source_label
              ? t('From an email ({card}). Check it, pick a category, and confirm. Next time, this shop and card are filled in for you.', { card: record.source_label })
              : t('From an email. Check it, pick a category, and confirm. Next time, this shop and card are filled in for you.')}
          </p>
        )}
        {duplicate && (
          <p className="notice warn small" role="status">
            <Icon d={icons.warning} className="inline-icon" /> {t("You already have a record of the same amount around this time. If it's the same payment, delete this one.")}
          </p>
        )}
        <div className="segmented" role="radiogroup" aria-label={t('Type')}>
          {(['expense', 'income', 'transfer'] as const).map((k) => (
            <label key={k} className={kind === k ? 'on' : ''}>
              <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => changeKind(k)} />
              {k === 'expense' ? t('Expense') : k === 'income' ? t('Income') : t('Transfer')}
            </label>
          ))}
        </div>
        <label className="amount-field">
          <span className="sr-only">{t('Amount ({currency})', { currency: w.settings.currency })}</span>
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
            {accountSelect(t('From'), accountId, setAccountId)}
            {accountSelect(t('To'), toAccountId, setToAccountId)}
          </div>
        ) : (
          <fieldset className="chip-pick">
            <legend>{t('Category')}</legend>
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
            {kind === 'income' ? t('From whom') : t('Payee')}
            <input
              value={payee}
              maxLength={500}
              list="payees"
              autoComplete="off"
              placeholder={kind === 'income' ? t('Employer, friend…') : t('Shop or person')}
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
          {kind !== 'transfer' && accountSelect(t('Account'), accountId, setAccountId)}
          <label className="field">
            {t('Date')}
            <input type="datetime-local" required value={when} onChange={(e) => setWhen(e.target.value)} />
          </label>
        </div>
        <label className="field">
          {t('Note')}
          <input value={note} maxLength={500} placeholder={t('Optional')} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          {record && (
            <button type="button" className="link danger" disabled={busy} onClick={remove}>
              {t('Delete')}
            </button>
          )}
          <span className="grow" />
          <button type="submit" disabled={busy}>
            {reviewing ? t('Confirm') : t('Save')}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
