// Sorting an import's records into categories before they're recorded: one
// row per category in the file (BudgetBakers) or shop (PayPay), with the
// app's suggestion picked. Ticking rows sets several at once.
import { useState } from "react";
import type { Category, CategoryChoice, ImportCategory, ImportResult } from "../../api";
import { CategoryIcon } from "../../components/Icon";
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
  const [showAll, setShowAll] = useState(groups.length <= firstRows + 3);
  const shown = showAll ? groups : groups.slice(0, firstRows);
  const pickedGroups = groups.filter((g) => picked.has(keyOf(g)));
  const all = picked.size === groups.length;
  const bulk = groups.length >= bulkRows;

  /** Existing categories of a kind, and new ones it could have: the missing defaults, and extra (the file's own name). */
  function names(kind: ImportCategory["kind"], extra?: string) {
    const existing = w.categories.filter((c) => c.kind === kind);
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
            <select
              aria-label={t("Set category for chosen")}
              value=""
              onChange={(e) => e.target.value && setForPicked(e.target.value)}
            >
              <option value="">{t("Set category…")}</option>
              <option value="|">{t("No category")}</option>
              {kinds.map((k) => {
                const { existing, fresh } = names(k);
                const title = k === "expense" ? t("Expenses") : t("Income");
                return [
                  <optgroup key={k} label={title}>
                    {existing.map((c) => (
                      <option key={c.id} value={`${k}|c:${c.id}`}>
                        {c.name}
                      </option>
                    ))}
                  </optgroup>,
                  fresh.length > 0 && (
                    <optgroup
                      key={`${k}-new`}
                      label={k === "expense" ? t("New expense categories") : t("New income categories")}
                    >
                      {fresh.map((n) => (
                        <option key={n} value={`${k}|n:${n}`}>
                          {n}
                        </option>
                      ))}
                    </optgroup>
                  ),
                ];
              })}
            </select>
          )}
        </div>
      )}
      <ul className="list flush" aria-label={shops ? t("Categories by shop") : t("Categories by category")}>
        {shown.map((g) => {
          const key = keyOf(g);
          const v = choices[key] ?? "";
          const cat = look(v);
          const { existing, fresh } = names(g.kind, g.new_name || undefined);
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
              <select
                aria-label={t("Category for {name}", { name: g.name })}
                value={v}
                onChange={(e) =>
                  onChange({ ...choices, [key]: e.target.value })
                }
              >
                <option value="">{t("No category")}</option>
                <optgroup label={t("Your categories")}>
                  {existing.map((c) => (
                    <option key={c.id} value={`c:${c.id}`}>
                      {c.name}
                    </option>
                  ))}
                </optgroup>
                {fresh.length > 0 && (
                  <optgroup label={t("New categories")}>
                    {fresh.map((n) => (
                      <option key={n} value={`n:${n}`}>
                        {n}
                      </option>
                    ))}
                  </optgroup>
                )}
              </select>
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
  );
}

/** A day (YYYY-MM-DD) as imports show it. */
export const shortDate = (iso: string) => new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'short', day: 'numeric' });

/** What an import did, in one sentence. */
export function ImportSummary({ result }: { result: ImportResult }) {
  return (
    <div className="notice" role="status">
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
