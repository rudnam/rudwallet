// Settings › Shops and cards: what confirming payments taught the app (each
// shop's category, each card's account), to check, correct or forget, and
// shops to add by hand before their first payment.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type Category, type Rules } from '../../api';
import { href } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';

export function RulesSettings() {
  const w = useWallet();
  const toast = useToast();
  const [rules, setRules] = useState<Rules | null>(null);
  const [error, setError] = useState('');
  const [shop, setShop] = useState('');
  const [shopCategory, setShopCategory] = useState('');

  function load() {
    return api.rules().then(setRules, (err) => setError(errorMessage(err)));
  }
  useEffect(() => {
    load();
  }, []);

  async function change(what: () => Promise<unknown>, done: string) {
    setError('');
    try {
      await what();
      await load();
      toast(done);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    const name = shop.trim();
    await change(() => api.setPayeeRule(name, Number(shopCategory)), `${name} added`);
    setShop('');
    setShopCategory('');
  }

  const accounts = w.accounts.filter((a) => !a.archived);
  return (
    <div className="stack loose">
      <div className="card stack">
        <p className="small">
          When you confirm a payment from an email or a notification, its shop's category and its card's account are
          remembered, and the next payment there is filled in for you. Payments at a shop's branches (a name that starts
          with the shop's) share its category.
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}

        <h2>Shops</h2>
        {rules === null ? (
          <p className="muted">Loading…</p>
        ) : rules.payees.length === 0 ? (
          <p className="muted small">No shops yet. Confirm a payment under <a href={href('review')}>To review</a>, or add one below.</p>
        ) : (
          <ul className="list rows" aria-label="Shops">
            {rules.payees.map((r) => (
              <li key={r.key} className="row wrap rule-row" aria-label={r.payee}>
                <span className="grow">
                  <span className="block">{r.payee}</span>
                  <span className="muted small">{records(r.records)}</span>
                </span>
                <CategorySelect
                  label={`Category for ${r.payee}`}
                  categories={w.categories}
                  value={String(r.category_id)}
                  onChange={(id) => change(() => api.setPayeeRule(r.key, Number(id)), `${r.payee} changed`)}
                />
                <button type="button" className="link" onClick={() => change(() => api.deletePayeeRule(r.key), `${r.payee} forgotten`)}>
                  Forget
                </button>
              </li>
            ))}
          </ul>
        )}

        <form className="stack tight" onSubmit={add}>
          <h3>Add a shop</h3>
          <div className="row wrap rule-row">
            <input
              className="grow"
              aria-label="Shop name"
              placeholder="Shop name, e.g. ローソン"
              value={shop}
              onChange={(e) => setShop(e.target.value)}
            />
            <CategorySelect label="Category for the new shop" categories={w.categories} value={shopCategory} onChange={setShopCategory} />
            <button type="submit" disabled={!shop.trim() || !shopCategory}>
              Add
            </button>
          </div>
        </form>
      </div>

      <div className="card stack">
        <h2>Cards</h2>
        {rules === null ? (
          <p className="muted">Loading…</p>
        ) : rules.cards.length === 0 ? (
          <p className="muted small">No cards yet. A card is remembered when you confirm its first payment.</p>
        ) : (
          <ul className="list rows" aria-label="Cards">
            {rules.cards.map((c) => (
              <li key={c.source} className="row wrap rule-row" aria-label={c.source}>
                <span className="grow">
                  <span className="block">{c.source}</span>
                  <span className="muted small">{records(c.records)}</span>
                </span>
                <select
                  aria-label={`Account for ${c.source}`}
                  value={c.account_id}
                  onChange={(e) => change(() => api.setCardRule(c.source, Number(e.target.value)), `${c.source} changed`)}
                >
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                    </option>
                  ))}
                  {!accounts.some((a) => a.id === c.account_id) && (
                    <option value={c.account_id}>{w.account(c.account_id)?.name ?? 'Archived account'}</option>
                  )}
                </select>
                <button type="button" className="link" onClick={() => change(() => api.deleteCardRule(c.source), `${c.source} forgotten`)}>
                  Forget
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="muted small">
          A forgotten card's payments go to the account chosen under{' '}
          <a href={href('settings', { section: 'email' })}>Email automation</a> until you confirm one elsewhere.
        </p>
      </div>
    </div>
  );
}

function CategorySelect({
  label,
  categories,
  value,
  onChange,
}: {
  label: string;
  categories: Category[];
  value: string;
  onChange: (id: string) => void;
}) {
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      {value === '' && <option value="">Pick a category</option>}
      <optgroup label="Expenses">
        {categories.filter((c) => c.kind === 'expense').map(option)}
      </optgroup>
      <optgroup label="Income">
        {categories.filter((c) => c.kind === 'income').map(option)}
      </optgroup>
    </select>
  );
}

function option(c: Category) {
  return (
    <option key={c.id} value={c.id}>
      {c.name}
    </option>
  );
}

function records(n: number) {
  return n === 1 ? '1 record' : `${n} records`;
}
