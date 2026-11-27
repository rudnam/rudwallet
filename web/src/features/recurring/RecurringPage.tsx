// Recurring payments: subscriptions, bills and income that come back every
// month or few. The server finds them in the records and suggests them; once
// added, each shows its last payment and when the next is due, and Insights
// counts what's still to come in the month's estimate.
import { useCallback, useEffect, useId, useState, type FormEvent } from 'react';
import { categoryPath } from '../../categories';
import {
  api,
  errorMessage,
  type Recurring,
  type RecurringInput,
  type RecurringKind,
  type RecurringOverview,
  type RecurringSuggestion,
} from '../../api';
import { CategoryPicker } from '../../components/CategoryPicker';
import { Dialog } from '../../components/Dialog';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { amountInput, dayKey, parseAmount } from '../../money';
import { href } from '../../router';
import { useWallet } from '../../wallet';
import { t, tn, type Key } from '../../i18n';
import { locale } from '../../language';

const kinds: { kind: RecurringKind; label: Key; heading: Key }[] = [
  { kind: 'subscription', label: 'Subscription', heading: 'Subscriptions' },
  { kind: 'bill', label: 'Bill', heading: 'Bills and utilities' },
  { kind: 'income', label: 'Income', heading: 'Income' },
];

const every: Record<number, Key> = {
  1: 'Every month',
  2: 'Every 2 months',
  3: 'Every 3 months',
  6: 'Every 6 months',
  12: 'Every year',
};

/** A YYYY-MM-DD day as "Dec 2" (with the year if it isn't this one). */
export function formatDate(day: string): string {
  const [y, m, d] = day.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const year = y === new Date().getFullYear() ? undefined : 'numeric';
  return date.toLocaleDateString(locale(), { year, month: 'short', day: 'numeric' });
}

export function RecurringPage() {
  const w = useWallet();
  const [data, setData] = useState<RecurringOverview | null>(() => api.seen.recurring() ?? null);
  const [editing, setEditing] = useState<Recurring | RecurringSuggestion | 'new' | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.recurring().then(
      (d) => {
        setData(d);
        setError('');
      },
      (err) => setError(errorMessage(err)),
    );
  }, []);
  useEffect(load, [load]);

  async function act(fn: () => Promise<unknown>) {
    try {
      await fn();
      load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const add = ({ name, kind, matches, amount, months, start_date, category_id, account_id }: RecurringSuggestion) =>
    api.createRecurring({ name, kind, matches, amount, months, start_date, category_id, account_id, active: true });
  const suggestions = data?.suggestions ?? [];
  const active = data?.recurring.filter((r) => r.active) ?? [];
  const stopped = data?.recurring.filter((r) => !r.active) ?? [];

  return (
    <section className="stack">
      <a className="back" href={href('stats')}>
        <Icon d={icons.back} />
        {t('Insights')}
      </a>
      <header className="page-head">
        <div>
          <h1>{t('Recurring payments')}</h1>
        </div>
        <button type="button" className="secondary" onClick={() => setEditing('new')}>
          {t('Add')}
        </button>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {data === null ? (
        <p className="muted">{t('Loading…')}</p>
      ) : (
        <>
          {data.recurring.length > 0 && <ThisMonth data={data} />}

          {suggestions.length > 0 && (
            <div className="card stack" aria-label={t('Found in your records')}>
              <div className="section-head">
                <h2>{t('Found in your records')}</h2>
                {suggestions.length > 1 && (
                  <button type="button" className="link" onClick={() => act(() => Promise.all(suggestions.map(add)))}>
                    {t('Add all')}
                  </button>
                )}
              </div>
              <p className="muted small">{t('These come back regularly. Add them to see what’s due and to estimate the month better.')}</p>
              <ul className="list flush rows" aria-label={t('Suggestions')}>
                {suggestions.map((s) => {
                  const c = w.category(s.category_id);
                  return (
                    <li key={s.key} className="stack tight">
                      <div className="row">
                        <CategoryIcon icon={c?.icon} name={c?.name ?? s.name} color={c?.color} />
                        <button
                          type="button"
                          className="grow unstyled"
                          aria-label={t('Review {name}', { name: s.name })}
                          onClick={() => setEditing(s)}
                        >
                          <strong className="block ellipsis">{s.name}</strong>
                          <span className="muted small">
                            {t(kindLabel(s.kind))} · {t(every[s.months])} · {tn(s.payments.length, '{n} payment', '{n} payments')}
                          </span>
                        </button>
                        <strong className="recurring-figures">{w.money(s.amount)}</strong>
                      </div>
                      <div className="recurring-actions">
                        <button type="button" className="link quiet" onClick={() => act(() => api.dismissRecurring(s.key))}>
                          {t('Not recurring')}
                        </button>
                        <button type="button" className="link" aria-label={t('Add {name}', { name: s.name })} onClick={() => act(() => add(s))}>
                          {t('Add')}
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {data.recurring.length === 0 && suggestions.length === 0 && (
            <div className="card stack empty">
              <span className="empty-mark" aria-hidden="true">
                <Icon d={icons.repeat} className="glyph" />
              </span>
              <h2>{t('No recurring payments yet')}</h2>
              <p className="muted small">
                {t('Subscriptions and bills you add here are expected every month, so Insights can tell where the month is heading.')}
              </p>
              <button type="button" onClick={() => setEditing('new')}>
                {t('Add a recurring payment')}
              </button>
            </div>
          )}

          {kinds.map(({ kind, heading }) => {
            const items = active.filter((r) => r.kind === kind);
            return items.length > 0 && <Group key={kind} title={t(heading)} items={items} onOpen={setEditing} />;
          })}
          {stopped.length > 0 && <Group title={t('Stopped')} items={stopped} onOpen={setEditing} />}
        </>
      )}
      {editing && (
        <RecurringForm
          recurring={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            load();
          }}
        />
      )}
    </section>
  );
}

function kindLabel(kind: RecurringKind): Key {
  return kinds.find((k) => k.kind === kind)!.label;
}

/** This month's: what's been paid, and what's still to come. */
function ThisMonth({ data }: { data: RecurringOverview }) {
  const w = useWallet();
  const m = data.month;
  return (
    <div className="card stack" aria-label={t('This month')}>
      <h2>{t('This month')}</h2>
      <div className="row">
        <span className="grow">
          <span className="muted small block">{t('Paid this month')}</span>
          <strong className="figure-small">{w.money(m.paid)}</strong>
        </span>
        <span className="grow">
          <span className="muted small block">{t('Still to come')}</span>
          <strong className="figure-small">{w.money(m.upcoming)}</strong>
        </span>
      </div>
      {m.due.length > 0 && (
        <ul className="list rows" aria-label={t('Still to come')}>
          {m.due.map((d, i) => (
            <li key={`${d.id}-${d.date}-${i}`} className="row">
              <span className="grow ellipsis">{d.name}</span>
              <span className={d.late ? 'small late' : 'small muted'}>
                {d.late ? t('Was due {date}', { date: formatDate(d.date) }) : formatDate(d.date)}
              </span>
              <strong>{w.money(d.amount)}</strong>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Group({ title, items, onOpen }: { title: string; items: Recurring[]; onOpen: (r: Recurring) => void }) {
  const w = useWallet();
  const total = items.reduce((sum, r) => sum + Math.round(r.estimate / r.months), 0);
  return (
    <div className="stack tight">
      <div className="section-head">
        <h2>{title}</h2>
        {items[0].active && <span className="muted small">{t('About {amount} a month', { amount: w.money(total) })}</span>}
      </div>
      <ul className="list card flush" aria-label={title}>
        {items.map((r) => {
          const c = w.category(r.category_id);
          return (
            <li key={r.id}>
              <button type="button" className="record" aria-label={t('Edit {name}', { name: r.name })} onClick={() => onOpen(r)}>
                <CategoryIcon icon={c?.icon} name={c?.name ?? r.name} color={c?.color} />
                <span className="grow">
                  <strong className="block ellipsis">{r.name}</strong>
                  <span className={r.active && r.late ? 'small late' : 'muted small'}>{status(r)}</span>
                </span>
                <span className="recurring-figures">
                  <strong className="block">
                    {r.kind === 'bill' && r.payments.length > 1 ? '≈ ' : ''}
                    {w.money(r.estimate)}
                  </strong>
                  <span className="muted small">{t(every[r.months])}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** When it was paid and when it's next due, in a line. */
function status(r: Recurring): string {
  if (!r.active) return r.last_paid ? t('Last paid {date}', { date: formatDate(r.last_paid.date) }) : t('Stopped');
  if (!r.next_due) return '';
  if (r.late) return t('Was due {date}', { date: formatDate(r.next_due) });
  const next = t('Next {date}', { date: formatDate(r.next_due) });
  return r.last_paid ? `${t('Paid {date}', { date: formatDate(r.last_paid.date) })} · ${next}` : next;
}

function RecurringForm({
  recurring,
  onClose,
  onSaved,
}: {
  recurring?: Recurring | RecurringSuggestion;
  onClose: () => void;
  onSaved: () => void;
}) {
  const w = useWallet();
  const exp = w.settings.currency_exponent;
  const saved = recurring && 'id' in recurring ? recurring : undefined;
  const [name, setName] = useState(recurring?.name ?? '');
  const [kind, setKind] = useState<RecurringKind>(recurring?.kind ?? 'subscription');
  const [amount, setAmount] = useState(recurring ? amountInput(saved ? saved.amount : recurring.amount, exp) : '');
  const [months, setMonths] = useState(recurring?.months ?? 1);
  const [start, setStart] = useState(saved?.next_due ?? recurring?.start_date ?? dayKey(new Date()));
  const [categoryId, setCategoryId] = useState<number | null>(recurring?.category_id ?? null);
  const [accountId, setAccountId] = useState<number | null>(recurring?.account_id ?? null);
  const [matches, setMatches] = useState((recurring?.matches ?? []).join(', '));
  const [active, setActive] = useState(saved?.active ?? true);
  const [picking, setPicking] = useState(false);
  const [error, setError] = useState('');
  const catId = useId();
  const recordKind = kind === 'income' ? 'income' : 'expense';
  const rawChosen = w.category(categoryId);
  const chosen = rawChosen?.kind === recordKind ? rawChosen : undefined;

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmount(amount, exp);
    if (!minor) {
      setError(t('Enter an amount.'));
      return;
    }
    const input: RecurringInput = {
      name: name.trim(),
      kind,
      matches: matches
        .split(',')
        .map((m) => m.trim())
        .filter(Boolean),
      amount: minor,
      months,
      start_date: start,
      category_id: w.category(categoryId)?.kind === recordKind ? categoryId : null,
      account_id: accountId,
      active,
    };
    try {
      if (saved) await api.updateRecurring(saved.id, input);
      else await api.createRecurring(input);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove() {
    if (!saved || !confirm(t('Delete "{name}"? Its records stay.', { name: saved.name }))) return;
    try {
      await api.deleteRecurring(saved.id);
      onSaved();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <>
      <Dialog title={saved ? t('Edit recurring payment') : t('New recurring payment')} onClose={onClose}>
        <form className="stack" onSubmit={save}>
          <div className="segmented" role="radiogroup" aria-label={t('Type')}>
            {kinds.map((k) => (
              <label key={k.kind} className={kind === k.kind ? 'on' : ''}>
                <input type="radio" name="kind" value={k.kind} checked={kind === k.kind} onChange={() => setKind(k.kind)} />
                {t(k.label)}
              </label>
            ))}
          </div>
          <label className="field">
            {t('Name')}
            <input required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            {kind === 'bill' ? t('About how much ({currency})', { currency: w.settings.currency }) : t('Amount ({currency})', { currency: w.settings.currency })}
            <input inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <fieldset className="chip-pick">
            <legend>{t('How often')}</legend>
            <div className="chips">
              {Object.entries(every).map(([m, label]) => (
                <label key={m} className={months === Number(m) ? 'chip on' : 'chip'}>
                  <input type="radio" name="months" checked={months === Number(m)} onChange={() => setMonths(Number(m))} />
                  {t(label)}
                </label>
              ))}
            </div>
          </fieldset>
          <label className="field">
            {t('Next due')}
            <input type="date" required value={start} onChange={(e) => setStart(e.target.value)} />
          </label>
          <div className="field">
            <label htmlFor={catId}>{t('Category')}</label>
            <button
              id={catId}
              type="button"
              className={chosen ? 'picker-trigger' : 'picker-trigger placeholder'}
              aria-haspopup="dialog"
              aria-describedby={`${catId}-val`}
              onClick={() => setPicking(true)}
            >
              {chosen && <CategoryIcon icon={chosen.icon} name={chosen.name} color={chosen.color} size="small" />}
              <span id={`${catId}-val`} className="ellipsis grow">
                {chosen ? (categoryPath(chosen.id, w.categories) ?? chosen.name) : t('No category')}
              </span>
              <Icon d={icons.expand} className="picker-caret" />
            </button>
          </div>
          <label className="field">
            {t('Account')}
            <select value={accountId ?? ''} onChange={(e) => setAccountId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">{t('Any account')}</option>
              {w.accounts
                .filter((a) => !a.archived || a.id === accountId)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            {t('Records it matches')}
            <input value={matches} placeholder={name} onChange={(e) => setMatches(e.target.value)} />
            <span className="muted small">{t('Shop names or notes that mean this payment, separated by commas. Left empty, its name.')}</span>
          </label>
          {saved && (
            <label className="check">
              <input type="checkbox" checked={!active} onChange={(e) => setActive(!e.target.checked)} />
              {t('Stopped (cancelled, or paid off)')}
            </label>
          )}
          {saved && saved.payments.length > 0 && <Payments recurring={saved} />}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <div className="row end">
            {saved && (
              <button type="button" className="link danger" onClick={remove}>
                {t('Delete')}
              </button>
            )}
            <span className="grow" />
            <button type="submit">{saved ? t('Save') : t('Add')}</button>
          </div>
        </form>
      </Dialog>
      {picking && (
        <CategoryPicker
          categories={w.categories}
          kind={recordKind}
          selected={chosen?.id ?? null}
          title={t('Category')}
          extras={[{ label: t('No category'), selected: !chosen, onPick: () => setCategoryId(null) }]}
          onPick={(c) => setCategoryId(c.id)}
          onClose={() => setPicking(false)}
        />
      )}
    </>
  );
}

function Payments({ recurring }: { recurring: Recurring }) {
  const w = useWallet();
  return (
    <div className="stack tight">
      <h3 className="small muted">{t('Its records')}</h3>
      <ul className="list rows" aria-label={t('Its records')}>
        {recurring.payments.map((p) => (
          <li key={p.transaction_id} className="row">
            <span className="small muted">{formatDate(p.date)}</span>
            <span className="grow ellipsis small">{p.label}</span>
            <strong className="small">{w.money(p.amount)}</strong>
          </li>
        ))}
      </ul>
    </div>
  );
}
