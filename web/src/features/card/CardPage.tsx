// #/card: what a transit IC card tapped to the phone opens (native.ts). The
// balance comes first, read from the card itself, so it shows at once and
// offline; then the server says what's new. A card imported before, with
// nothing lost since, imports its new entries in one tap with the choices
// remembered from last time; otherwise the full import preview opens
// (Settings › Import › IC card), which also "Review first" opens.
import { useEffect, useState } from 'react';
import { api, errorMessage, type ICCardRead, type ICPreview, type ICResult, type ICRow } from '../../api';
import { icCardReader, keepICCard, onICCardTapped, readICCard, takeTappedICCard, type ICCardTap } from '../../native';
import { navigate } from '../../router';
import { useWallet } from '../../wallet';
import { t, tn, type Key } from '../../i18n';
import { ImportSummary, categoryChoices, shortDate, suggestedChoices } from '../settings/ImportCategories';

/** Why a card couldn't be read, by the reader's error code. */
const readErrors: Record<string, Key> = {
  'nfc-off': 'NFC is turned off.',
  'not-transit': "That isn't a transit IC card. Suica, PASMO, ICOCA and the other cards that work at ticket gates can be read.",
  lost: 'The card was moved away too soon. Hold it still until the phone beeps.',
};

/** The card's newest entry, decoded here: its balance and day (block layout: internal/wallet/importic.go). */
function newest(read: ICCardRead): { balance: number; day: string } | null {
  for (const hex of read.history) {
    const b = (i: number) => parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    if (hex.length !== 32 || (b(4) === 0 && b(5) === 0)) continue;
    const y = 2000 + (b(4) >> 1);
    const m = ((b(4) & 1) << 3) | (b(5) >> 5);
    const d = b(5) & 0x1f;
    return { balance: b(10) | (b(11) << 8), day: `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}` };
  }
  return null;
}

export function CardPage() {
  const w = useWallet();
  const [read, setRead] = useState<ICCardRead | null>(null);
  const [preview, setPreview] = useState<ICPreview | null>(null);
  const [result, setResult] = useState<ICResult | null>(null);
  const [reader, setReader] = useState<'ready' | 'off' | null>(null);
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function show(tap: ICCardTap) {
    setResult(null);
    setPreview(null);
    if ('error' in tap) {
      setError(tap.error in readErrors ? t(readErrors[tap.error]) : tap.error);
      return;
    }
    setError('');
    setRead(tap);
    try {
      setPreview(await api.previewICCard(tap));
    } catch (err) {
      setError(t("Couldn't check for new entries: {error}", { error: errorMessage(err) }));
    }
  }

  useEffect(() => {
    icCardReader().then(setReader);
    // The card whose tap opened this, then any tapped while it's open.
    const first = takeTappedICCard();
    if (first) void show(first);
    return onICCardTapped((tap) => void show(tap));
  }, []);

  async function readOne() {
    setWaiting(true);
    try {
      await show(await readICCard());
    } catch (err) {
      const code = (err as { code?: string }).code ?? '';
      if (code !== 'cancelled') setError(code in readErrors ? t(readErrors[code]) : errorMessage(err));
    } finally {
      setWaiting(false);
    }
  }

  /** The full import preview, to choose accounts and categories first. */
  function review() {
    if (!read) return;
    keepICCard(read);
    navigate('settings', { section: 'import' });
  }

  /** Imports the new entries with the accounts and categories remembered from last time. */
  async function importNew() {
    if (!read || !preview) return;
    setBusy(true);
    setError('');
    try {
      const accounts = Object.fromEntries(preview.accounts.map((a) => [a.role, a.account_id ?? 0]));
      const res = await api.importICCard(read, accounts, categoryChoices(preview.categories, suggestedChoices(preview.categories)), false);
      setResult(res);
      setPreview({ ...preview, new: 0, rows: res.rows.map((r) => (r.status === 'new' ? { ...r, status: 'imported' } : r)), balance: res.balance });
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const top = read ? newest(read) : null;
  // Read and imported before, with nothing lost in between: what to choose is known.
  const known = !!preview && preview.accounts[0]?.account_id !== null && !preview.gap && preview.rows.some((r) => r.status === 'imported');
  const account = w.accounts.find((a) => a.id === preview?.balance.account_id)?.name ?? '';
  return (
    <section className="stack">
      <header className="page-head">
        <div>
          {preview && <span className="eyebrow">{t('Card ••{card}', { card: preview.card })}</span>}
          <h1>{t('IC card')}</h1>
        </div>
      </header>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!read && (
        <div className="card stack">
          <p>{t('Tap a transit IC card to the back of the phone to see its balance.')}</p>
          {reader === 'ready' &&
            (waiting ? (
              <p className="muted" role="status">
                {t('Hold the card to the back of the phone…')}
              </p>
            ) : (
              <div className="row">
                <button type="button" onClick={readOne}>
                  {t('Read a card')}
                </button>
              </div>
            ))}
        </div>
      )}
      {top && (
        <div className="card hero">
          <span className="muted">{t('Balance on the card')}</span>
          <span className="hero-figure">{w.money(top.balance)}</span>
          <span className="muted small">{t('Last used {day}', { day: shortDate(top.day) })}</span>
        </div>
      )}
      {read && !preview && !error && <p className="muted">{t('Checking for new entries…')}</p>}
      {preview && preview.new > 0 && (
        <div className="card stack">
          <p>
            {preview.rows.some((r) => r.status === 'imported')
              ? tn(preview.new, '{n} new entry since you last imported this card.', '{n} new entries since you last imported this card.')
              : tn(preview.new, "{n} entry isn't imported yet.", "{n} entries aren't imported yet.")}
          </p>
          {preview.gap && <p className="notice warn">{t('The card was used more often than it keeps since it was last read, so some entries are lost.')}</p>}
          <div className="row end">
            {known && (
              <button type="button" className="secondary" onClick={review} disabled={busy}>
                {t('Review first')}
              </button>
            )}
            <button type="button" onClick={known ? importNew : review} disabled={busy}>
              {busy ? t('Importing…') : tn(preview.new, 'Import {n} entry', 'Import {n} entries')}
            </button>
          </div>
        </div>
      )}
      {preview && preview.new === 0 && !result && <p className="notice">{t("All of this card's entries are imported.")}</p>}
      {result && (
        <>
          <ImportSummary result={result} />
          {result.balance.difference !== 0 && (
            <p className="notice warn">
              {t("{account} in the app is {difference} off the card's balance.", { account, difference: w.money(Math.abs(result.balance.difference)) })}
            </p>
          )}
        </>
      )}
      {preview && (
        <div className="card stack">
          <h2>{t('Latest entries')}</h2>
          <ul className="list rows" aria-label={t('Card entries')}>
            {[...preview.rows].reverse().map((r) => (
              <Entry key={`${r.date}:${r.seq}`} row={r} />
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

const statusText: Partial<Record<ICRow['status'], Key>> = {
  new: 'New',
  imported: 'Imported',
  matched: 'Already recorded',
};

function Entry({ row: r }: { row: ICRow }) {
  const w = useWallet();
  const what = r.status === 'start' ? t('Start: amount unknown') : r.kind === 'transfer' ? t('Top-up') : (r.payee ?? r.group ?? '');
  const status = statusText[r.status];
  return (
    <li className="row">
      <span className="grow stack tight">
        <span>{what}</span>
        <span className="muted small">
          {shortDate(r.date)}
          {r.time && ` ${r.time}`}
          {status && ` · ${t(status)}`}
        </span>
      </span>
      {r.status !== 'start' && r.status !== 'none' && <strong className={r.amount > 0 ? 'amount income' : 'amount'}>{w.money(r.amount, true)}</strong>}
    </li>
  );
}
