// Sorting an import's records into categories before they're recorded: one
// row per category in the file (BudgetBakers) or shop (PayPay), with the
// app's suggestion picked. Ticking rows sets several at once.
import { useEffect, useState } from "react";
import { inOrder } from "../../categories";
import { api, errorMessage, type Category, type CategoryChoice, type ImportBatch, type ImportCategory, type ImportFileRow, type ImportOutlook, type ImportResult } from "../../api";
import { CategoryPicker } from "../../components/CategoryPicker";
import { CategoryIcon, Icon, icons } from "../../components/Icon";
import { useWallet } from "../../wallet";
import { serverText, t, tn, tx } from "../../i18n";
import { locale } from "../../language";
import { href } from "../../router";

/** Each group's choice as a select value: "c:<id>", "n:<new name>", or "" for none. */
export type Choices = Record<string, string>;

const keyOf = (g: ImportCategory) => `${g.kind}:${g.name}`;

/** The suggested choice for every group. */
export function suggestedChoices(groups: ImportCategory[]): Choices {
  return Object.fromEntries(
    groups.map((g) => [
      keyOf(g),
      g.category_id
        ? `c:${g.category_id}`
        : g.new_name
          ? `n:${g.new_name}`
          : "",
    ]),
  );
}

/** The choices as the import takes them. */
export function categoryChoices(
  groups: ImportCategory[],
  choices: Choices,
): CategoryChoice[] {
  return groups.map((g) => {
    const v = choices[keyOf(g)] ?? "";
    return {
      name: g.name,
      kind: g.kind,
      category_id: v.startsWith("c:") ? Number(v.slice(2)) : 0,
      new_name: v.startsWith("n:") ? v.slice(2) : "",
    };
  });
}

/** How many rows show before "Show all". */
const firstRows = 12;
/** Fewer rows than this are quicker to set one by one, without ticking. */
const bulkRows = 4;

export function ImportCategories({
  groups,
  newCategories,
  choices,
  onChange,
  by,
}: {
  groups: ImportCategory[];
  /** Default categories the user doesn't have, offered as new ones. */
  newCategories: Category[];
  choices: Choices;
  onChange: (c: Choices) => void;
  /** What a row is: a shop (PayPay) or a category in the file (BudgetBakers). */
  by: "shop" | "category";
}) {
  const shops = by === "shop";
  const w = useWallet();
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [picking, setPicking] = useState<"bulk" | ImportCategory | null>(null);
  const [showAll, setShowAll] = useState(groups.length <= firstRows + 3);
  const shown = showAll ? groups : groups.slice(0, firstRows);
  const pickedGroups = groups.filter((g) => picked.has(keyOf(g)));
  const all = picked.size === groups.length;
  const bulk = groups.length >= bulkRows;

  /** Existing categories of a kind, and new ones it could have: the missing defaults, and extra (the file's own name). */
  function names(kind: ImportCategory["kind"], extra?: string) {
    const existing = inOrder(w.categories).filter((c) => c.kind === kind);
    const fresh = newCategories
      .filter((c) => c.kind === kind)
      .map((c) => c.name);
    if (
      extra &&
      !fresh.includes(extra) &&
      !existing.some((c) => c.name.toLowerCase() === extra.toLowerCase())
    )
      fresh.unshift(extra);
    return { existing, fresh };
  }

  function look(
    v: string,
  ): { icon?: string; name?: string; color?: string } | null {
    if (v.startsWith("c:"))
      return w.categories.find((c) => c.id === Number(v.slice(2))) ?? null;
    if (v.startsWith("n:")) {
      const name = v.slice(2);
      return newCategories.find((c) => c.name === name) ?? { name };
    }
    return null;
  }

  /** Sets the ticked rows of the value's kind (every ticked row, for no category). */
  function setForPicked(value: string) {
    const [kind, v] = [
      value.slice(0, value.indexOf("|")),
      value.slice(value.indexOf("|") + 1),
    ];
    const next = { ...choices };
    for (const g of pickedGroups)
      if (!kind || g.kind === kind) next[keyOf(g)] = v;
    onChange(next);
    setPicked(new Set());
  }

  const toggle = (g: ImportCategory) => {
    const next = new Set(picked);
    if (!next.delete(keyOf(g))) next.add(keyOf(g));
    setPicked(next);
  };
  const kinds = (["expense", "income"] as const).filter((k) =>
    pickedGroups.some((g) => g.kind === k),
  );

  return (
    <>
    <div className="card flush mapper">
      {bulk && (
        <div
          className="mapper-bar"
          role="region"
          aria-label={shops ? t("Chosen shops") : t("Chosen categories")}
        >
          <label className="mapper-all">
            <input
              type="checkbox"
              aria-label={shops ? t("Choose all shops") : t("Choose all categories")}
              checked={all}
              ref={(el) => {
                if (el) el.indeterminate = picked.size > 0 && !all;
              }}
              onChange={() =>
                setPicked(all ? new Set() : new Set(groups.map(keyOf)))
              }
            />
            <span className="bulk-count" aria-live="polite">
              {picked.size === 0 ? (
                <span className="muted small">
                  {shops ? t("Tick shops to set several at once") : t("Tick categories to set several at once")}
                </span>
              ) : (
                t("{n} chosen", { n: picked.size })
              )}
            </span>
          </label>
          {picked.size > 0 && (
            <button
              type="button"
              className="picker-trigger"
              aria-label={t("Set category for chosen")}
              aria-haspopup="dialog"
              onClick={() => setPicking("bulk")}
            >
              <span className="ellipsis grow">{t("Set category…")}</span>
              <Icon d={icons.expand} className="picker-caret" />
            </button>
          )}
        </div>
      )}
      <ul className="list flush" aria-label={shops ? t("Categories by shop") : t("Categories by category")}>
        {shown.map((g) => {
          const key = keyOf(g);
          const v = choices[key] ?? "";
          const cat = look(v);
          return (
            <li
              key={key}
              className={bulk ? "suggestion pick" : "suggestion"}
              aria-label={g.name}
            >
              {bulk && (
                <input
                  type="checkbox"
                  aria-label={t("Choose {name}", { name: g.name })}
                  checked={picked.has(key)}
                  onChange={() => toggle(g)}
                />
              )}
              {cat ? (
                <CategoryIcon
                  icon={cat.icon}
                  name={cat.name}
                  color={cat.color}
                />
              ) : (
                <span className="cat-icon" aria-hidden="true">
                  ?
                </span>
              )}
              <span className="grow">
                <span className="record-title">
                  {g.name}
                  {g.kind === "income" && (
                    <span className="badge quiet">{t("income")}</span>
                  )}
                </span>
                <span className="muted small">
                  {tn(g.count, "{n} record", "{n} records")} · {w.money(g.total)}
                  {g.learned && v === `c:${g.category_id}` && ` · ${t("as before")}`}
                  {v.startsWith("n:") && ` · ${t("new category")}`}
                </span>
              </span>
              <button
                type="button"
                className={cat ? "picker-trigger" : "picker-trigger placeholder"}
                aria-label={t("Category for {name}", { name: g.name })}
                aria-haspopup="dialog"
                onClick={() => setPicking(g)}
              >
                <span className="ellipsis grow">{cat?.name ?? t("No category")}</span>
                <Icon d={icons.expand} className="picker-caret" />
              </button>
            </li>
          );
        })}
      </ul>
      {!showAll && (
        <button
          type="button"
          className="link mapper-more"
          onClick={() => setShowAll(true)}
        >
          {shops ? t("Show all {n} shops", { n: groups.length }) : t("Show all {n} categories", { n: groups.length })}
        </button>
      )}
    </div>
    {picking === "bulk" && (
      <CategoryPicker
        categories={w.categories}
        kind={kinds.length === 1 ? kinds[0] : undefined}
        selected={null}
        title={t("Set category for chosen")}
        extras={[
          { label: t("No category"), selected: false, onPick: () => setForPicked("|") },
          ...kinds.flatMap((k) =>
            names(k).fresh.map((n) => ({
              label: t("New category: {name}", { name: n }),
              selected: false,
              onPick: () => setForPicked(`${k}|n:${n}`),
            })),
          ),
        ]}
        onPick={(c) => setForPicked(`${c.kind}|c:${c.id}`)}
        onClose={() => setPicking(null)}
      />
    )}
    {picking !== null && picking !== "bulk" && (() => {
      const g = picking;
      const key = keyOf(g);
      const v = choices[key] ?? "";
      const { fresh } = names(g.kind, g.new_name || undefined);
      return (
        <CategoryPicker
          categories={w.categories}
          kind={g.kind}
          selected={v.startsWith("c:") ? Number(v.slice(2)) : null}
          title={t("Category for {name}", { name: g.name })}
          extras={[
            { label: t("No category"), selected: v === "", onPick: () => onChange({ ...choices, [key]: "" }) },
            ...fresh.map((n) => ({
              label: t("New category: {name}", { name: n }),
              selected: v === `n:${n}`,
              onPick: () => onChange({ ...choices, [key]: `n:${n}` }),
            })),
          ]}
          onPick={(c) => onChange({ ...choices, [key]: `c:${c.id}` })}
          onClose={() => setPicking(null)}
        />
      );
    })()}
    </>
  );
}

/** A day (YYYY-MM-DD) as imports show it. */
export const shortDate = (iso: string) => new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'short', day: 'numeric' });

/** What an import did, in one sentence. */
export function ImportSummary({ result }: { result: ImportResult }) {
  const w = useWallet();
  // Undoing the import: its records go to the trash, and can come back.
  const [undone, setUndone] = useState<number[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function undo() {
    if (!result.batch_id) return;
    setBusy(true);
    setError('');
    try {
      if (undone) {
        await api.restoreTransactions(undone);
        setUndone(null);
      } else {
        setUndone((await api.undoImport(result.batch_id)).ids);
      }
      importsChanged();
      await w.refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="notice stack" role="status">
      {undone ? (
        <p>{tn(undone.length, 'Undone: {n} record moved to the trash.', 'Undone: {n} records moved to the trash.')}</p>
      ) : (
        <p>
          {[
            tn(result.imported, 'Imported {n} record', 'Imported {n} records'),
            result.duplicates > 0 && t('skipped {n} already imported', { n: result.duplicates }),
            result.matched > 0 && t('skipped {n} already recorded from notifications or by hand', { n: result.matched }),
            result.skipped > 0 && t('left out {n} paid in ways not imported', { n: result.skipped }),
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
        </p>
      )}
      {!undone && result.problems.length > 0 && (
        <ul className="small">
          {result.problems.map((p) => (
            <li key={p}>{serverText(p)}</li>
          ))}
        </ul>
      )}
      {result.batch_id && (
        <div className="row end">
          <button type="button" className="secondary" onClick={undo} disabled={busy}>
            {busy ? t('Undoing…') : undone ? t('Bring them back') : t('Undo import')}
          </button>
        </div>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}

/** Tells the list of recent imports to ask again: after an import, an undo or a restore. */
export function importsChanged() {
  window.dispatchEvent(new Event('rudwallet:imports'));
}

/**
 * What importing does, before anything else: how many records it adds, and
 * how many it leaves out as already there. The server works these out by
 * running the import and rolling it back.
 */
export function ImportOutlookCard({ outlook, from, to, checking }: { outlook: ImportOutlook; from?: string | null; to?: string | null; checking?: boolean }) {
  const skipped = outlook.skipped ?? 0;
  return (
    <div className="import-outlook" aria-busy={checking || undefined}>
      <p className="import-outlook-title">
        {outlook.new > 0 ? tn(outlook.new, '{n} new record to add', '{n} new records to add') : t('Nothing new to add')}
        {checking && <span className="muted small"> · {t('Counting again for your choices…')}</span>}
      </p>
      {from && to && <p className="small muted">{shortDate(from) === shortDate(to) ? shortDate(from) : `${shortDate(from)} – ${shortDate(to)}`}</p>}
      <ul className="import-outlook-lines">
        {outlook.duplicates > 0 && <li className="done">{tn(outlook.duplicates, '{n} is already in RudWallet from an earlier import', '{n} are already in RudWallet from an earlier import')}</li>}
        {outlook.matched > 0 && <li className="done">{tn(outlook.matched, '{n} is already recorded another way (a notification, by hand, or another import)', '{n} are already recorded another way (a notification, by hand, or another import)')}</li>}
        {skipped > 0 && <li className="out">{tn(skipped, '{n} is paid in a way you’re not importing', '{n} are paid in ways you’re not importing')}</li>}
      </ul>
      <p className="small muted">{t('What’s already in RudWallet is never added again, and you can undo the import afterwards.')}</p>
    </div>
  );
}

const rowGroups: { status: ImportFileRow['status']; label: (n: number) => string; open: boolean }[] = [
  { status: 'new', label: (n) => t('To add · {n}', { n }), open: true },
  { status: 'imported', label: (n) => t('Already in RudWallet · {n}', { n }), open: false },
  { status: 'matched', label: (n) => t('Already recorded another way · {n}', { n }), open: false },
  { status: 'skipped', label: (n) => t('Not imported · {n}', { n }), open: false },
];

/** A file's rows by what becomes of them: those to add open, those already there folded away. */
export function FileRows({ rows }: { rows: ImportFileRow[] }) {
  const w = useWallet();
  return (
    <div className="stack import-rows">
      <h3>{t('In the file')}</h3>
      {rowGroups.map((g) => {
        const these = rows.filter((r) => r.status === g.status);
        if (these.length === 0) return null;
        return (
          <details key={g.status} open={g.open} className={`import-group ${g.status}`}>
            <summary>{g.label(these.length)}</summary>
            <ul className="import-row-list">
              {these.map((r, i) => (
                <li key={i} className={g.status === 'new' ? undefined : 'muted'}>
                  <span className="import-row-text">
                    {r.payee || r.note || t(r.kind === 'transfer' ? 'Transfer' : r.kind === 'expense' ? 'Expense' : 'Income')}
                    <span className="small muted">
                      {' '}
                      · {new Date(r.occurred_at).toLocaleString(locale(), r.all_day ? { month: 'short', day: 'numeric', timeZone: 'Asia/Tokyo' } : { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })} · {r.to_account ? `${r.account} → ${r.to_account}` : r.account}
                    </span>
                  </span>
                  <span className="amount">{w.money(r.kind === 'expense' ? -r.amount : r.amount, r.kind !== 'transfer')}</span>
                </li>
              ))}
            </ul>
          </details>
        );
      })}
    </div>
  );
}

const sourceNames: Record<ImportBatch['source'], () => string> = {
  paypay: () => 'PayPay',
  mufg: () => 'MUFG',
  iccard: () => t('IC card'),
  budgetbakers: () => 'BudgetBakers',
  vpass: () => 'Vpass',
};

/** The latest imports, each with what it added and found already there, and Undo. */
export function RecentImports() {
  const w = useWallet();
  const [list, setList] = useState<ImportBatch[]>([]);
  const [busy, setBusy] = useState<number | null>(null);
  const [message, setMessage] = useState<{ text: string; ids?: number[] } | null>(null);
  useEffect(() => {
    let live = true;
    const load = () =>
      api.imports().then(
        (r) => live && setList(r.imports),
        () => {},
      );
    load();
    window.addEventListener('rudwallet:imports', load);
    return () => {
      live = false;
      window.removeEventListener('rudwallet:imports', load);
    };
  }, []);
  async function undo(b: ImportBatch) {
    setBusy(b.id);
    setMessage(null);
    try {
      const { ids } = await api.undoImport(b.id);
      setMessage({ text: tn(ids.length, 'Import undone: {n} record moved to the trash', 'Import undone: {n} records moved to the trash'), ids });
      importsChanged();
      await w.refresh();
    } catch (err) {
      setMessage({ text: errorMessage(err) });
    } finally {
      setBusy(null);
    }
  }
  async function restore(ids: number[]) {
    setMessage(null);
    await api.restoreTransactions(ids);
    importsChanged();
    await w.refresh();
  }
  if (list.length === 0) return null;
  return (
    <div className="card stack">
      <h2>{t('Recent imports')}</h2>
      <ul className="import-history">
        {list.map((b) => {
          const already = b.duplicates + b.matched;
          return (
            <li key={b.id} className={b.undone_at ? 'muted' : undefined}>
              <span className="stack tight">
                <strong>
                  {sourceNames[b.source]()}
                  {b.label && ` ••${b.label}`}
                </strong>
                <span className="small muted">
                  {new Date(b.created_at).toLocaleString(locale(), { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                  {b.from && b.to && ` · ${shortDate(b.from) === shortDate(b.to) ? shortDate(b.from) : `${shortDate(b.from)} – ${shortDate(b.to)}`}`}
                </span>
                <span className="small muted">
                  {[tn(b.imported, '{n} added', '{n} added'), already > 0 && tn(already, '{n} already there', '{n} already there')].filter(Boolean).join(' · ')}
                </span>
              </span>
              {b.undone_at ? (
                <span className="small muted">{t('Undone')}</span>
              ) : (
                b.remaining > 0 && (
                  <button type="button" className="secondary" onClick={() => undo(b)} disabled={busy !== null}>
                    {busy === b.id ? t('Undoing…') : t('Undo')}
                  </button>
                )
              )}
            </li>
          );
        })}
      </ul>
      {message && (
        <p role="status" className="notice row">
          <span>{message.text}</span>
          {message.ids && (
            <button type="button" className="link" onClick={() => restore(message.ids!)}>
              {t('Bring them back')}
            </button>
          )}
        </p>
      )}
    </div>
  );
}
