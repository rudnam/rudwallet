// Sorting records that have no category (often many, after an import):
// Claude suggests a category for each payee, the user checks the list, and
// only then are the records filed. Filing a payee also teaches the app its
// category for future emails.
import { useEffect, useState } from 'react';
import { api, errorMessage, type AIStatus, type CategorySuggestion } from '../../api';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { href } from '../../router';
import { useToast } from '../../toast';
import { useWallet } from '../../wallet';
import { t, tn, tx } from '../../i18n';

/** A suggestion as the user has it: the category they've settled on (0: leave it). */
type Row = CategorySuggestion & { choice: number };

export function OrganizePage() {
  const w = useWallet();
  const toast = useToast();
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [rows, setRows] = useState<Row[] | null>(null);
  const [remaining, setRemaining] = useState(0);
  // Payees the user chose to leave, which come first in the next batch.
  const [skipped, setSkipped] = useState(0);
  const [busy, setBusy] = useState<'' | 'asking' | 'applying'>('');
  const [error, setError] = useState('');

  useEffect(() => {
    api.ai().then(setStatus, (err) => setError(errorMessage(err)));
  }, []);

  async function ask(offset: number) {
    setBusy('asking');
    setError('');
    try {
      const res = await api.suggestCategories(offset);
      setRows(res.suggestions.map((s) => ({ ...s, choice: s.category_id ?? 0 })));
      setRemaining(res.remaining);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy('');
    }
  }

  async function apply() {
    if (!rows) return;
    const chosen = rows.filter((r) => r.choice);
    setBusy('applying');
    setError('');
    try {
      const { updated } = await api.applyCategories(chosen.map((r) => ({ payee: r.payee, kind: r.kind, category_id: r.choice })));
      toast(tn(updated, 'Sorted {n} record', 'Sorted {n} records'));
      const left = skipped + rows.length - chosen.length;
      setSkipped(left);
      setRows(null);
      setStatus(await api.ai());
      await w.refresh();
      if (remaining > 0) await ask(left);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy('');
    }
  }

  const set = (i: number, choice: number) => setRows((rs) => rs && rs.map((r, j) => (j === i ? { ...r, choice } : r)));
  const chosen = rows?.filter((r) => r.choice) ?? [];
  const records = chosen.reduce((n, r) => n + r.count, 0);

  return (
    <section className="stack loose">
      <header className="stack tight">
        <a className="back" href={href('settings', { section: 'claude' })}>
          <Icon d="M15 5l-7 7 7 7" className="inline-icon" /> Claude
        </a>
        <h1>{t('Sort records')}</h1>
        <p className="muted lede">{t('Claude suggests a category for each shop or payee. Check them, then file the records.')}</p>
      </header>

      {status && !status.has_key && (
        <div className="card stack empty">
          <span className="empty-mark" aria-hidden="true">
            <Icon d={icons.spark} className="glyph" />
          </span>
          <h2>{t('Add your Claude key first.')}</h2>
          <p className="muted small">
            {t('Sorting uses your own Anthropic API key.')} <a href={href('settings', { section: 'claude' })}>{t('Add it in Settings')}</a>
          </p>
        </div>
      )}

      {status?.has_key && rows === null && (
        <div className="card stack">
          {status.uncategorized === 0 ? (
            <p>{t('Every record has a category. Nothing to sort.')}</p>
          ) : (
            <>
              <p>
                {tx(status.uncategorized === 1 ? '{n} record has no category.' : '{n} records have no category.', {
                  n: <strong>{status.uncategorized}</strong>,
                })}
                {skipped > 0 && ` ${tn(skipped, 'You left {n} payee as they were.', 'You left {n} payees as they were.')}`}
              </p>
              <div className="row end">
                {skipped > 0 && (
                  <button type="button" className="secondary" disabled={!!busy} onClick={() => { setSkipped(0); ask(0); }}>
                    {t('Start over')}
                  </button>
                )}
                <button type="button" disabled={!!busy} onClick={() => ask(skipped)}>
                  <Icon d={icons.spark} className="inline-icon" /> {busy === 'asking' ? t('Claude is reading your payees…') : t('Suggest categories')}
                </button>
              </div>
            </>
          )}
        </div>
      )}

      {busy === 'asking' && rows === null && <p className="muted small">{t('This can take up to a minute for a long list.')}</p>}

      {rows && rows.length === 0 && (
        <div className="card stack">
          <p>{t('Nothing left to suggest.')}</p>
          <div className="row end">
            <button type="button" className="secondary" onClick={() => setRows(null)}>
              {t('Done')}
            </button>
          </div>
        </div>
      )}

      {rows && rows.length > 0 && (
        <>
          <ul className="list card flush" aria-label={t('Suggested categories')}>
            {rows.map((r, i) => {
              const cat = w.categories.find((c) => c.id === r.choice);
              return (
                <li key={`${r.kind}-${r.payee}`} className="suggestion" aria-label={r.payee}>
                  {cat ? <CategoryIcon icon={cat.icon} name={cat.name} color={cat.color} /> : <span className="cat-icon" aria-hidden="true">?</span>}
                  <span className="grow">
                    <span className="record-title">
                      {r.payee}
                      {r.kind === 'income' && <span className="badge quiet">{t('income')}</span>}
                    </span>
                    <span className="muted small">
                      {tn(r.count, '{n} record', '{n} records')} · {w.money(r.total)}
                      {r.category_id === null && ` · ${t('no suggestion')}`}
                    </span>
                  </span>
                  <select aria-label={t('Category for {name}', { name: r.payee })} value={r.choice} onChange={(e) => set(i, Number(e.target.value))}>
                    <option value={0}>{t('Leave as is')}</option>
                    {w.categories
                      .filter((c) => c.kind === r.kind)
                      .map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                  </select>
                </li>
              );
            })}
          </ul>
          <div className="row end wrap">
            {remaining > 0 && <span className="muted small grow">{tn(remaining, '{n} more payee after these.', '{n} more payees after these.')}</span>}
            <button type="button" className="secondary" disabled={!!busy} onClick={() => setRows(null)}>
              {t('Cancel')}
            </button>
            <button type="button" disabled={!!busy || chosen.length === 0} onClick={apply}>
              {busy === 'applying' ? t('Filing…') : tn(records, 'File {n} record', 'File {n} records')}
            </button>
          </div>
        </>
      )}

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </section>
  );
}
