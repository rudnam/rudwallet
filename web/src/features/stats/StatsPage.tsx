// Spending and income over a week, a month, a year or any range, compared
// with the period before.
import { useEffect, useState } from 'react';
import { api, errorMessage, type Budget, type Stats } from '../../api';
import { CategoryBars, DailyColumns, type BarItem } from '../../components/charts';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { addMonths, dayKey, formatCompact, formatMonth } from '../../money';
import { Figure } from '../../components/Figure';
import { href, navigate, type Route } from '../../router';
import { useWallet } from '../../wallet';
import { t, tn, tx, type Key } from '../../i18n';
import { locale } from '../../language';

type Span = 'week' | 'month' | 'year' | 'custom';
const spans: { span: Span; label: Key }[] = [
  { span: 'week', label: 'Week' },
  { span: 'month', label: 'Month' },
  { span: 'year', label: 'Year' },
  { span: 'custom', label: 'Range' },
];

const previousLabel: Record<Span, Key> = { week: 'Previous week', month: 'Previous month', year: 'Previous year', custom: 'Previous period' };
const nextLabel: Record<Span, Key> = { week: 'Next week', month: 'Next month', year: 'Next year', custom: 'Next period' };

/** A period: from is its first moment, to the first moment after it (local time). */
interface Period {
  span: Span;
  from: Date;
  to: Date;
}

export function StatsPage({ route }: { route: Route }) {
  const w = useWallet();
  const p = route.params;
  const period = periodFrom(p);
  const account = Number(p.get('account')) || undefined;
  const days = elapsedDays(period);
  const ongoing = days > 0 && days < lengthInDays(period);
  // The three periods before, each only up to the same point as this one,
  // so half a month is compared with the first halves of earlier months.
  const past = comparedWith(period);
  // The month in progress, across all accounts, also shows where it's heading.
  const ahead = period.span === 'month' && ongoing && !account;
  const [stats, setStats] = useState<{ now: Stats; past: Stats[] } | null>(() => seenStats(period, account));
  const [budgets, setBudgets] = useState<Budget[]>(() => api.seen.budgets() ?? []);
  const [error, setError] = useState('');

  useEffect(() => {
    if (ahead) api.budgets().then(setBudgets, () => {});
  }, [ahead]);

  const key = `${period.from.getTime()}-${period.to.getTime()}-${days}-${account}`;
  useEffect(() => {
    let current = true;
    // A period seen before shows at once, while it reloads.
    setStats(seenStats(period, account));
    const ask = (q: Period) => api.stats(q.from, q.to, account);
    Promise.all([ask(period), ...past.map(ask)]).then(
      ([now, ...before]) => {
        if (!current) return;
        setStats({ now, past: before });
        setError('');
        // Load the periods either side, so stepping to them is instant.
        const neighbours = [shift(period, -1), shift(period, 1)].filter((q) => q.from <= new Date());
        for (const q of neighbours) prefetchStats(q, account);
      },
      (err) => current && setError(errorMessage(err)),
    );
    return () => {
      current = false;
    };
    // The key holds everything the request depends on.
  }, [key]);

  function go(next: Period, acct = account) {
    const params: Record<string, string | number> = { span: next.span, start: dayKey(next.from) };
    if (next.span === 'custom') params.end = dayKey(addDays(next.to, -1));
    if (acct) params.account = acct;
    navigate('stats', params);
  }

  function changeSpan(span: Span) {
    // Keep looking at today if it's in view, else where the period starts.
    const now = new Date();
    const anchor = now >= period.from && now < period.to ? now : period.from;
    if (span === 'custom') go({ span, from: period.from, to: period.to });
    else go(periodAt(span, anchor));
  }

  const s = stats?.now;
  const expenses = s ? categoryBars(s, 'expense', w) : [];
  const incomes = s ? categoryBars(s, 'income', w) : [];
  const series = s ? buckets(period, s) : null;

  return (
    <section className="stack loose">
      <header className="page-head">
        <div>
          <h1>{t('Insights')}</h1>
        </div>
        {/* Which accounts count: all of them unless one is picked. */}
          <select
            aria-label={t('Account')}
            className={account ? 'chip-select set' : 'chip-select'}
            value={account ?? ''}
            onChange={(e) => go(period, Number(e.target.value) || undefined)}
          >
            <option value="">{t('All accounts')}</option>
            {w.accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
      </header>

      <div className="stack">
        <div className="segmented four" role="radiogroup" aria-label={t('Period')}>
          {spans.map((o) => (
            <label key={o.span} className={period.span === o.span ? 'on' : ''}>
              <input type="radio" name="span" value={o.span} checked={period.span === o.span} onChange={() => changeSpan(o.span)} />
              {t(o.label)}
            </label>
          ))}
        </div>
        {period.span === 'custom' ? (
          <div className="card range-picker">
            <label>
              <span className="muted small">{t('From')}</span>
              <input
                type="date"
                value={dayKey(period.from)}
                max={dayKey(addDays(period.to, -1))}
                onChange={(e) => e.target.value && go({ ...period, from: parseDay(e.target.value) })}
              />
            </label>
            <label>
              <span className="muted small">{t('To')}</span>
              <input
                type="date"
                value={dayKey(addDays(period.to, -1))}
                min={dayKey(period.from)}
                onChange={(e) => e.target.value && go({ ...period, to: addDays(parseDay(e.target.value), 1) })}
              />
            </label>
          </div>
        ) : (
          <div className="month-picker">
            <button type="button" className="link" aria-label={t(previousLabel[period.span])} onClick={() => go(shift(period, -1))}>
              ‹
            </button>
            <strong>{periodTitle(period)}</strong>
            <button
              type="button"
              className="link"
              aria-label={t(nextLabel[period.span])}
              disabled={period.to > new Date()}
              onClick={() => go(shift(period, 1))}
            >
              ›
            </button>
          </div>
        )}
      </div>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="hero plain balance">
        <span className="muted">{t('Spent')}</span>
        <span className="hero-figure">{s ? <Figure amount={s.spent} /> : '…'}</span>
        {/* The same line under the figure as on Home. Income and net say nothing new without income. */}
        <span className="balance-month">
          <span>
            {t('Per day')} <strong>{s && days > 0 ? w.money(Math.round(s.spent / days)) : '–'}</strong>
          </span>
          {s && s.income > 0 && (
            <>
              <span>
                {t('Income')} <strong>{w.money(s.income)}</strong>
              </span>
              <span>
                {t('Net')} <strong>{w.money(s.income - s.spent, true)}</strong>
              </span>
            </>
          )}
        </span>
      </div>

      {stats && ahead && (
        <MonthAhead now={stats.now} budgets={budgets} days={days} period={period} />
      )}

      <div className="card stack">
        <h2>{t('Where it went')}</h2>
        {s === undefined ? (
          <p className="muted">{t('Loading…')}</p>
        ) : expenses.length === 0 ? (
          <p className="muted">{t('No spending recorded in this period.')}</p>
        ) : (
          <CategoryBars
            items={expenses}
            format={(n) => w.money(n)}
            // Records are listed a month at a time.
            onSelect={
              period.span === 'month'
                ? (k) =>
                    navigate('records', {
                      category: k,
                      month: dayKey(period.from).slice(0, 7),
                      ...(account ? { account } : {}),
                    })
                : undefined
            }
          />
        )}
      </div>

      {stats && days > 0 && <Trends now={stats.now} past={stats.past} period={period} days={days} ongoing={ongoing} />}

      <div className="card stack">
        <h2>{t('Spending over time')}</h2>
        {series === null ? (
          <p className="muted">{t('Loading…')}</p>
        ) : (
          <DailyColumns
            values={series.values}
            labelFor={series.labelFor}
            tick={series.tick}
            label={t(stepLabels[series.step].label)}
            hint={t(stepLabels[series.step].hint)}
            format={(n) => w.money(n)}
            compact={(n) => formatCompact(n, w.settings)}
          />
        )}
      </div>

      {incomes.length > 0 && (
        <div className="card stack">
          <h2>{t('Where it came from')}</h2>
          <CategoryBars items={incomes} format={(n) => w.money(n)} label={t('Income by category')} />
        </div>
      )}
    </section>
  );
}

// ---------- Periods ----------

function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
}

/** YYYY-MM-DD as local midnight. */
function parseDay(s: string): Date {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function validDay(s: string | null): Date | null {
  if (!s || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const d = parseDay(s);
  return isNaN(d.getTime()) ? null : d;
}

/** The week (from Monday), month or year containing d. */
function periodAt(span: Exclude<Span, 'custom'>, d: Date): Period {
  if (span === 'week') {
    const from = addDays(new Date(d.getFullYear(), d.getMonth(), d.getDate()), -((d.getDay() + 6) % 7));
    return { span, from, to: addDays(from, 7) };
  }
  if (span === 'year') {
    const from = new Date(d.getFullYear(), 0, 1);
    return { span, from, to: addMonths(from, 12) };
  }
  const from = new Date(d.getFullYear(), d.getMonth(), 1);
  return { span, from, to: addMonths(from, 1) };
}

function periodFrom(p: URLSearchParams): Period {
  const span = (['week', 'month', 'year', 'custom'] as const).find((s) => s === p.get('span')) ?? 'month';
  const start = validDay(p.get('start')) ?? new Date();
  if (span !== 'custom') return periodAt(span, start);
  const end = validDay(p.get('end'));
  if (!validDay(p.get('start')) || !end) return { span, ...lastDays(30) };
  const [a, b] = end < start ? [end, start] : [start, end];
  return { span, from: a, to: addDays(b, 1) };
}

/** The last n days, today included. */
function lastDays(n: number): { from: Date; to: Date } {
  const now = new Date();
  const to = addDays(new Date(now.getFullYear(), now.getMonth(), now.getDate()), 1);
  return { from: addDays(to, -n), to };
}

function lengthInDays(p: Period): number {
  return Math.round((p.to.getTime() - p.from.getTime()) / 86_400_000);
}

/** The period n steps later (or earlier): the next week, month or year, or a range of the same length. */
function shift(p: Period, n: number): Period {
  if (p.span === 'week') return { ...p, from: addDays(p.from, 7 * n), to: addDays(p.to, 7 * n) };
  if (p.span === 'month') return { ...p, from: addMonths(p.from, n), to: addMonths(p.to, n) };
  if (p.span === 'year') return { ...p, from: addMonths(p.from, 12 * n), to: addMonths(p.to, 12 * n) };
  const len = lengthInDays(p);
  return { ...p, from: addDays(p.from, len * n), to: addDays(p.to, len * n) };
}

/** Days of the period so far: a period that is still going counts up to today. */
function elapsedDays(p: Period): number {
  const end = p.to < lastDays(1).to ? p.to : lastDays(1).to;
  return Math.max(0, Math.round((end.getTime() - p.from.getTime()) / 86_400_000));
}

const dayFormat: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'short', day: 'numeric' };

/** "2026", or in Japanese 2026年. */
function yearName(d: Date): string {
  return d.toLocaleDateString(locale(), { year: 'numeric' });
}

function periodTitle(p: Period): string {
  if (p.span === 'month') return formatMonth(p.from);
  if (p.span === 'year') return yearName(p.from);
  return new Intl.DateTimeFormat(locale(), dayFormat).formatRange(p.from, addDays(p.to, -1));
}

/** The three periods before, each only up to the same point as this one. */
function comparedWith(p: Period): Period[] {
  const days = elapsedDays(p);
  return [1, 2, 3].map((n) => samePoint(shift(p, -n), days));
}

/** A period's stats and those it's compared with, if all were seen since the last change. */
function seenStats(p: Period, account?: number): { now: Stats; past: Stats[] } | null {
  const [now, ...past] = [p, ...comparedWith(p)].map((q) => api.seen.stats(q.from, q.to, account));
  return now && past.every((s) => s !== undefined) ? { now, past: past as Stats[] } : null;
}

/** Asks ahead for what a period's stats need; with no period, what Stats opens on (this month). */
export function prefetchStats(p: Period = periodAt('month', new Date()), account?: number) {
  for (const q of [p, ...comparedWith(p)]) api.prefetch.stats(q.from, q.to, account);
}

/** The period cut off after its first n days. */
function samePoint(p: Period, n: number): Period {
  const end = addDays(p.from, n);
  return { ...p, to: end < p.to ? end : p.to };
}
// ---------- Trends ----------

const notEnough: Record<Span, Key> = {
  week: 'Not enough history yet. Once you have records from the weeks before this one, you’ll see how this week compares.',
  month: 'Not enough history yet. Once you have records from the months before this one, you’ll see how this month compares.',
  year: 'Not enough history yet. Once you have records from the years before this one, you’ll see how this year compares.',
  custom: 'Not enough history yet. Once you have records from the {n} days before this range, you’ll see how it compares.',
};

/** "the last 3 months", "the last week", "the last 2 30-day periods". */
function lastPeriods(span: Span, n: number, len: number): string {
  switch (span) {
    case 'week':
      return tn(n, 'the last week', 'the last {n} weeks');
    case 'month':
      return tn(n, 'the last month', 'the last {n} months');
    case 'year':
      return tn(n, 'the last year', 'the last {n} years');
    default:
      return tn(n, 'the last {len}-day period', 'the last {n} {len}-day periods', { len });
  }
}

/** Within this share of usual counts as "about usual". */
const usualBand = 0.1;

/**
 * Is this period's spending high? Compared with the average of the periods
 * before that have any records (up to three), each up to the same point.
 */
function Trends({ now, past, period, days, ongoing }: { now: Stats; past: Stats[]; period: Period; days: number; ongoing: boolean }) {
  const w = useWallet();
  const active = past.filter((s) => s.days.length > 0);
  const len = lengthInDays(period);
  // "by this point", put before or after the sentence as the language needs.
  const when = ongoing ? (period.span === 'year' || period.span === 'custom' ? t('by day {n}', { n: days }) : t('by this point')) : '';

  if (active.length === 0) {
    return (
      <div className="card stack">
        <h2>{t('Trends')}</h2>
        <p className="muted">{t(notEnough[period.span], { n: len })}</p>
      </div>
    );
  }

  const usual = Math.round(active.reduce((t, s) => t + s.spent, 0) / active.length);
  const ratio = usual > 0 ? now.spent / usual - 1 : now.spent > 0 ? 1 : 0;
  const verdict = Math.abs(ratio) <= usualBand ? 'usual' : ratio > 0 ? 'more' : 'less';
  const last = lastPeriods(period.span, active.length, len);
  const basis = ongoing ? t('the average of {last} at the same point', { last }) : t('the average of {last}', { last });

  // Categories that moved most against their own usual.
  const usualBy = new Map<string, number>();
  for (const s of active)
    for (const c of s.categories)
      if (c.kind === 'expense') usualBy.set(String(c.category_id), (usualBy.get(String(c.category_id)) ?? 0) + c.amount / active.length);
  const nowBy = new Map(now.categories.filter((c) => c.kind === 'expense').map((c) => [String(c.category_id), c.amount]));
  // Ignore changes too small to matter: under 5% of usual spending.
  const floor = Math.max(usual * 0.05, 1);
  const moves = [...new Set([...usualBy.keys(), ...nowBy.keys()])]
    .map((k) => {
      const amount = nowBy.get(k) ?? 0;
      const before = Math.round(usualBy.get(k) ?? 0);
      return { key: k, amount, before, diff: amount - before };
    })
    .filter((m) => Math.abs(m.diff) >= floor && (m.before === 0 || Math.abs(m.diff) / m.before > 0.25));
  const up = moves.filter((m) => m.diff > 0).sort((a, b) => b.diff - a.diff).slice(0, 3);
  const down = moves.filter((m) => m.diff < 0).sort((a, b) => a.diff - b.diff).slice(0, 3);

  const share = now.income > 0 ? Math.round((now.spent / now.income) * 100) : null;

  return (
    <div className="card stack trends" aria-label={t('Trends')}>
      <h2>{t('Trends')}</h2>
      <div className={`trend-verdict ${verdict}`}>
        <span className="trend-mark" aria-hidden="true">
          <Icon d={verdict === 'more' ? icons.up : verdict === 'less' ? icons.down : icons.level} />
        </span>
        <div className="grow">
          <strong>
            {t(verdict === 'usual' ? 'About your usual spending {when}' : verdict === 'more' ? '{pct}% more than usual {when}' : '{pct}% less than usual {when}', {
              pct: Math.round(Math.abs(ratio) * 100),
              when,
            }).trim()}
          </strong>
          <span className="muted small block">
            {t('{now} against {usual}, {basis}.', { now: w.money(now.spent), usual: w.money(usual), basis })}
          </span>
        </div>
      </div>
      {share !== null && (
        <p className="small">
          {share <= 100
            ? tx(when ? 'You spent {share} of what came in {when}, keeping {kept}.' : 'You spent {share} of what came in, keeping {kept}.', {
                share: <strong>{share}%</strong>,
                when,
                kept: w.money(now.income - now.spent),
              })
            : tx(when ? 'You spent {over} more than came in {when}.' : 'You spent {over} more than came in.', {
                over: <strong>{w.money(now.spent - now.income)}</strong>,
                when,
              })}
        </p>
      )}
      {up.length > 0 && <Movers title={t('Higher than usual')} items={up} />}
      {down.length > 0 && <Movers title={t('Lower than usual')} items={down} />}
    </div>
  );
}

/**
 * The month in progress: where its spending is heading at the same rate per
 * day as so far, and how much each budget leaves to spend per day.
 */
function MonthAhead({ now, budgets, days, period }: { now: Stats; budgets: Budget[]; days: number; period: Period }) {
  const w = useWallet();
  const len = lengthInDays(period);
  const left = len - days + 1; // today included
  const heading = (spentIn: (s: Stats) => number) => Math.round((spentIn(now) / days) * len);
  const forBudget = (b: Budget) => (s: Stats) =>
    b.category_id === null
      ? s.spent
      : s.categories.filter((c) => c.kind === 'expense' && c.category_id === b.category_id).reduce((sum, c) => sum + c.amount, 0);
  const title = t('Rest of {month}', { month: period.from.toLocaleDateString(locale(), { month: 'long' }) });

  return (
    <div className="card stack month-ahead" aria-label={title}>
      <div className="section-head">
        <h2>{title}</h2>
        <a href={href('budgets')}>{budgets.length > 0 ? t('Edit budgets') : t('Set a budget')}</a>
      </div>
      <div>
        <strong className="block">{t('Heading for about {amount}', { amount: w.money(heading((s) => s.spent)) })}</strong>
        <span className="muted small block">
          {t('{spent} so far, at the same rate per day.', { spent: w.money(now.spent) })}
        </span>
      </div>
      {budgets.length > 0 && (
        <div className="stack tight">
          <h3 className="small muted">{tn(left, 'To stay within budget, {n} day left', 'To stay within budget, {n} days left')}</h3>
          <ul className="movers" aria-label={t('Per day to stay within budget')}>
            {budgets.map((b) => {
              const c = w.category(b.category_id);
              const spent = forBudget(b)(now);
              // The most a day that keeps within it, against the rate so far.
              const allowed = Math.floor((b.amount - spent) / left);
              const rate = Math.round(spent / days);
              return (
                <li key={b.id}>
                  {c ? (
                    <CategoryIcon icon={c.icon} name={c.name} color={c.color} size="small" />
                  ) : (
                    <span className="cat-icon small" aria-hidden="true">
                      <Icon d={icons.budgets} className="glyph" />
                    </span>
                  )}
                  <span className="grow ellipsis">{b.name}</span>
                  <span className="mover-figures">
                    {spent >= b.amount ? (
                      <strong className="over">{t('Over by {amount}', { amount: w.money(spent - b.amount) })}</strong>
                    ) : (
                      <strong>{t('Up to {amount} a day', { amount: w.money(allowed) })}</strong>
                    )}
                    <span className="muted small block">
                      {spent < b.amount && rate > allowed && <Icon d={icons.warning} className="inline-icon" />}{' '}
                      {t('Now {amount} a day', { amount: w.money(rate) })}
                    </span>
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}

function Movers({ title, items }: { title: string; items: { key: string; amount: number; before: number; diff: number }[] }) {
  const w = useWallet();
  return (
    <div className="stack tight">
      <h3 className="small muted">{title}</h3>
      <ul className="movers" aria-label={title}>
        {items.map((m) => {
          const c = m.key === 'null' ? undefined : w.category(Number(m.key));
          return (
            <li key={m.key}>
              <CategoryIcon icon={c?.icon} name={c?.name} color={c?.color} size="small" />
              <span className="grow ellipsis">{c?.name ?? t('No category')}</span>
              <span className="mover-figures">
                <strong>
                  {m.diff > 0 ? '+' : '−'}
                  {w.money(Math.abs(m.diff))}
                </strong>
                <span className="muted small block">
                  {t('{amount}, usually {before}', { amount: w.money(m.amount), before: w.money(m.before) })}
                </span>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// ---------- Series ----------

const stepLabels: Record<Series['step'], { label: Key; hint: Key }> = {
  day: { label: 'Spending per day', hint: 'Point at a day to see its spending.' },
  month: { label: 'Spending per month', hint: 'Point at a month to see its spending.' },
  year: { label: 'Spending per year', hint: 'Point at a year to see its spending.' },
};

interface Series {
  step: 'day' | 'month' | 'year';
  values: number[];
  labelFor: (i: number) => string;
  tick: (i: number, slot: number) => string | null;
}

/**
 * Spending per column: days for up to two months, then months for up to
 * three years, then years.
 */
function buckets(p: Period, s: Stats): Series {
  const len = lengthInDays(p);
  const spentOn = new Map(s.days.map((d) => [d.day, d.spent]));

  if (len <= 62) {
    const dates = Array.from({ length: len }, (_, i) => addDays(p.from, i));
    return {
      step: 'day',
      values: dates.map((d) => spentOn.get(dayKey(d)) ?? 0),
      labelFor: (i) => dates[i].toLocaleDateString(locale(), { weekday: 'short', month: 'short', day: 'numeric' }),
      tick:
        p.span === 'week'
          ? (i) => dates[i].toLocaleDateString(locale(), { weekday: 'short' })
          : (i, slot) => {
              const d = dates[i].getDate();
              // Day numbers every 5 or 10 days, plus where a range starts.
              return i === 0 || d % (slot < 14 ? 10 : 5) === 0 ? String(d) : null;
            },
    };
  }

  const last = addDays(p.to, -1);
  const months = (last.getFullYear() - p.from.getFullYear()) * 12 + last.getMonth() - p.from.getMonth() + 1;
  const byYear = months > 36;
  const starts: Date[] = [];
  if (byYear) for (let y = p.from.getFullYear(); y <= last.getFullYear(); y++) starts.push(new Date(y, 0, 1));
  else for (let i = 0; i < months; i++) starts.push(addMonths(p.from, i));
  const index = (day: string) => {
    const [y, m] = day.split('-').map(Number);
    return byYear ? y - starts[0].getFullYear() : (y - starts[0].getFullYear()) * 12 + m - 1 - starts[0].getMonth();
  };
  const values = new Array<number>(starts.length).fill(0);
  for (const d of s.days) {
    const i = index(d.day);
    if (i >= 0 && i < values.length) values[i] += d.spent;
  }
  return {
    step: byYear ? 'year' : 'month',
    values,
    labelFor: (i) => (byYear ? yearName(starts[i]) : formatMonth(starts[i])),
    tick: (i, slot) => {
      const d = starts[i];
      if (byYear) return slot >= 30 || i % 2 === 0 ? String(d.getFullYear()) : null;
      // A year's months by name; over longer ranges, where each year starts.
      if (months <= 12) return d.toLocaleDateString(locale(), { month: slot >= 26 ? 'short' : 'narrow' });
      return d.getMonth() === 0 || i === 0 ? String(d.getFullYear()) : null;
    },
  };
}

function categoryBars(s: Stats, kind: 'expense' | 'income', w: ReturnType<typeof useWallet>): BarItem[] {
  return s.categories
    .filter((c) => c.kind === kind)
    .map((total) => {
      const c = total.category_id === null ? undefined : w.category(total.category_id);
      return {
        key: total.category_id ?? 'none',
        label: c?.name ?? t('No category'),
        icon: <CategoryIcon icon={c?.icon} name={c?.name} color={c?.color} size="small" />,
        amount: total.amount,
      };
    });
}
