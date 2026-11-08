// Settings › Shops and cards: what confirming payments taught the app (each
// shop's category, each card's account), to check, correct or forget, and
// shops to add by hand before their first payment.
import { useEffect, useState, type FormEvent } from 'react';
import { inOrder, optionLabel } from '../../categories';
import { api, errorMessage, type Category, type Rules } from '../../api';
import { href } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { t, tn, tx } from '../../i18n';

export function RulesSettings() {
  const w = useWallet();
  const toast = useToast();
  const [rules, setRules] = useState<Rules | null>(() => api.seen.rules() ?? null);
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
    await change(() => api.setPayeeRule(name, Number(shopCategory)), t('{name} added', { name }));
    setShop('');
    setShopCategory('');
  }

  const accounts = w.accounts.filter((a) => !a.archived);
  return (
    <div className="stack loose">
      <div className="card stack">
        <p className="small">
          {t(
            "Confirm a payment once, and its shop's category and its card's account fill in by themselves next time. A shop's branches share its category.",
          )}
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}

        <h2>{t('Shops')}</h2>
        {rules === null ? (
          <p className="muted">{t('Loading…')}</p>
        ) : rules.payees.length === 0 ? (
          <p className="muted small">
            {t('No shops yet. Each one you confirm in Activity is added here.')}
          </p>
        ) : (
          <ul className="list rows" aria-label={t('Shops')}>
            {rules.payees.map((r) => (
              <li key={r.key} className="row wrap rule-row" aria-label={r.payee}>
                <span className="grow">
                  <span className="block">{r.payee}</span>
                  <span className="muted small">{records(r.records)}</span>
                </span>
                <CategorySelect
                  label={t('Category for {name}', { name: r.payee })}
                  categories={w.categories}
                  value={String(r.category_id)}
                  onChange={(id) => change(() => api.setPayeeRule(r.key, Number(id)), t('{name} changed', { name: r.payee }))}
                />
                <button type="button" className="link" onClick={() => change(() => api.deletePayeeRule(r.key), t('{name} forgotten', { name: r.payee }))}>
                  {t('Forget')}
                </button>
              </li>
            ))}
          </ul>
        )}

        <form className="stack tight" onSubmit={add}>
          <h3>{t('Add a shop')}</h3>
          <div className="row wrap rule-row">
            <input
              className="grow"
              aria-label={t('Shop name')}
              placeholder={t('Shop name, e.g. ローソン')}
              value={shop}
              onChange={(e) => setShop(e.target.value)}
            />
            <CategorySelect label={t('Category for the new shop')} categories={w.categories} value={shopCategory} onChange={setShopCategory} />
            <button type="submit" disabled={!shop.trim() || !shopCategory}>
              {t('Add')}
            </button>
          </div>
        </form>
      </div>

      <div className="card stack">
        <h2>{t('Cards')}</h2>
        {rules === null ? (
          <p className="muted">{t('Loading…')}</p>
        ) : rules.cards.length === 0 ? (
          <p className="muted small">{t('No cards yet. A card is remembered when you confirm its first payment.')}</p>
        ) : (
          <ul className="list rows" aria-label={t('Cards')}>
            {rules.cards.map((c) => (
              <li key={c.source} className="row wrap rule-row" aria-label={c.source}>
                <span className="grow">
                  <span className="block">{c.source}</span>
                  <span className="muted small">{records(c.records)}</span>
                </span>
                <select
                  aria-label={t('Account for {name}', { name: c.source })}
                  value={c.account_id}
                  onChange={(e) => change(() => api.setCardRule(c.source, Number(e.target.value)), t('{name} changed', { name: c.source }))}
                >
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name}
                    </option>
                  ))}
                  {!accounts.some((a) => a.id === c.account_id) && (
                    <option value={c.account_id}>{w.account(c.account_id)?.name ?? t('Archived account')}</option>
                  )}
                </select>
                <button type="button" className="link" onClick={() => change(() => api.deleteCardRule(c.source), t('{name} forgotten', { name: c.source }))}>
                  {t('Forget')}
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="muted small">
          {tx('Forget a card, and its payments go to the account set in {notifications}.', {
            notifications: <a href={href('settings', { section: 'notifications' })}>{t('Payment notifications')}</a>,
          })}
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
      {value === '' && <option value="">{t('Pick a category')}</option>}
      <optgroup label={t('Expenses')}>
        {inOrder(categories).filter((c) => c.kind === 'expense').map((c) => option(c, categories))}
      </optgroup>
      <optgroup label={t('Income')}>
        {inOrder(categories).filter((c) => c.kind === 'income').map((c) => option(c, categories))}
      </optgroup>
    </select>
  );
}

function option(c: Category, all: Category[]) {
  return (
    <option key={c.id} value={c.id}>
      {optionLabel(c, all)}
    </option>
  );
}

function records(n: number) {
  return tn(n, '{n} record', '{n} records');
}
