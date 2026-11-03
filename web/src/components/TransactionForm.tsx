// Add, edit, or review a record.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Transaction, type TransactionInput, type TransactionKind } from '../api';
import { t, type Key } from '../i18n';
import { amountInput, currencySymbol, formatDay, formatTime, parseAmount, toLocalInput } from '../money';
import { useToast } from '../toast';
import { useWallet } from '../wallet';
import { Dialog } from './Dialog';
import { addTag, TagField } from './TagField';
import { CategoryIcon, Icon, icons } from './Icon';
import { hapticError, hapticKey, hapticSelect, hapticSuccess } from '../native';

interface Props {
  /** The record to edit; omit to add a new one. */
  record?: Transaction;
  onClose: () => void;
  /** Called after a save or delete, so the page can reload. */
  onSaved: () => void;
  /** Starting values for a new record. */
  draft?: { payee?: string; note?: string; occurred_at?: string };
}

// New records start in the account used last time.
const lastAccountKey = 'wallet:last-account';

/** What the user did last time at each payee, from recent records. */
type Habit = { category_id: number | null; account_id: number; kind: TransactionKind };

/** One field an edit changes, as the user reads it. */
type Change = { field: string; from: string; to: string };

/** How many categories show before More. */
const fewCategories = 7;

/** How many recent payees are offered as one-tap chips on a new record. */
const fewPayees = 4;

/**
 * Phones and tablets get the app's own number pad for the amount, so the
 * keyboard doesn't jump up over the form. A mouse and keyboard type as usual.
 */
const touch = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;

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
  const [tags, setTags] = useState<string[]>(record?.tags ?? []);
  const [tagText, setTagText] = useState('');
  const [knownTags, setKnownTags] = useState<string[]>(() => api.seen.tags()?.map((x) => x.name) ?? []);
  const [habits, setHabits] = useState<Map<string, Habit>>(new Map());
  // How many recent records each category has, to show the most used first.
  const [use, setUse] = useState<Map<number, number>>(new Map());
  const [allCategories, setAllCategories] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // An edit waiting for the user to check what it changes.
  const [pending, setPending] = useState<{ input: TransactionInput; changes: Change[] } | null>(null);
  // A new record on a touch screen is entered on the number pad, which stays
  // at the bottom of the sheet, in the thumb's reach, with Save on it. It
  // steps aside only while a text field has the phone's keyboard.
  const quick = touch && !record;
  const [typing, setTyping] = useState(false);
  const pad = quick && !typing;
  // The date shows as a chip until the user asks to change it.
  const [editingWhen, setEditingWhen] = useState(false);
  const [extras, setExtras] = useState(false);

  // Suggest payees from the last few months; picking one fills in its category.
  useEffect(() => {
    api.transactions({ from: new Date(Date.now() - 120 * 86_400_000), status: 'confirmed' }).then(
      (list) => {
        const m = new Map<string, Habit>();
        const counts = new Map<number, number>();
        for (const t of list) {
          if (t.payee && !m.has(t.payee)) m.set(t.payee, t);
          if (t.category_id !== null) counts.set(t.category_id, (counts.get(t.category_id) ?? 0) + 1);
        }
        setHabits(m);
        setUse(counts);
      },
      () => {},
    );
    api.tags().then((list) => setKnownTags(list.map((x) => x.name)), () => {});
  }, []);

  // The most used categories first (sort keeps the rest in their order); a
  // few show until the user asks for all, and the chosen one always shows.
  const categories = w.categories
    .filter((c) => c.kind === kind)
    .sort((a, b) => (use.get(b.id) ?? 0) - (use.get(a.id) ?? 0));
  const folding = categories.length > fewCategories + 1;
  const chosenHidden = categories.findIndex((c) => c.id === categoryId) >= fewCategories;
  const shownCategories =
    !folding || allCategories
      ? categories
      : // A chosen category from further down takes the last place, so More stays on the second row.
        categories.filter((c, i) => i < fewCategories - (chosenHidden ? 1 : 0) || c.id === categoryId);
  const duplicate = record?.duplicate_of_id ? record.duplicate_of_id : null;

  function changeKind(k: TransactionKind) {
    setKind(k);
    hapticSelect();
    if (w.category(categoryId)?.kind !== k) setCategoryId(null);
  }

  function chooseCategory(id: number) {
    setCategoryId(id);
    hapticSelect();
  }

  /** A key on the number pad: a digit, '000', the decimal point, or 'back'. */
  function press(key: string) {
    hapticKey();
    setAmount((a) => {
      if (key === 'back') return grouped(a.replace(/,/g, '').slice(0, -1));
      const plain = a.replace(/,/g, '');
      if (key === '.') return exp === 0 || plain.includes('.') ? a : (plain || '0') + '.';
      const [, frac] = plain.split('.');
      if (frac !== undefined && frac.length + key.length > exp) return a;
      if (plain.replace('.', '').length + key.length > 12) return a;
      // No leading zeros: 0 and 000 on nothing leave it as it is.
      if (plain === '0' || plain === '') return /^0+$/.test(key) ? plain : key;
      return grouped(plain + key);
    });
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
      hapticError();
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
      // A tag still being typed counts too.
      tags: addTag(tags, tagText),
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
      const saved = record ? await api.updateTransaction(record.id, input) : await api.createTransaction(input);
      if (!record) localStorage.setItem(lastAccountKey, String(accountId));
      // Kept on the phone for now: don't hold the form open waiting for the network.
      if (saved.unsent) void w.refresh();
      else await w.refresh();
      hapticSuccess();
      if (record && !reviewing) toast(t('Saved'), { label: t('Undo'), run: () => undo(record) });
      else if (saved.unsent) toast(t('Saved on this phone. It’s sent when you’re online.'));
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
        tags: before.tags,
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
    const tagList = (tags: string[]) => text(tags.map((x) => `#${x}`).join(' '));
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
      ['Tags', tagList(before.tags), tagList(after.tags ?? before.tags)],
    ];
    return fields.filter(([, from, to]) => from !== to).map(([field, from, to]) => ({ field: t(field), from, to }));
  }

  async function remove() {
    if (!record || !confirm(t('Delete this record?'))) return;
    setBusy(true);
    try {
      await api.deleteTransaction(record.id);
      // One not sent yet is gone from the phone, with nothing to restore.
      if (record.unsent) toast(t('Deleted'));
      else {
        await w.refresh();
        toast(t('Deleted'), { label: t('Undo'), run: () => restore(record.id) });
      }
      onSaved();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  // Accounts as a row of chips in their colours: for an expense or income, the
  // account; for a transfer, where from and where to.
  const accountPick = (label: string, name: string, value: number, set: (id: number) => void) => (
    <div className="detail">
      <span id={`record-${name}`}>{label}</span>
      <div className="account-pick" role="radiogroup" aria-labelledby={`record-${name}`}>
        {accounts.map((a) => (
          <label key={a.id} className={value === a.id ? 'on' : ''}>
            <input
              type="radio"
              name={name}
              checked={value === a.id}
              onChange={() => {
                set(a.id);
                hapticSelect();
              }}
            />
            <span className="swatch" style={{ background: a.color }} aria-hidden="true" />
            {a.name}
          </label>
        ))}
      </div>
    </div>
  );
  const accountChips = accountPick(t('Account'), 'account', accountId, setAccountId);
  // On the phone's quick sheet, note and tags wait behind one row until wanted.
  const showExtras = !quick || extras || note !== '' || tags.length > 0;

  // Recent payees, offered while a new record has none yet.
  const recentPayees = !record && !payee ? [...habits.keys()].slice(0, fewPayees) : [];

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
      <form
        className="stack"
        onSubmit={save}
        // The phone's keyboard and the number pad never show together.
        onFocus={(e) => setTyping(isTextField(e.target))}
        onBlur={(e) => setTyping(isTextField(e.relatedTarget))}
      >
        {reviewing && (
          <p className="notice small">
            {record.source_label
              ? t('From a notification ({card}). Check it, pick a category, and confirm. Next time, this shop and card are filled in for you.', { card: record.source_label })
              : t('From a notification. Check it, pick a category, and confirm. Next time, this shop and card are filled in for you.')}
          </p>
        )}
        {duplicate && (
          <p className="notice warn small" role="status">
            <Icon d={icons.warning} className="inline-icon" /> {t("You already have a record of the same amount around this time. If it's the same payment, delete this one.")}
          </p>
        )}
        {record?.unsent && (
          <p className={record.unsent.error ? 'notice warn small' : 'notice small'}>
            {record.unsent.error
              ? t('The server didn’t take this record: {error}. Change it and save, or delete it.', { error: record.unsent.error })
              : t('Saved on this phone. It’s sent when you’re online.')}
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
            inputMode={quick ? 'none' : exp === 0 ? 'numeric' : 'decimal'}
            autoComplete="off"
            placeholder="0"
            // Grows with the number, so the currency sign stays beside it.
            size={Math.max(amount.length, 1)}
            required
            data-autofocus={!record || undefined}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        {kind !== 'transfer' && (
          <fieldset className="category-pick">
            <legend className="sr-only">{t('Category')}</legend>
            {shownCategories.map((c) => (
              <label key={c.id} className={categoryId === c.id ? 'on' : ''}>
                <input
                  type="radio"
                  name="category"
                  checked={categoryId === c.id}
                  onChange={() => chooseCategory(c.id)}
                  // Tapping the chosen category again clears it.
                  onClick={() => categoryId === c.id && setCategoryId(null)}
                />
                <CategoryIcon icon={c.icon} name={c.name} color={c.color} />
                <span className="category-name">{c.name}</span>
              </label>
            ))}
            {folding && (
              <button type="button" className="category-more" aria-expanded={allCategories} onClick={() => setAllCategories(!allCategories)}>
                <span className="cat-icon" aria-hidden="true">
                  <Icon d={allCategories ? icons.close : icons.more} className="glyph" />
                </span>
                <span className="category-name">{allCategories ? t('Fewer') : t('More')}</span>
              </button>
            )}
          </fieldset>
        )}
        <div className="details">
          {kind === 'transfer' ? (
            <>
              {accountPick(t('From'), 'from', accountId, setAccountId)}
              {accountPick(t('To'), 'to', toAccountId, setToAccountId)}
            </>
          ) : (
            <label className="detail">
              <span>{kind === 'income' ? t('From whom') : t('Payee')}</span>
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
          {recentPayees.length > 0 && kind !== 'transfer' && (
            <div className="detail payee-suggest">
              <span className="sr-only">{t('Recently paid')}</span>
              <ul className="chips" aria-label={t('Recently paid')}>
                {recentPayees.map((p) => (
                  <li key={p}>
                    <button type="button" className="chip" onClick={() => changePayee(p)}>
                      {p}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {kind !== 'transfer' && accountChips}
          {editingWhen ? (
            <label className="detail">
              <span>{t('Date')}</span>
              <input
                type="datetime-local"
                required
                autoFocus
                value={when}
                onChange={(e) => setWhen(e.target.value)}
                ref={(el) => {
                  // Straight to the phone's picker, where it can.
                  if (el && touch) {
                    try {
                      el.showPicker();
                    } catch {
                      /* not allowed here: the field is there to tap */
                    }
                  }
                }}
              />
            </label>
          ) : (
            <div className="detail" role="group" aria-labelledby="record-date">
              <span id="record-date">{t('Date')}</span>
              <button type="button" className="chip when" onClick={() => setEditingWhen(true)}>
                {when ? `${formatDay(new Date(when))}, ${formatTime(new Date(when))}` : t('Date')}
              </button>
            </div>
          )}
          {showExtras ? (
            <>
              <label className="detail">
                <span>{t('Note')}</span>
                <input value={note} maxLength={500} placeholder={t('Optional')} onChange={(e) => setNote(e.target.value)} />
              </label>
              <div className="detail">
                <label htmlFor="record-tags">{t('Tags')}</label>
                <TagField id="record-tags" tags={tags} onTags={setTags} text={tagText} onText={setTagText} suggestions={knownTags} />
              </div>
            </>
          ) : (
            <div className="detail">
              <span>{t('More')}</span>
              <button type="button" className="chip when" onClick={() => setExtras(true)}>
                {t('Add a note or tags')}
              </button>
            </div>
          )}
        </div>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {pad ? (
          <NumberPad decimal={exp > 0} onPress={press} busy={busy} />
        ) : (
        <div className="row end dialog-actions">
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
        )}
      </form>
    </Dialog>
  );
}

/** Fields that bring up the phone's keyboard: everything but the amount and the choices. */
function isTextField(el: EventTarget | null): boolean {
  return (
    el instanceof HTMLElement &&
    el.matches('input:not([type=radio]):not([type=checkbox]), textarea') &&
    !el.matches('.amount-field input')
  );
}

/**
 * The number pad, docked at the bottom of the sheet like the phone's own
 * keyboard, with Save beside the digits, so a record is a few taps of one
 * thumb: amount, category, Save.
 */
function NumberPad({ decimal, onPress, busy }: { decimal: boolean; onPress: (key: string) => void; busy: boolean }) {
  const key = (k: string, className = 'key') => (
    <button
      key={k}
      type="button"
      className={className}
      // Keep the amount focused, so its caret stays where the digits go.
      onPointerDown={(e) => e.preventDefault()}
      onClick={() => onPress(k)}
      aria-label={k === 'back' ? t('Delete last digit') : undefined}
    >
      {k === 'back' ? <Icon d={icons.backspace} className="glyph" /> : k}
    </button>
  );
  return (
    <div className="number-pad" role="group" aria-label={t('Number pad')}>
      {['1', '2', '3'].map((k) => key(k))}
      {key('back', 'key fn')}
      {['4', '5', '6'].map((k) => key(k))}
      <button type="submit" className="key save" disabled={busy}>
        {t('Save')}
      </button>
      {['7', '8', '9'].map((k) => key(k))}
      {key('0', 'key zero')}
      {/* Yen amounts are mostly round thousands; other currencies need cents. */}
      {key(decimal ? '.' : '000')}
    </div>
  );
}

/** "12000.5" → "12,000.5": thousands marked as the amount is tapped in (parseAmount drops the commas). */
function grouped(plain: string): string {
  const [whole, frac] = plain.split('.');
  const w = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return frac === undefined ? w : `${w}.${frac}`;
}
