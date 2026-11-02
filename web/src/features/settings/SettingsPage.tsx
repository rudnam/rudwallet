// Settings: a short menu; each entry opens its own page (?section=…), with
// a way back. Payment notifications, what they learned, Claude, accounts,
// categories, currency, and import.
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { api, errorMessage, type Me, type Account, type AccountKind, type Category, type ImportResult, type PayPayPreview, type BudgetBakersPreview, type MUFGPreview, type MUFGResult, type MUFGRole, type MUFGBalance } from '../../api';
import { CategoryIcon, glyphKey, glyphs, Icon, icons, Plus } from '../../components/Icon';
import { Dialog } from '../../components/Dialog';
import { amountInput, parseAmount } from '../../money';
import { href, type Route } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { WidgetSettings } from './WidgetSettings';
import { NotificationSettings } from './NotificationSettings';
import { ClaudeSettings } from './ClaudeSettings';
import { LanguageSettings } from './LanguageSettings';
import { RulesSettings } from './RulesSettings';
import { serverText, t, tn, tx, type Key } from '../../i18n';
import { language, languages, locale } from '../../language';
import { ImportCategories, categoryChoices, suggestedChoices, type Choices } from './ImportCategories';

/** Each kind of account: what to call it, its icon and colour, and names people often use (pass both through t). */
export const accountKinds: Record<AccountKind, { label: Key; glyph: string; color: string; names: Key[] }> = {
  bank: { label: 'Bank account', glyph: 'bank', color: '#3b6ea8', names: ['MUFG', 'SMBC', 'Mizuho', 'Rakuten Bank', 'Japan Post Bank'] },
  credit_card: { label: 'Credit card', glyph: 'card', color: '#c8463d', names: ['PayPay Card', 'Rakuten Card', 'Amex', 'SMBC Card'] },
  e_money: { label: 'E-money', glyph: 'phone', color: '#d4553f', names: ['PayPay', 'Suica', 'PASMO', 'ICOCA', 'nanaco'] },
  cash: { label: 'Cash', glyph: 'money', color: '#4f8a4c', names: ['Cash'] },
  savings: { label: 'Savings', glyph: 'savings', color: '#b08a3e', names: ['Savings', 'Emergency fund'] },
  other: { label: 'Other', glyph: 'other', color: '#6f6c63', names: [] },
};

const swatches = ['#c8463d', '#d97757', '#b08a3e', '#4f8a4c', '#2a8a8a', '#3b6ea8', '#5b4a8a', '#a8508a', '#6f6c63'];

const sections: { id: string; label: Key; title: Key; glyph: string }[] = [
  { id: 'notifications', label: 'Payment notifications', title: 'Payment notifications', glyph: 'card' },
  { id: 'rules', label: 'Shops and cards', title: 'Shops and cards', glyph: 'shopping' },
  { id: 'claude', label: 'Claude', title: 'Claude', glyph: 'spark' },
  { id: 'accounts', label: 'Accounts', title: 'Accounts', glyph: 'bank' },
  { id: 'categories', label: 'Categories', title: 'Categories', glyph: 'other' },
  { id: 'general', label: 'Currency', title: 'Currency', glyph: 'money' },
  { id: 'import', label: 'Import', title: 'Import records', glyph: 'school' },
  { id: 'widgets', label: 'Home screen widgets', title: 'Home screen widgets', glyph: 'phone' },
  { id: 'language', label: 'Language', title: 'Language', glyph: 'globe' },
];

export function SettingsPage({
  route,
  me,
  onSignOut,
  onDeleted,
}: {
  route: Route;
  me: Me;
  onSignOut: () => void;
  onDeleted: () => void;
}) {
  const [deleting, setDeleting] = useState(false);
  const w = useWallet();
  const open = sections.find((s) => s.id === route.params.get('section'));

  if (open) {
    return (
      <section className="stack loose">
        <header className="stack tight">
          <a className="back" href={href('settings')}>
            <Icon d="M15 5l-7 7 7 7" className="inline-icon" /> {t('Settings')}
          </a>
          <h1>{t(open.title)}</h1>
        </header>
        {open.id === 'notifications' && <NotificationSettings />}
        {open.id === 'rules' && <RulesSettings />}
        {open.id === 'claude' && <ClaudeSettings />}
        {open.id === 'accounts' && <Accounts />}
        {open.id === 'categories' && <Categories />}
        {open.id === 'general' && <General />}
        {open.id === 'import' && <Import />}
        {open.id === 'widgets' && <WidgetSettings />}
        {open.id === 'language' && <LanguageSettings />}
      </section>
    );
  }

  const active = w.accounts.filter((a) => !a.archived).length;
  const summary: Record<string, string> = {
    notifications: w.settings.auto_confirm ? t('Auto-confirm on') : '',
    rules: t('What was learned'),
    claude: t('Connect apps'),
    accounts: tn(active, '{n} account', '{n} accounts'),
    categories: tn(w.categories.length, '{n} category', '{n} categories'),
    general: w.settings.currency,
    import: 'PayPay, MUFG, BudgetBakers',
    widgets: 'Android',
    language: languages.find((l) => l.code === language())!.name,
  };
  return (
    <section className="stack loose">
      <h1>{t('Settings')}</h1>
      <nav className="card flush" aria-label={t('Settings sections')}>
        <ul className="list flush">
          {sections.map((s) => (
            <li key={s.id}>
              <a className="menu-row" href={href('settings', { section: s.id })}>
                <span className="cat-icon" aria-hidden="true">
                  <Icon d={s.glyph in icons ? icons[s.glyph as keyof typeof icons] : glyphs[s.glyph].d} className="glyph" />
                </span>
                <span className="grow">{t(s.label)}</span>
                <span className="muted small">{summary[s.id]}</span>
                <Icon d="M9 5l7 7-7 7" className="inline-icon chev-icon" />
              </a>
            </li>
          ))}
        </ul>
      </nav>
      {me.demo ? (
        // Ending a demo deletes it.
        <div className="card me">
          <span className="grow">
            <span className="muted small block">{t('Demo')}</span>
            {t('Sample data, deleted within a day')}
          </span>
          <button type="button" className="secondary" onClick={onSignOut}>
            {t('End demo')}
          </button>
        </div>
      ) : (
        <>
          <div className="card me">
            <span className="avatar" aria-hidden="true">
              {me.email[0]}
            </span>
            <span className="grow">
              <span className="muted small block">{t('Signed in as')}</span>
              {me.email}
            </span>
            <button type="button" className="secondary" onClick={onSignOut}>
              {t('Sign out')}
            </button>
          </div>
          <div className="row end">
            <button type="button" className="link danger" onClick={() => setDeleting(true)}>
              {t('Delete account')}
            </button>
          </div>
        </>
      )}
      {deleting && <DeleteAccount me={me} onClose={() => setDeleting(false)} onDeleted={onDeleted} />}
    </section>
  );
}

/**
 * Deleting the account: says plainly what goes, and asks for the password
 * (or, for an account that signs in with Google, the email typed out).
 */
function DeleteAccount({ me, onClose, onDeleted }: { me: Me; onClose: () => void; onDeleted: () => void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.deleteMe(me.has_password ? { password: value } : { email: value });
      onDeleted();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <Dialog title={t('Delete account')} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="muted">
          {tx("This deletes {email} and everything in it: accounts, records, budgets and categories. It can't be undone.", {
            email: <strong>{me.email}</strong>,
          })}
        </p>
        <label className="field">
          {me.has_password ? t('Your password') : t('Type your email to confirm')}
          <input
            type={me.has_password ? 'password' : 'email'}
            autoComplete={me.has_password ? 'current-password' : 'off'}
            placeholder={me.has_password ? undefined : me.email}
            required
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          <button type="button" className="link" onClick={onClose}>
            {t('Cancel')}
          </button>
          <button type="submit" className="danger" disabled={busy}>
            {t('Delete everything')}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/** A section's toolbar: a sentence about it and its main action. */
function Intro({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="intro">
      <p className="muted lede grow">{children}</p>
      {action}
    </div>
  );
}

// ---------- Accounts ----------

function Accounts() {
  const w = useWallet();
  const [editing, setEditing] = useState<Account | 'new' | null>(null);
  return (
    <>
      <Intro
        action={
          <button type="button" onClick={() => setEditing('new')}>
            {t('Add account')}
          </button>
        }
      >
        {t('Everywhere your money lives. Tap one to change it.')}
      </Intro>
      <ul className="list card flush" aria-label={t('Accounts')}>
        {w.accounts.map((a) => (
          <li key={a.id}>
            <button type="button" className="record" aria-label={t('Edit account {name}', { name: a.name })} onClick={() => setEditing(a)}>
              <CategoryIcon icon={accountKinds[a.kind].glyph} color={a.color} />
              <span className="grow">
                <span className="record-title">
                  {a.name} {a.archived && <span className="badge quiet">{t('archived')}</span>}
                </span>
                <span className="muted small">{t(accountKinds[a.kind].label)}</span>
              </span>
              <span className="amount">{w.money(a.balance)}</span>
            </button>
          </li>
        ))}
      </ul>
      {editing && <AccountForm account={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

/**
 * Count what an account really holds (the cash in a wallet) and record the
 * difference from the app's balance as one record.
 */
function ReconcileForm({ account, onDone }: { account: Account; onDone: () => void }) {
  const w = useWallet();
  const toast = useToast();
  const exp = w.settings.currency_exponent;
  const [counted, setCounted] = useState('');
  const [error, setError] = useState('');

  async function reconcile(e: FormEvent) {
    e.preventDefault();
    const text = counted.trim();
    const minor = parseAmount(text.replace(/^-/, ''), exp);
    if (!text || minor === null) {
      setError(t('Enter the balance as a number, like 12000.'));
      return;
    }
    try {
      const res = await api.reconcileAccount(account.id, text.startsWith('-') ? -minor : minor);
      await w.refresh();
      toast(res.transaction ? t('Recorded {amount} as untracked', { amount: w.money(res.transaction.amount) }) : t('The balance already matches'));
      onDone();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <form className="stack" onSubmit={reconcile}>
      <label className="field">
        {t('What it really holds ({currency})', { currency: w.settings.currency })}
        <input inputMode="decimal" placeholder={amountInput(Math.max(account.balance, 0), exp)} value={counted} onChange={(e) => setCounted(e.target.value)} />
        <span className="muted small">{t('The app has {balance}. The difference is recorded as one untracked expense (or income).', { balance: w.money(account.balance) })}</span>
      </label>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="row end">
        <button type="submit" className="secondary">
          {t('Reconcile')}
        </button>
      </div>
    </form>
  );
}

/**
 * Add or edit an account: pick its kind, name it (with suggestions), and say
 * what's in it, or for a card, what's owed on it, which is stored as negative.
 */
export function AccountForm({ account, onClose }: { account?: Account; onClose: () => void }) {
  const w = useWallet();
  const toast = useToast();
  const exp = w.settings.currency_exponent;
  const [kind, setKind] = useState<AccountKind | null>(account?.kind ?? null);
  const [name, setName] = useState(account?.name ?? '');
  const card = kind === 'credit_card';
  // Cards are entered as what's owed: -¥12,000 shows as "12000".
  const shown = account ? (account.kind === 'credit_card' ? -account.initial_balance : account.initial_balance) : 0;
  const [balance, setBalance] = useState(account ? (shown < 0 ? '-' : '') + amountInput(Math.abs(shown), exp) : '');
  const [color, setColor] = useState(account?.color ?? '');
  const [archived, setArchived] = useState(account?.archived ?? false);
  const [error, setError] = useState('');
  const meta = kind ? accountKinds[kind] : null;

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!kind || !meta) {
      setError(t('Pick what kind of account it is.'));
      return;
    }
    const text = balance.trim() || '0';
    const minor = parseAmount(text.replace(/^-/, ''), exp);
    if (minor === null) {
      setError(t('Enter the balance as a number, like 12000.'));
      return;
    }
    const signed = (text.startsWith('-') ? -minor : minor) * (card ? -1 : 1);
    const input = { name: name.trim() || t(meta.label), kind, initial_balance: signed, color: color || meta.color, archived };
    try {
      if (account) await api.updateAccount(account.id, input);
      else await api.createAccount(input);
      await w.refresh();
      toast(account ? t('Saved') : t('Added {name}', { name: input.name }));
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!account || !confirm(t("Delete \"{name}\" and every record in it? This can't be undone.", { name: account.name }))) return;
    try {
      await api.deleteAccount(account.id);
      await w.refresh();
      toast(t('Deleted'));
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const cur = w.settings.currency;
  const balanceLabel = t(
    account ? (card ? 'Owed at the start ({currency})' : 'Starting balance ({currency})') : card ? 'Owed today ({currency})' : 'Balance today ({currency})',
    { currency: cur },
  );

  return (
    <Dialog title={account ? t('Edit account') : t('New account')} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <fieldset className="chip-pick">
          <legend>{account ? t('Type') : t('What kind of account?')}</legend>
          <div className="kind-pick">
            {(Object.keys(accountKinds) as AccountKind[]).map((k) => (
              <label key={k} className={kind === k ? 'on' : ''}>
                <input type="radio" name="kind" checked={kind === k} onChange={() => setKind(k)} />
                <Icon d={glyphs[accountKinds[k].glyph].d} className="glyph" />
                {t(accountKinds[k].label)}
              </label>
            ))}
          </div>
        </fieldset>
        {meta && (
          <>
            <label className="field">
              {t('Name')}
              <input
                maxLength={60}
                value={name}
                placeholder={t(meta.names[0] ?? meta.label)}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            {!account && meta.names.length > 0 && (
              <div className="chips" aria-label={t('Suggested names')}>
                {meta.names.map((key) => {
                  const n = t(key);
                  return (
                    <button key={key} type="button" className={name === n ? 'chip on' : 'chip'} onClick={() => setName(n)}>
                      {n}
                    </button>
                  );
                })}
              </div>
            )}
            <label className="field">
              {balanceLabel}
              <input inputMode="decimal" placeholder="0" value={balance} onChange={(e) => setBalance(e.target.value)} />
              <span className="muted small">
                {account
                  ? t('Before any records in it. Records change the balance from there.')
                  : card
                    ? t("What you haven't paid off yet. Payments you record add to it.")
                    : t('Records you add from now on change it.')}
              </span>
            </label>
            <fieldset className="chip-pick">
              <legend>{t('Colour')}</legend>
              <div className="swatch-pick">
                {swatches.map((c) => (
                  <label key={c} className={(color || meta.color) === c ? 'on' : ''} style={{ background: c }}>
                    <input type="radio" name="color" aria-label={c} checked={(color || meta.color) === c} onChange={() => setColor(c)} />
                  </label>
                ))}
                <label
                  className={swatches.includes(color || meta.color) ? 'custom' : 'custom on'}
                  title={t('Any colour')}
                  style={swatches.includes(color || meta.color) ? undefined : { background: color || meta.color }}
                >
                  <input type="color" aria-label={t('Any colour')} value={color || meta.color} onChange={(e) => setColor(e.target.value)} />
                </label>
              </div>
            </fieldset>
            {account && (
              <label className="check">
                <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
                {t('Archived (hidden from the overview and new records)')}
              </label>
            )}
          </>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          {account && (
            <button type="button" className="link danger" onClick={remove}>
              {t('Delete')}
            </button>
          )}
          <span className="grow" />
          <button type="submit" disabled={!kind}>
            {account ? t('Save') : t('Add account')}
          </button>
        </div>
      </form>
      {account && !account.archived && <ReconcileForm account={account} onDone={onClose} />}
    </Dialog>
  );
}

// ---------- Categories ----------

function Categories() {
  const w = useWallet();
  const [editing, setEditing] = useState<Category | 'new' | null>(null);
  const [missing, setMissing] = useState<Category[]>([]);
  const [error, setError] = useState('');
  const names = w.categories.map((c) => `${c.kind}:${c.name}`).join('|');
  useEffect(() => {
    api.missingCategories().then(setMissing, () => setMissing([]));
  }, [names]);

  async function add(c: Category) {
    setError('');
    try {
      await api.createCategory({ name: c.name, kind: c.kind, icon: c.icon, color: c.color });
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    }
  }
  return (
    <>
      <Intro
        action={
          <button type="button" onClick={() => setEditing('new')}>
            {t('Add category')}
          </button>
        }
      >
        {t('What you spend on and earn from. Tap one to rename it or change its icon.')}
      </Intro>
      <div className="card stack">
      {(['expense', 'income'] as const).map((kind) => (
        <div key={kind} className="stack tight">
          <span className="muted small">{kind === 'expense' ? t('Expenses') : t('Income')}</span>
          <div className="chips">
            {w.categories
              .filter((c) => c.kind === kind)
              .map((c) => (
                <button key={c.id} type="button" className="chip" aria-label={t('Edit category {name}', { name: c.name })} onClick={() => setEditing(c)}>
                  <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" /> {c.name}
                </button>
              ))}
          </div>
        </div>
      ))}
      {missing.length > 0 && (
        <div className="stack tight">
          <span className="muted small">{t('More categories')}</span>
          <div className="chips">
            {missing.map((c) => (
              <button key={`${c.kind}:${c.name}`} type="button" className="chip quiet" aria-label={t('Add category {name}', { name: c.name })} onClick={() => add(c)}>
                <Plus /> {c.name}
              </button>
            ))}
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="error small">
          {error}
        </p>
      )}
      </div>
      {editing && <CategoryForm category={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function CategoryForm({ category, onClose }: { category?: Category; onClose: () => void }) {
  const w = useWallet();
  const [name, setName] = useState(category?.name ?? '');
  const [kind, setKind] = useState(category?.kind ?? 'expense');
  const [icon, setIcon] = useState(glyphKey(category?.icon) ?? '');
  const [color, setColor] = useState(category?.color ?? '#66706b');
  const [error, setError] = useState('');

  async function save(e: FormEvent) {
    e.preventDefault();
    try {
      if (category) await api.updateCategory(category.id, { name, kind, icon, color });
      else await api.createCategory({ name, kind, icon, color });
      await w.refresh();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!category || !confirm(t('Delete "{name}"? Its records become uncategorised, and its budgets and the shops filed under it are forgotten.', { name: category.name }))) return;
    try {
      await api.deleteCategory(category.id);
      await w.refresh();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Dialog title={category ? t('Edit category') : t('New category')} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <label className="field">
          {t('Name')}
          <input required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        {!category && (
          <label className="field">
            {t('Type')}
            <select value={kind} onChange={(e) => setKind(e.target.value as Category['kind'])}>
              <option value="expense">{t('Expense')}</option>
              <option value="income">{t('Income')}</option>
            </select>
          </label>
        )}
        <div className="grid2">
          <label className="field">
            {t('Colour')}
            <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
          </label>
          <div className="field">
            {t('Preview')}
            <span className="row">
              <CategoryIcon icon={icon} name={name || '?'} color={color} />
              <span className="ellipsis">{name}</span>
            </span>
          </div>
        </div>
        <fieldset className="chip-pick">
          <legend>{t('Icon')}</legend>
          <div className="icon-pick">
            {[['', t('Initial only')], ...Object.entries(glyphs).map(([k, g]) => [k, t(g.label)])].map(([k, label]) => (
              <label key={k} className={icon === k ? 'on' : ''} title={label}>
                <input type="radio" name="icon" aria-label={label} checked={icon === k} onChange={() => setIcon(k)} />
                {k ? <Icon d={glyphs[k].d} className="glyph" /> : <span className="initial">{(name || 'A')[0]}</span>}
              </label>
            ))}
          </div>
        </fieldset>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="row end">
          {category && (
            <button type="button" className="link danger" onClick={remove}>
              {t('Delete')}
            </button>
          )}
          <span className="grow" />
          <button type="submit">{t('Save')}</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- General ----------

const currencies = ['JPY', 'USD', 'EUR', 'GBP', 'PHP', 'KRW', 'CNY', 'TWD', 'AUD', 'CAD', 'SGD', 'HKD', 'THB', 'INR'];

function General() {
  const w = useWallet();
  const [error, setError] = useState('');
  async function setCurrency(currency: string) {
    setError('');
    try {
      await api.updateSettings({ ...w.settings, currency });
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    }
  }
  return (
    <div className="card stack">
      <label className="field">
        {t('Main currency')}
        <select value={w.settings.currency} onChange={(e) => setCurrency(e.target.value)}>
          {(currencies.includes(w.settings.currency) ? currencies : [w.settings.currency, ...currencies]).map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
      </label>
      <p className="muted small">{t("Pick it before adding records; it can't change afterwards.")}</p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}

// ---------- Import ----------

function Import() {
  return (
    <>
      <PayPayImport />
      <MUFGImport />
      <BudgetBakersImport />
    </>
  );
}

/** What an import did, in one sentence. */
function ImportSummary({ result }: { result: ImportResult }) {
  return (
    <div className="notice" role="status">
      {[
        tn(result.imported, 'Imported {n} record', 'Imported {n} records'),
        result.duplicates > 0 && t('skipped {n} already imported', { n: result.duplicates }),
        result.matched > 0 && t('skipped {n} already recorded from notifications or by hand', { n: result.matched }),
        result.accounts_created > 0 && tn(result.accounts_created, 'created {n} account', 'created {n} accounts'),
        result.categories_created > 0 && tn(result.categories_created, 'created {n} category', 'created {n} categories'),
      ]
        .filter(Boolean)
        .join(t(', '))}
      {t('.')}
      {result.imported > 0 && (
        <>
          {' '}
          {tx('Records without a category can be {sorted}.', { sorted: <a href={href('settings', { section: 'claude' })}>{t('sorted by Claude')}</a> })}
        </>
      )}
      {result.problems.length > 0 && (
        <ul className="small">
          {result.problems.map((p) => (
            <li key={p}>{serverText(p)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** English names for the ways of paying PayPay lists most often. */
const payPayMethodNames: Record<string, string> = {
  PayPay残高: 'PayPay balance',
  PayPayカード: 'PayPay Card',
  PayPayクレジット: 'PayPay Credit',
  PayPayポイント: 'PayPay Points',
};

const shortDate = (iso: string) => new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'short', day: 'numeric' });

/**
 * PayPay's transaction history: the file is read first, then each way of
 * paying in it (balance, card, a bank for charges) gets an account, with
 * the app's best guess picked, before anything is recorded.
 */
function PayPayImport() {
  const w = useWallet();
  const [csv, setCsv] = useState('');
  const [preview, setPreview] = useState<PayPayPreview | null>(null);
  const [choice, setChoice] = useState<Record<string, number>>({});
  const [cats, setCats] = useState<Choices>({});
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [fileKey, setFileKey] = useState(0);

  function reset() {
    setCsv('');
    setPreview(null);
    setFileKey((k) => k + 1);
  }

  async function read(file: File) {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const text = await file.text();
      const p = await api.previewPayPay(text);
      setCsv(text);
      setPreview(p);
      setChoice(Object.fromEntries(p.methods.map((m) => [m.method, m.account_id ?? 0])));
      setCats(suggestedChoices(p.categories));
    } catch (err) {
      setError(errorMessage(err));
      setFileKey((k) => k + 1);
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      setResult(await api.importPayPay(csv, choice, categoryChoices(preview?.categories ?? [], cats)));
      reset();
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const accounts = w.accounts.filter((a) => !a.archived || Object.values(choice).includes(a.id));
  return (
    <div className="card stack">
      <h2>PayPay</h2>
      <p className="small muted">
        {t(
          'In the PayPay app, open 取引履歴 (transaction history), tap the download button, pick a period of up to a year, and save the CSV file. Payments already recorded from their emails are skipped, and importing an overlapping period adds each payment once.',
        )}
      </p>
      {!preview && (
        <label className="field">
          {t('PayPay CSV file')}
          <input key={fileKey} type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
        </label>
      )}
      {preview && preview.records === 0 && (
        <>
          <p>{t('There are no payments in this file.')}</p>
          <div className="row end">
            <button type="button" className="secondary" onClick={reset}>
              {t('Choose another file')}
            </button>
          </div>
        </>
      )}
      {preview && preview.records > 0 && (
        <form className="stack" onSubmit={submit}>
          <p>
            {!preview.from || !preview.to
              ? tn(preview.records, '{n} payment. Choose where each way of paying is recorded:', '{n} payments. Choose where each way of paying is recorded:')
              : shortDate(preview.from) === shortDate(preview.to)
                ? tn(preview.records, '{n} payment on {day}. Choose where each way of paying is recorded:', '{n} payments on {day}. Choose where each way of paying is recorded:', {
                    day: shortDate(preview.from),
                  })
                : tn(preview.records, '{n} payment, {from} to {to}. Choose where each way of paying is recorded:', '{n} payments, {from} to {to}. Choose where each way of paying is recorded:', {
                    from: shortDate(preview.from),
                    to: shortDate(preview.to),
                  })}
          </p>
          {preview.methods.map((m) => (
            <label key={m.method} className="field">
              <span>
                {m.method}
                {language() === 'en' && payPayMethodNames[m.method] && <span className="muted"> · {payPayMethodNames[m.method]}</span>}
                <span className="muted small"> · {tn(m.count, '{n} record', '{n} records')}</span>
              </span>
              <select value={choice[m.method]} onChange={(e) => setChoice({ ...choice, [m.method]: Number(e.target.value) })}>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
                {!accounts.some((a) => a.name.toLowerCase() === m.new_name.toLowerCase()) && (
                  <option value={0}>{t('New account: {name}', { name: m.new_name })}</option>
                )}
              </select>
            </label>
          ))}
          {preview.categories.length > 0 && (
            <>
              <p>{t("Choose a category for each shop. A shop's category is remembered, so its next payments are sorted too.")}</p>
              <ImportCategories groups={preview.categories} newCategories={preview.new_categories} choices={cats} onChange={setCats} by="shop" />
            </>
          )}
          {preview.problems.length > 0 && (
            <div className="notice warn small">
              {t("These rows can't be read and will be left out:")}
              <ul>
                {preview.problems.map((p) => (
                  <li key={p}>{serverText(p)}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="row end">
            <button type="button" className="secondary" onClick={reset} disabled={busy}>
              {t('Cancel')}
            </button>
            <button type="submit" disabled={busy}>
              {busy ? t('Importing…') : tn(preview.records, 'Import {n} payment', 'Import {n} payments')}
            </button>
          </div>
        </form>
      )}
      {busy && !preview && <p className="muted">{t('Reading the file…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && <ImportSummary result={result} />}
    </div>
  );
}

/** What each role in an MUFG statement is, for its account picker. */
const mufgRoles: Record<MUFGRole, Key> = {
  bank: 'The MUFG account this statement is for',
  cash: 'Cash (ATM withdrawals)',
  'paypay-card': 'PayPay Card (monthly payment)',
  'smbc-card': 'SMBC card (monthly payment)',
  paypay: 'PayPay balance (charges)',
};

/** Reads a bank's CSV file, which is Shift_JIS unless it was saved again as UTF-8. */
async function readJapaneseText(file: File): Promise<string> {
  const bytes = await file.arrayBuffer();
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder('shift_jis').decode(bytes);
  }
}

/**
 * The bank account's balance against the statement's, and how they come to
 * agree: by the import changing the starting balance (match), or by hand;
 * rows recorded in another account have to be moved either way.
 */
function MUFGBalanceNote({ balance, match, done }: { balance: MUFGBalance; match: boolean; done: boolean }) {
  const w = useWallet();
  if (balance.account_id === null) return null;
  const bank = w.accounts.find((a) => a.id === balance.account_id)?.name ?? '';
  const adjust = balance.difference - balance.misplaced;
  return (
    <div className={balance.difference === 0 ? 'notice' : 'notice warn'} role="status">
      <p>
        {t('Statement balance on {day}: {closing}. The account in the app: {app}.', {
          day: shortDate(balance.to),
          closing: w.money(balance.closing),
          app: w.money(balance.app),
        })}{' '}
        {balance.difference === 0 && t('They match.')}
      </p>
      {done && balance.adjusted !== 0 && (
        <p>{t("{bank}'s starting balance was changed by {amount}, to {initial}, so it matches the statement.", { bank, amount: w.money(balance.adjusted, true), initial: w.money(balance.initial_balance) })}</p>
      )}
      {!done && adjust !== 0 &&
        (match ? (
          <p>{t("Importing changes {bank}'s starting balance by {amount}, to {suggested}, for the records before {from} that the app doesn't have.", { bank, amount: w.money(adjust, true), suggested: w.money(balance.suggested_initial_balance), from: shortDate(balance.from) })}</p>
        ) : (
          <p>{t("They're {difference} apart. If the account's records before {from} are incomplete, set its starting balance to {suggested} (Settings › Accounts). Otherwise, look for a record in the wrong account.", { difference: w.money(Math.abs(adjust)), from: shortDate(balance.from), suggested: w.money(balance.suggested_initial_balance) })}</p>
        ))}
      {balance.misplaced !== 0 && <p>{t('Rows worth {amount} are already recorded in another account (marked below). Move those records to {bank} to match.', { amount: w.money(balance.misplaced, true), bank })}</p>}
    </div>
  );
}

/**
 * MUFG's statement (入出金明細): the file is read first, and every row is
 * shown with how it would be recorded, with an account for each role (the
 * bank, cash, the cards it pays) and a category for each payee to choose,
 * before anything is recorded.
 */
function MUFGImport() {
  const w = useWallet();
  const [csv, setCsv] = useState('');
  const [preview, setPreview] = useState<MUFGPreview | null>(null);
  const [choice, setChoice] = useState<Record<string, number>>({});
  const [cats, setCats] = useState<Choices>({});
  const [result, setResult] = useState<MUFGResult | null>(null);
  const [match, setMatch] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [fileKey, setFileKey] = useState(0);

  function reset() {
    setCsv('');
    setPreview(null);
    setFileKey((k) => k + 1);
  }

  async function read(file: File) {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const text = await readJapaneseText(file);
      const p = await api.previewMUFG(text);
      setCsv(text);
      setPreview(p);
      setChoice(Object.fromEntries(p.accounts.map((a) => [a.role, a.account_id ?? 0])));
      setCats(suggestedChoices(p.categories));
    } catch (err) {
      setError(errorMessage(err));
      setFileKey((k) => k + 1);
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      setResult(await api.importMUFG(csv, choice, categoryChoices(preview?.categories ?? [], cats), match));
      reset();
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const name = (id: number | undefined) => w.accounts.find((a) => a.id === id)?.name ?? '';
  const roleName = (role: MUFGRole | undefined) => {
    const a = preview?.accounts.find((x) => x.role === role);
    return a ? name(choice[a.role]) || a.new_name : '';
  };
  const accounts = w.accounts.filter((a) => !a.archived || Object.values(choice).includes(a.id));
  return (
    <div className="card stack">
      <h2>MUFG</h2>
      <p className="small muted">
        {t(
          'In 三菱UFJダイレクト, open 入出金明細, pick the period, and download the CSV file. Rows already imported, or already recorded from notifications, are skipped.',
        )}
      </p>
      {!preview && (
        <label className="field">
          {t('MUFG CSV file')}
          <input key={fileKey} type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
        </label>
      )}
      {preview && (
        <form className="stack" onSubmit={submit}>
          <p>
            {tn(preview.records, '{n} row, {from} to {to}. Choose the accounts:', '{n} rows, {from} to {to}. Choose the accounts:', {
              from: shortDate(preview.balance.from),
              to: shortDate(preview.balance.to),
            })}
          </p>
          {preview.accounts.map((a) => (
            <label key={a.role} className="field">
              <span>
                {t(mufgRoles[a.role])}
                <span className="muted small"> · {tn(a.count, '{n} record', '{n} records')}</span>
              </span>
              <select value={choice[a.role]} onChange={(e) => setChoice({ ...choice, [a.role]: Number(e.target.value) })}>
                {accounts.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.name}
                  </option>
                ))}
                {!accounts.some((x) => x.name.toLowerCase() === a.new_name.toLowerCase()) && <option value={0}>{t('New account: {name}', { name: a.new_name })}</option>}
              </select>
              {a.candidates.length > 0 && (
                <span className="muted small">{t('Also looks like this account: {names}', { names: a.candidates.map(name).join(t(', ')) })}</span>
              )}
            </label>
          ))}
          {preview.categories.length > 0 && (
            <>
              <p>{t('Choose a category for each payee, such as your employer for salary. It is remembered for the next statement; payees left without one are imported without a category, to sort later.')}</p>
              <ImportCategories groups={preview.categories} newCategories={preview.new_categories} choices={cats} onChange={setCats} by="shop" />
            </>
          )}
          <div className="table-scroll">
            <table className="small" aria-label={t('Statement rows')}>
              <thead>
                <tr>
                  <th>{t('Date')}</th>
                  <th>{t('Statement')}</th>
                  <th className="amount">{t('Amount')}</th>
                  <th>{t('Recorded as')}</th>
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((r) => (
                  <tr key={r.line} className={r.status === 'new' ? '' : 'muted'}>
                    <td>{shortDate(r.date)}</td>
                    <td>
                      {r.summary} {r.detail}
                    </td>
                    <td className="amount">{w.money(r.in - r.out, true)}</td>
                    <td>
                      {r.status === 'imported'
                        ? t('Already imported')
                        : r.status === 'matched'
                          ? t('Already recorded in {account}', { account: name(r.matched_account_id) })
                          : r.kind === 'transfer'
                            ? t('Transfer: {from} → {to}', { from: roleName(r.from_role), to: roleName(r.to_role) })
                            : r.rule
                              ? t(r.kind === 'expense' ? 'Expense' : 'Income')
                              : t('No rule: needs a category')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <MUFGBalanceNote balance={preview.balance} match={match} done={false} />
          <label className="check">
            <input type="checkbox" checked={match} onChange={(e) => setMatch(e.target.checked)} />
            {t("Update the bank account's balance to match the statement")}
          </label>
          <div className="row end">
            <button type="button" className="secondary" onClick={reset} disabled={busy}>
              {t('Cancel')}
            </button>
            <button type="submit" disabled={busy || preview.new === 0}>
              {busy ? t('Importing…') : tn(preview.new, 'Import {n} row', 'Import {n} rows')}
            </button>
          </div>
        </form>
      )}
      {busy && !preview && <p className="muted">{t('Reading the file…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && (
        <>
          <ImportSummary result={result} />
          <MUFGBalanceNote balance={result.balance} match={match} done />
        </>
      )}
    </div>
  );
}

/**
 * Wallet by BudgetBakers' records export: the file is read first, then its
 * accounts and categories are matched to the user's (or new ones), with the
 * app's best guess picked, before anything is recorded.
 */
function BudgetBakersImport() {
  const w = useWallet();
  const [csv, setCsv] = useState('');
  const [preview, setPreview] = useState<BudgetBakersPreview | null>(null);
  const [choice, setChoice] = useState<Record<string, number>>({});
  const [cats, setCats] = useState<Choices>({});
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [fileKey, setFileKey] = useState(0);

  function reset() {
    setCsv('');
    setPreview(null);
    setFileKey((k) => k + 1);
  }

  async function read(file: File) {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const text = await file.text();
      const p = await api.previewBudgetBakers(text);
      setCsv(text);
      setPreview(p);
      setChoice(Object.fromEntries(p.accounts.map((a) => [a.name, a.account_id ?? 0])));
      setCats(suggestedChoices(p.categories));
    } catch (err) {
      setError(errorMessage(err));
      setFileKey((k) => k + 1);
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      setResult(await api.importBudgetBakers(csv, choice, categoryChoices(preview?.categories ?? [], cats)));
      reset();
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const accounts = w.accounts.filter((a) => !a.archived || Object.values(choice).includes(a.id));
  return (
    <div className="card stack">
      <h2>BudgetBakers</h2>
      <p className="small muted">
        {t(
          "In BudgetBakers' Wallet app, export your records as CSV (on the web app: Records → Export), then choose the file here. You can choose where its accounts and categories go before anything is imported. Importing the same file twice adds nothing new.",
        )}
      </p>
      {!preview && (
        <label className="field">
          {t('CSV file')}
          <input key={fileKey} type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
        </label>
      )}
      {preview && preview.records === 0 && (
        <>
          <p>{t('There are no records in this file.')}</p>
          <div className="row end">
            <button type="button" className="secondary" onClick={reset}>
              {t('Choose another file')}
            </button>
          </div>
        </>
      )}
      {preview && preview.records > 0 && (
        <form className="stack" onSubmit={submit}>
          <p>
            {!preview.from || !preview.to
              ? tn(preview.records, "{n} record. Choose where each account's records go:", "{n} records. Choose where each account's records go:")
              : shortDate(preview.from) === shortDate(preview.to)
                ? tn(preview.records, "{n} record on {day}. Choose where each account's records go:", "{n} records on {day}. Choose where each account's records go:", {
                    day: shortDate(preview.from),
                  })
                : tn(preview.records, "{n} record, {from} to {to}. Choose where each account's records go:", "{n} records, {from} to {to}. Choose where each account's records go:", {
                    from: shortDate(preview.from),
                    to: shortDate(preview.to),
                  })}
          </p>
          {preview.accounts.map((a) => (
            <label key={a.name} className="field">
              <span>
                {a.name}
                <span className="muted small"> · {tn(a.count, '{n} record', '{n} records')}</span>
              </span>
              <select value={choice[a.name]} onChange={(e) => setChoice({ ...choice, [a.name]: Number(e.target.value) })}>
                {accounts.map((acc) => (
                  <option key={acc.id} value={acc.id}>
                    {acc.name}
                  </option>
                ))}
                {!accounts.some((acc) => acc.name.toLowerCase() === a.name.toLowerCase()) && <option value={0}>{t('New account: {name}', { name: a.name })}</option>}
              </select>
            </label>
          ))}
          {preview.categories.length > 0 && (
            <>
              <p>{t("And where each category's records go. Close matches are picked; BudgetBakers' categories that fit none of yours can be added as they are.")}</p>
              <ImportCategories groups={preview.categories} newCategories={preview.new_categories} choices={cats} onChange={setCats} by="category" />
            </>
          )}
          {preview.problems.length > 0 && (
            <div className="notice warn small">
              {t("These rows can't be read and will be left out:")}
              <ul>
                {preview.problems.map((p) => (
                  <li key={p}>{serverText(p)}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="row end">
            <button type="button" className="secondary" onClick={reset} disabled={busy}>
              {t('Cancel')}
            </button>
            <button type="submit" disabled={busy}>
              {busy ? t('Importing…') : tn(preview.records, 'Import {n} record', 'Import {n} records')}
            </button>
          </div>
        </form>
      )}
      {busy && !preview && <p className="muted">{t('Reading the file…')}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && <ImportSummary result={result} />}
    </div>
  );
}
