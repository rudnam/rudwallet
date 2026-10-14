// Settings: a short menu; each entry opens its own page (?section=…), with
// a way back. Email automation, payment notifications, what they learned,
// accounts, categories, currency, and import.
import { useState, type FormEvent, type ReactNode } from 'react';
import { api, errorMessage, type Me, type Account, type AccountKind, type Category, type ImportResult, type PayPayPreview } from '../../api';
import { CategoryIcon, glyphKey, glyphs, Icon, icons } from '../../components/Icon';
import { Dialog } from '../../components/Dialog';
import { amountInput, parseAmount } from '../../money';
import { href, type Route } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { WidgetSettings } from './WidgetSettings';
import { EmailSettings } from './EmailSettings';
import { NotificationSettings } from './NotificationSettings';
import { ClaudeSettings } from './ClaudeSettings';
import { RulesSettings } from './RulesSettings';

/** Each kind of account: what to call it, its icon and colour, and names people often use. */
export const accountKinds: Record<AccountKind, { label: string; glyph: string; color: string; names: string[] }> = {
  bank: { label: 'Bank account', glyph: 'bank', color: '#3b6ea8', names: ['MUFG', 'SMBC', 'Mizuho', 'Rakuten Bank', 'Japan Post Bank'] },
  credit_card: { label: 'Credit card', glyph: 'card', color: '#c8463d', names: ['PayPay Card', 'Rakuten Card', 'Amex', 'SMBC Card'] },
  e_money: { label: 'E-money', glyph: 'phone', color: '#d4553f', names: ['PayPay', 'Suica', 'PASMO', 'ICOCA', 'nanaco'] },
  cash: { label: 'Cash', glyph: 'money', color: '#4f8a4c', names: ['Cash'] },
  savings: { label: 'Savings', glyph: 'savings', color: '#b08a3e', names: ['Savings', 'Emergency fund'] },
  other: { label: 'Other', glyph: 'other', color: '#6f6c63', names: [] },
};

const swatches = ['#c8463d', '#d97757', '#b08a3e', '#4f8a4c', '#2a8a8a', '#3b6ea8', '#5b4a8a', '#a8508a', '#6f6c63'];

const sections: { id: string; label: string; title: string; glyph: string }[] = [
  { id: 'email', label: 'Email automation', title: 'Email automation', glyph: 'mail' },
  { id: 'notifications', label: 'Payment notifications', title: 'Payment notifications', glyph: 'card' },
  { id: 'rules', label: 'Shops and cards', title: 'Shops and cards', glyph: 'shopping' },
  { id: 'claude', label: 'Claude', title: 'Claude', glyph: 'spark' },
  { id: 'accounts', label: 'Accounts', title: 'Accounts', glyph: 'bank' },
  { id: 'categories', label: 'Categories', title: 'Categories', glyph: 'other' },
  { id: 'general', label: 'Currency', title: 'Currency', glyph: 'money' },
  { id: 'import', label: 'Import', title: 'Import records', glyph: 'school' },
  { id: 'widgets', label: 'Home screen widgets', title: 'Home screen widgets', glyph: 'phone' },
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
            <Icon d="M15 5l-7 7 7 7" className="inline-icon" /> Settings
          </a>
          <h1>{open.title}</h1>
        </header>
        {open.id === 'email' && <EmailSettings />}
        {open.id === 'notifications' && <NotificationSettings />}
        {open.id === 'rules' && <RulesSettings />}
        {open.id === 'claude' && <ClaudeSettings />}
        {open.id === 'accounts' && <Accounts />}
        {open.id === 'categories' && <Categories />}
        {open.id === 'general' && <General />}
        {open.id === 'import' && <Import />}
        {open.id === 'widgets' && <WidgetSettings />}
      </section>
    );
  }

  const active = w.accounts.filter((a) => !a.archived).length;
  const summary: Record<string, string> = {
    email: w.settings.auto_confirm ? 'Auto-confirm on' : '',
    rules: 'What was learned',
    claude: 'Sort, connect apps',
    accounts: `${active} ${active === 1 ? 'account' : 'accounts'}`,
    categories: `${w.categories.length} categories`,
    general: w.settings.currency,
    import: 'PayPay, BudgetBakers',
    widgets: 'Android',
  };
  return (
    <section className="stack loose">
      <h1>Settings</h1>
      <nav className="card flush" aria-label="Settings sections">
        <ul className="list flush">
          {sections.map((s) => (
            <li key={s.id}>
              <a className="menu-row" href={href('settings', { section: s.id })}>
                <span className="cat-icon" aria-hidden="true">
                  <Icon d={s.glyph in icons ? icons[s.glyph as keyof typeof icons] : glyphs[s.glyph].d} className="glyph" />
                </span>
                <span className="grow">{s.label}</span>
                <span className="muted small">{summary[s.id]}</span>
                <Icon d="M9 5l7 7-7 7" className="inline-icon chev-icon" />
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <div className="card me">
        <span className="avatar" aria-hidden="true">
          {me.email[0]}
        </span>
        <span className="grow">
          <span className="muted small block">Signed in as</span>
          {me.email}
        </span>
        <button type="button" className="secondary" onClick={onSignOut}>
          Sign out
        </button>
      </div>
      <div className="row end">
        <button type="button" className="link danger" onClick={() => setDeleting(true)}>
          Delete account
        </button>
      </div>
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
    <Dialog title="Delete account" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="muted">
          This deletes <strong>{me.email}</strong> and everything in it: accounts, records, budgets, categories and your forwarding
          address. It can't be undone.
        </p>
        <label className="field">
          {me.has_password ? 'Your password' : 'Type your email to confirm'}
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
            Cancel
          </button>
          <button type="submit" className="danger" disabled={busy}>
            Delete everything
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
            Add account
          </button>
        }
      >
        Everywhere your money lives. Tap one to change it.
      </Intro>
      <ul className="list card flush" aria-label="Accounts">
        {w.accounts.map((a) => (
          <li key={a.id}>
            <button type="button" className="record" aria-label={`Edit account ${a.name}`} onClick={() => setEditing(a)}>
              <CategoryIcon icon={accountKinds[a.kind].glyph} color={a.color} />
              <span className="grow">
                <span className="record-title">
                  {a.name} {a.archived && <span className="badge quiet">archived</span>}
                </span>
                <span className="muted small">{accountKinds[a.kind].label}</span>
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
      setError('Pick what kind of account it is.');
      return;
    }
    const text = balance.trim() || '0';
    const minor = parseAmount(text.replace(/^-/, ''), exp);
    if (minor === null) {
      setError('Enter the balance as a number, like 12000.');
      return;
    }
    const signed = (text.startsWith('-') ? -minor : minor) * (card ? -1 : 1);
    const input = { name: name.trim() || meta.label, kind, initial_balance: signed, color: color || meta.color, archived };
    try {
      if (account) await api.updateAccount(account.id, input);
      else await api.createAccount(input);
      await w.refresh();
      toast(account ? 'Saved' : `Added ${input.name}`);
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!account || !confirm(`Delete "${account.name}" and every record in it? This can't be undone.`)) return;
    try {
      await api.deleteAccount(account.id);
      await w.refresh();
      toast('Deleted');
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const cur = w.settings.currency;
  const balanceLabel = account
    ? `${card ? 'Owed at the start' : 'Starting balance'} (${cur})`
    : `${card ? 'Owed today' : 'Balance today'} (${cur})`;

  return (
    <Dialog title={account ? 'Edit account' : 'New account'} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <fieldset className="chip-pick">
          <legend>{account ? 'Type' : 'What kind of account?'}</legend>
          <div className="kind-pick">
            {(Object.keys(accountKinds) as AccountKind[]).map((k) => (
              <label key={k} className={kind === k ? 'on' : ''}>
                <input type="radio" name="kind" checked={kind === k} onChange={() => setKind(k)} />
                <Icon d={glyphs[accountKinds[k].glyph].d} className="glyph" />
                {accountKinds[k].label}
              </label>
            ))}
          </div>
        </fieldset>
        {meta && (
          <>
            <label className="field">
              Name
              <input
                maxLength={60}
                value={name}
                placeholder={meta.names[0] ?? meta.label}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            {!account && meta.names.length > 0 && (
              <div className="chips" aria-label="Suggested names">
                {meta.names.map((n) => (
                  <button key={n} type="button" className={name === n ? 'chip on' : 'chip'} onClick={() => setName(n)}>
                    {n}
                  </button>
                ))}
              </div>
            )}
            <label className="field">
              {balanceLabel}
              <input inputMode="decimal" placeholder="0" value={balance} onChange={(e) => setBalance(e.target.value)} />
              <span className="muted small">
                {account
                  ? 'Before any records in it. Records change the balance from there.'
                  : card
                    ? "What you haven't paid off yet. Payments you record add to it."
                    : 'Records you add from now on change it.'}
              </span>
            </label>
            <fieldset className="chip-pick">
              <legend>Colour</legend>
              <div className="swatch-pick">
                {swatches.map((c) => (
                  <label key={c} className={(color || meta.color) === c ? 'on' : ''} style={{ background: c }}>
                    <input type="radio" name="color" aria-label={c} checked={(color || meta.color) === c} onChange={() => setColor(c)} />
                  </label>
                ))}
                <label
                  className={swatches.includes(color || meta.color) ? 'custom' : 'custom on'}
                  title="Any colour"
                  style={swatches.includes(color || meta.color) ? undefined : { background: color || meta.color }}
                >
                  <input type="color" aria-label="Any colour" value={color || meta.color} onChange={(e) => setColor(e.target.value)} />
                </label>
              </div>
            </fieldset>
            {account && (
              <label className="check">
                <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
                Archived (hidden from the overview and new records)
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
              Delete
            </button>
          )}
          <span className="grow" />
          <button type="submit" disabled={!kind}>
            {account ? 'Save' : 'Add account'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- Categories ----------

function Categories() {
  const w = useWallet();
  const [editing, setEditing] = useState<Category | 'new' | null>(null);
  return (
    <>
      <Intro
        action={
          <button type="button" onClick={() => setEditing('new')}>
            Add category
          </button>
        }
      >
        What you spend on and earn from. Tap one to rename it or change its icon.
      </Intro>
      <div className="card stack">
      {(['expense', 'income'] as const).map((kind) => (
        <div key={kind} className="stack tight">
          <span className="muted small">{kind === 'expense' ? 'Expenses' : 'Income'}</span>
          <div className="chips">
            {w.categories
              .filter((c) => c.kind === kind)
              .map((c) => (
                <button key={c.id} type="button" className="chip" aria-label={`Edit category ${c.name}`} onClick={() => setEditing(c)}>
                  <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" /> {c.name}
                </button>
              ))}
          </div>
        </div>
      ))}
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
    if (!category || !confirm(`Delete "${category.name}"? Its records become uncategorised.`)) return;
    try {
      await api.deleteCategory(category.id);
      await w.refresh();
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Dialog title={category ? 'Edit category' : 'New category'} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <label className="field">
          Name
          <input required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        {!category && (
          <label className="field">
            Type
            <select value={kind} onChange={(e) => setKind(e.target.value as Category['kind'])}>
              <option value="expense">Expense</option>
              <option value="income">Income</option>
            </select>
          </label>
        )}
        <div className="grid2">
          <label className="field">
            Colour
            <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
          </label>
          <div className="field">
            Preview
            <span className="row">
              <CategoryIcon icon={icon} name={name || '?'} color={color} />
              <span className="ellipsis">{name}</span>
            </span>
          </div>
        </div>
        <fieldset className="chip-pick">
          <legend>Icon</legend>
          <div className="icon-pick">
            {[['', 'Initial only'], ...Object.entries(glyphs).map(([k, g]) => [k, g.label])].map(([k, label]) => (
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
              Delete
            </button>
          )}
          <span className="grow" />
          <button type="submit">Save</button>
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
        Main currency
        <select value={w.settings.currency} onChange={(e) => setCurrency(e.target.value)}>
          {(currencies.includes(w.settings.currency) ? currencies : [w.settings.currency, ...currencies]).map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
      </label>
      <p className="muted small">Pick it before adding records; it can't change afterwards.</p>
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
      <BudgetBakersImport />
    </>
  );
}

/** What an import did, in one sentence. */
function ImportSummary({ result }: { result: ImportResult }) {
  return (
    <div className="notice" role="status">
      Imported {result.imported} records
      {result.duplicates > 0 && `, skipped ${result.duplicates} already imported`}
      {result.matched > 0 && `, skipped ${result.matched} already recorded from emails or by hand`}
      {result.accounts_created > 0 && `, created ${result.accounts_created} accounts`}
      {result.categories_created > 0 && `, created ${result.categories_created} categories`}.
      {result.imported > 0 && (
        <>
          {' '}
          Records without a category can be <a href={href('organize')}>sorted by Claude</a>.
        </>
      )}
      {result.problems.length > 0 && (
        <ul className="small">
          {result.problems.map((p) => (
            <li key={p}>{p}</li>
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

const shortDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });

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
      setResult(await api.importPayPay(csv, choice));
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
        In the PayPay app, open 取引履歴 (transaction history), tap the download button, pick a period of up to a year, and save the CSV file.
        Payments already recorded from their emails are skipped, and importing an overlapping period adds each payment once.
      </p>
      {!preview && (
        <label className="field">
          PayPay CSV file
          <input key={fileKey} type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
        </label>
      )}
      {preview && preview.records === 0 && (
        <>
          <p>There are no payments in this file.</p>
          <div className="row end">
            <button type="button" className="secondary" onClick={reset}>
              Choose another file
            </button>
          </div>
        </>
      )}
      {preview && preview.records > 0 && (
        <form className="stack" onSubmit={submit}>
          <p>
            {preview.records} {preview.records === 1 ? 'payment' : 'payments'}
            {preview.from && preview.to && (shortDate(preview.from) === shortDate(preview.to) ? ` on ${shortDate(preview.from)}` : `, ${shortDate(preview.from)} to ${shortDate(preview.to)}`)}. Choose where each way of paying is
            recorded:
          </p>
          {preview.methods.map((m) => (
            <label key={m.method} className="field">
              <span>
                {m.method}
                {payPayMethodNames[m.method] && <span className="muted"> · {payPayMethodNames[m.method]}</span>}
                <span className="muted small"> · {m.count} {m.count === 1 ? 'record' : 'records'}</span>
              </span>
              <select value={choice[m.method]} onChange={(e) => setChoice({ ...choice, [m.method]: Number(e.target.value) })}>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
                {!accounts.some((a) => a.name.toLowerCase() === m.new_name.toLowerCase()) && (
                  <option value={0}>New account: {m.new_name}</option>
                )}
              </select>
            </label>
          ))}
          {preview.problems.length > 0 && (
            <div className="notice warn small">
              These rows can't be read and will be left out:
              <ul>
                {preview.problems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="row end">
            <button type="button" className="secondary" onClick={reset} disabled={busy}>
              Cancel
            </button>
            <button type="submit" disabled={busy}>
              {busy ? 'Importing…' : `Import ${preview.records} ${preview.records === 1 ? 'payment' : 'payments'}`}
            </button>
          </div>
        </form>
      )}
      {busy && !preview && <p className="muted">Reading the file…</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && <ImportSummary result={result} />}
    </div>
  );
}

function BudgetBakersImport() {
  const w = useWallet();
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function upload(file: File) {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      setResult(await api.importBudgetBakers(await file.text()));
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card stack">
      <h2>BudgetBakers</h2>
      <p className="small muted">
        In BudgetBakers' Wallet app, export your records as CSV (on the web app: Records → Export), then choose the file here. Accounts and
        categories are matched by name and created when missing. Importing the same file twice adds nothing new.
      </p>
      <label className="field">
        CSV file
        <input type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
      </label>
      {busy && <p className="muted">Importing…</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && <ImportSummary result={result} />}
    </div>
  );
}
