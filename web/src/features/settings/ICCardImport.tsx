// Settings › Import › IC card: a transit IC card (Suica, PASMO, ICOCA…)
// held to the back of the phone is read over NFC (native.ts), and its last
// entries are shown with how each would be recorded, as for a bank
// statement, before anything is recorded. Only the Android app can read
// cards; elsewhere this says so.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type ICBalance, type ICCardRead, type ICPreview, type ICResult, type ICRole, type ICRow } from '../../api';
import { cancelICCardRead, icCardReader, openNFCSettings, readICCard } from '../../native';
import { useWallet } from '../../wallet';
import { t, tn, type Key } from '../../i18n';
import { ImportCategories, ImportSummary, categoryChoices, shortDate, suggestedChoices, type Choices } from './ImportCategories';

const roles: Record<ICRole, Key> = {
  card: 'The card',
  'top-up': 'Top-ups are paid from',
};

/** Why a card couldn't be read, by the reader's error code. */
const readErrors: Record<string, Key> = {
  'nfc-off': 'NFC is turned off.',
  'not-transit': "That isn't a transit IC card. Suica, PASMO, ICOCA and the other cards that work at ticket gates can be read.",
  lost: 'The card was moved away too soon. Hold it still until the phone beeps.',
};

export function ICCardImport() {
  const w = useWallet();
  const [reader, setReader] = useState<'ready' | 'off' | null>(null);
  const [waiting, setWaiting] = useState(false);
  const [read, setRead] = useState<ICCardRead | null>(null);
  const [preview, setPreview] = useState<ICPreview | null>(null);
  const [choice, setChoice] = useState<Record<string, number>>({});
  const [cats, setCats] = useState<Choices>({});
  const [match, setMatch] = useState(true);
  const [result, setResult] = useState<ICResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // Check again on coming back from Android's NFC settings.
    const check = () => {
      if (document.visibilityState === 'visible') icCardReader().then(setReader);
    };
    check();
    document.addEventListener('visibilitychange', check);
    return () => {
      document.removeEventListener('visibilitychange', check);
      cancelICCardRead();
    };
  }, []);

  async function start() {
    setError('');
    setResult(null);
    setWaiting(true);
    let card: ICCardRead;
    try {
      card = await readICCard();
    } catch (err) {
      const code = (err as { code?: string }).code ?? '';
      if (code === 'nfc-off') setReader('off');
      if (code !== 'cancelled') setError(code in readErrors ? t(readErrors[code]) : errorMessage(err));
      return;
    } finally {
      setWaiting(false);
    }
    setBusy(true);
    try {
      const p = await api.previewICCard(card);
      setRead(card);
      setPreview(p);
      setChoice(Object.fromEntries(p.accounts.map((a) => [a.role, a.account_id ?? 0])));
      setCats(suggestedChoices(p.categories));
      setMatch(!p.gap);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function stop() {
    cancelICCardRead();
    setWaiting(false);
  }

  function reset() {
    setRead(null);
    setPreview(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!read) return;
    setBusy(true);
    setError('');
    try {
      setResult(await api.importICCard(read, choice, categoryChoices(preview?.categories ?? [], cats), match));
      reset();
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const name = (id: number | undefined) => w.accounts.find((a) => a.id === id)?.name ?? '';
  const accounts = w.accounts.filter((a) => !a.archived || Object.values(choice).includes(a.id));
  return (
    <div className="card stack">
      <h2>{t('IC card')}</h2>
      <p className="small muted">
        {t(
          "Hold a Suica, PASMO, ICOCA or other transit IC card to the back of the phone to import its train and bus fares, shop payments and top-ups. The card keeps only its last 20 entries, so read it every week or two. Mobile Suica on this phone can't be read this way.",
        )}
      </p>
      {reader === null && <p className="muted">{t('This works in the RudWallet app on an Android phone with NFC.')}</p>}
      {reader === 'off' && !preview && (
        <div className="row">
          <span className="grow">{t('NFC is turned off.')}</span>
          <button type="button" className="secondary" onClick={openNFCSettings}>
            {t('Turn on NFC')}
          </button>
        </div>
      )}
      {reader === 'ready' && !preview && !waiting && (
        <div className="row">
          <button type="button" onClick={start} disabled={busy}>
            {t('Read a card')}
          </button>
        </div>
      )}
      {waiting && (
        <div className="row">
          <span className="grow" role="status">
            {t('Hold the card to the back of the phone…')}
          </span>
          <button type="button" className="secondary" onClick={stop}>
            {t('Cancel')}
          </button>
        </div>
      )}
      {busy && !preview && <p className="muted">{t('Reading the card…')}</p>}
      {preview && (
        <form className="stack" onSubmit={submit}>
          <p>
            {tn(preview.records, 'Card ••{card}: {n} entry, {from} to {to}. Choose the accounts:', 'Card ••{card}: {n} entries, {from} to {to}. Choose the accounts:', {
              card: preview.card,
              from: shortDate(preview.from),
              to: shortDate(preview.to),
            })}
          </p>
          {preview.gap && (
            <p className="notice warn">{t('The card was used more often than it keeps since it was last read, so some entries are lost. The difference shows in the balance below.')}</p>
          )}
          {preview.accounts.map((a) => (
            <label key={a.role} className="field">
              <span>
                {t(roles[a.role])}
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
            </label>
          ))}
          {preview.categories.length > 0 && (
            <>
              <p>{t('Choose a category for each kind of entry. It is remembered for the next time you read a card.')}</p>
              <ImportCategories groups={preview.categories} newCategories={preview.new_categories} choices={cats} onChange={setCats} by="shop" />
            </>
          )}
          <div className="table-scroll">
            <table className="small" aria-label={t('Card entries')}>
              <thead>
                <tr>
                  <th>{t('Date')}</th>
                  <th>{t('Entry')}</th>
                  <th className="amount">{t('Amount')}</th>
                  <th className="amount">{t('Balance')}</th>
                  <th>{t('Recorded as')}</th>
                </tr>
              </thead>
              <tbody>
                {[...preview.rows].reverse().map((r) => (
                  <tr key={`${r.date}:${r.seq}`} className={r.status === 'new' ? '' : 'muted'}>
                    <td>
                      {shortDate(r.date)}
                      {r.time && ` ${r.time}`}
                    </td>
                    <td>{r.status === 'start' ? '' : r.kind === 'transfer' ? t('Top-up') : r.group}</td>
                    <td className="amount">{r.status === 'start' ? '' : w.money(r.amount, true)}</td>
                    <td className="amount">{w.money(r.balance)}</td>
                    <td>{status(r, name(choice['top-up']) || (preview.accounts.find((a) => a.role === 'top-up')?.new_name ?? ''))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <BalanceNote balance={preview.balance} match={match} done={false} newName={preview.accounts[0]?.new_name ?? ''} />
          <label className="check">
            <input type="checkbox" checked={match} onChange={(e) => setMatch(e.target.checked)} />
            {t("Update the card's account to match the card's balance")}
          </label>
          <div className="row end">
            <button type="button" className="secondary" onClick={reset} disabled={busy}>
              {t('Cancel')}
            </button>
            <button type="submit" disabled={busy || (preview.new === 0 && (!match || preview.balance.difference === 0))}>
              {busy ? t('Importing…') : tn(preview.new, 'Import {n} entry', 'Import {n} entries')}
            </button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && (
        <>
          <ImportSummary result={result} />
          <BalanceNote balance={result.balance} match={match} done newName="" />
        </>
      )}
    </div>
  );
}

/** How an entry would be recorded; topUp names the account top-ups come from. */
function status(r: ICRow, topUp: string): string {
  switch (r.status) {
    case 'imported':
      return t('Already imported');
    case 'matched':
      return t('Already recorded');
    case 'start':
      return t('Start: amount unknown');
    case 'none':
      return t('Nothing paid');
  }
  if (r.kind === 'transfer') return t('Transfer: {from} → {to}', { from: topUp, to: t('The card') });
  return t(r.kind === 'expense' ? 'Expense' : 'Income');
}

/** The card's account against the card, and how they come to agree. */
function BalanceNote({ balance, match, done, newName }: { balance: ICBalance; match: boolean; done: boolean; newName: string }) {
  const w = useWallet();
  const account = w.accounts.find((a) => a.id === balance.account_id)?.name ?? newName;
  return (
    <div className={balance.difference === 0 ? 'notice' : 'notice warn'} role="status">
      <p>
        {t('On the card: {card}. {account} in the app: {app}.', { card: w.money(balance.card), account, app: w.money(balance.app) })}{' '}
        {balance.difference === 0 && t('They match.')}
      </p>
      {done && balance.adjusted !== 0 && (
        <p>{t("{account}'s starting balance was changed by {amount}, to {initial}, so it matches the card.", { account, amount: w.money(balance.adjusted, true), initial: w.money(balance.initial_balance) })}</p>
      )}
      {!done && balance.difference !== 0 &&
        (match ? (
          <p>{t("Importing changes {account}'s starting balance by {amount}, to {suggested}.", { account, amount: w.money(balance.difference, true), suggested: w.money(balance.suggested_initial_balance) })}</p>
        ) : (
          <p>{t("They're {difference} apart.", { difference: w.money(Math.abs(balance.difference)) })}</p>
        ))}
    </div>
  );
}
