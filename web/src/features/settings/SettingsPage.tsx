// Settings: email automation, accounts, categories, currency, and import.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Account, type AccountKind, type Category, type ImportResult } from '../../api';
import { Dialog } from '../../components/Dialog';
import { amountInput, parseAmount } from '../../money';
import type { Route } from '../../router';
import { useWallet } from '../../wallet';
import { EmailSettings } from './EmailSettings';

export const accountKinds: Record<AccountKind, string> = {
  cash: 'Cash',
  bank: 'Bank account',
  credit_card: 'Credit card',
  e_money: 'E-money (PayPay, Suica…)',
  savings: 'Savings',
  other: 'Other',
};

export function SettingsPage({ route }: { route: Route }) {
  const section = route.params.get('section');
  useEffect(() => {
    if (section) document.getElementById(section)?.scrollIntoView();
  }, [section]);

  return (
    <section className="stack">
      <h1>Settings</h1>
      <EmailSettings />
      <Accounts />
      <Categories />
      <General />
      <Import />
    </section>
  );
}

// ---------- Accounts ----------

function Accounts() {
  const w = useWallet();
  const [editing, setEditing] = useState<Account | 'new' | null>(null);
  return (
    <div className="card stack" id="accounts">
      <div className="row">
        <h2 className="grow">Accounts</h2>
        <button type="button" onClick={() => setEditing('new')}>
          Add account
        </button>
      </div>
      <ul className="list rows" aria-label="Accounts">
        {w.accounts.map((a) => (
          <li key={a.id} className="row">
            <span className="swatch" style={{ background: a.color }} aria-hidden="true" />
            <span className="grow">
              {a.name} {a.archived && <span className="badge quiet">archived</span>}
              <span className="muted small block">{accountKinds[a.kind]}</span>
            </span>
            <span>{w.money(a.balance)}</span>
            <button type="button" className="link" aria-label={`Edit account ${a.name}`} onClick={() => setEditing(a)}>
              Edit
            </button>
          </li>
        ))}
      </ul>
      {editing && <AccountForm account={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function AccountForm({ account, onClose }: { account?: Account; onClose: () => void }) {
  const w = useWallet();
  const exp = w.settings.currency_exponent;
  const [name, setName] = useState(account?.name ?? '');
  const [kind, setKind] = useState<AccountKind>(account?.kind ?? 'bank');
  const [initial, setInitial] = useState(account ? amountInput(Math.abs(account.initial_balance), exp) : '0');
  const [negative, setNegative] = useState((account?.initial_balance ?? 0) < 0);
  const [color, setColor] = useState(account?.color ?? '#2f5d50');
  const [archived, setArchived] = useState(account?.archived ?? false);
  const [error, setError] = useState('');

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(initial || '0', exp);
    if (minor === null) {
      setError('Enter the starting balance as a number.');
      return;
    }
    const input = { name, kind, initial_balance: negative ? -minor : minor, color, archived };
    try {
      if (account) await api.updateAccount(account.id, input);
      else await api.createAccount(input);
      await w.refresh();
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
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Dialog title={account ? 'Edit account' : 'New account'} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <label className="field">
          Name
          <input required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          Type
          <select value={kind} onChange={(e) => setKind(e.target.value as AccountKind)}>
            {Object.entries(accountKinds).map(([k, label]) => (
              <option key={k} value={k}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Starting balance ({w.settings.currency})
          <input inputMode="decimal" value={initial} onChange={(e) => setInitial(e.target.value)} />
        </label>
        <label className="check">
          <input type="checkbox" checked={negative} onChange={(e) => setNegative(e.target.checked)} />
          The starting balance is owed (e.g. a card's unpaid bill)
        </label>
        <label className="field">
          Colour
          <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
        </label>
        {account && (
          <label className="check">
            <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
            Archived (hidden from the overview and new records)
          </label>
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
          <button type="submit">Save</button>
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
    <div className="card stack" id="categories">
      <div className="row">
        <h2 className="grow">Categories</h2>
        <button type="button" onClick={() => setEditing('new')}>
          Add category
        </button>
      </div>
      {(['expense', 'income'] as const).map((kind) => (
        <div key={kind} className="stack tight">
          <span className="muted small">{kind === 'expense' ? 'Expenses' : 'Income'}</span>
          <div className="chips">
            {w.categories
              .filter((c) => c.kind === kind)
              .map((c) => (
                <button key={c.id} type="button" className="chip" aria-label={`Edit category ${c.name}`} onClick={() => setEditing(c)}>
                  <span aria-hidden="true">{c.icon}</span> {c.name}
                </button>
              ))}
          </div>
        </div>
      ))}
      {editing && <CategoryForm category={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CategoryForm({ category, onClose }: { category?: Category; onClose: () => void }) {
  const w = useWallet();
  const [name, setName] = useState(category?.name ?? '');
  const [kind, setKind] = useState(category?.kind ?? 'expense');
  const [icon, setIcon] = useState(category?.icon ?? '');
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
            Icon (an emoji)
            <input maxLength={8} value={icon} onChange={(e) => setIcon(e.target.value)} />
          </label>
          <label className="field">
            Colour
            <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
          </label>
        </div>
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
    <div className="card stack" id="general">
      <h2>Currency</h2>
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
    <div className="card stack" id="import">
      <h2>Import from BudgetBakers</h2>
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
      {result && (
        <div className="notice" role="status">
          Imported {result.imported} records
          {result.duplicates > 0 && `, skipped ${result.duplicates} already imported`}
          {result.accounts_created > 0 && `, created ${result.accounts_created} accounts`}
          {result.categories_created > 0 && `, created ${result.categories_created} categories`}.
          {result.problems.length > 0 && (
            <ul className="small">
              {result.problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
