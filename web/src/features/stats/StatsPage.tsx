// Spending and income over a week, a month, a year or any range, compared
// with the period before.
import { useEffect, useState } from 'react';
import { api, errorMessage, type Stats } from '../../api';
import { CategoryBars, DailyColumns, type BarItem } from '../../components/charts';
import { CategoryIcon, Icon, icons } from '../../components/Icon';
import { addMonths, dayKey, formatCompact, formatMonth } from '../../money';
import { navigate, type Route } from '../../router';
import { useWallet } from '../../wallet';

type Span = 'week' | 'month' | 'year' | 'custom';
const spans: { span: Span; label: string }[] = [
  { span: 'week', label: 'Week' },
  { span: 'month', label: 'Month' },
  { span: 'year', label: 'Year' },
  { span: 'custom', label: 'Range' },
];

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
  const past = [1, 2, 3].map((n) => samePoint(shift(period, -n), days));
  const [stats, setStats] = useState<{ now: Stats; past: Stats[] } | null>(null);
  const [error, setError] = useState('');

  const key = `${period.from.getTime()}-${period.to.getTime()}-${days}-${account}`;
  useEffect(() => {
    let current = true;
    setStats(null);
    Promise.all([api.stats(period.from, period.to, account), ...past.map((q) => api.stats(q.from, q.to, account))]).then(
      ([now, ...before]) => current && (setStats({ now, past: before }), setError('')),
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
          <span className="eyebrow">Stats</span>
          <h1>{periodTitle(period)}</h1>
        </div>
      </header>

      <div className="stack">
        <div className="segmented four" role="radiogroup" aria-label="Period">
          {spans.map((o) => (
            <label key={o.span} className={period.span === o.span ? 'on' : ''}>
              <input type="radio" name="span" value={o.span} checked={period.span === o.span} onChange={() => changeSpan(o.span)} />
              {o.label}
            </label>
          ))}
        </div>
        {period.span === 'custom' ? (
          <div className="card range-picker">
            <label>
              <span className="muted small">From</span>
              <input
                type="date"
                value={dayKey(period.from)}
                max={dayKey(addDays(period.to, -1))}
                onChange={(e) => e.target.value && go({ ...period, from: parseDay(e.target.value) })}
              />
            </label>
            <label>
              <span className="muted small">To</span>
              <input
                type="date"
                value={dayKey(addDays(period.to, -1))}
                min={dayKey(period.from)}
                onChange={(e) => e.target.value && go({ ...period, to: addDays(parseDay(e.target.value), 1) })}
              />
            </label>
          </div>
        ) : (
          <div className="card month-picker">
            <button type="button" className="link" aria-label={`Previous ${period.span}`} onClick={() => go(shift(period, -1))}>
              ‹
            </button>
            <strong>{periodTitle(period)}</strong>
            <button
              type="button"
              className="link"
              aria-label={`Next ${period.span}`}
              disabled={period.to > new Date()}
              onClick={() => go(shift(period, 1))}
            >
              ›
            </button>
          </div>
        )}
        <div className="filters">
          <select
            aria-label="Account"
            value={account ?? ''}
            onChange={(e) => go(period, Number(e.target.value) || undefined)}
          >
            <option value="">All accounts</option>
            {w.accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <div className="card hero">
        <span className="muted">Spent</span>
        <span className="hero-figure">{s ? w.money(s.spent) : '…'}</span>
        {stats && <span className="muted small">{stats.past[0].days.length === 0
              ? `No records in ${periodName(past[0])} to compare with.`
              : comparison(stats.now.spent, stats.past[0].spent, past[0], ongoing, w.money)}</span>}
        <div className="tiles">
          <Tile label="Income" value={s ? w.money(s.income) : '…'} className="income" />
          <Tile label="Net" value={s ? w.money(s.income - s.spent, true) : '…'} />
          <Tile label="Per day" value={s && days > 0 ? w.money(Math.round(s.spent / days)) : '–'} />
        </div>
      </div>

      {stats && days > 0 && <Trends now={stats.now} past={stats.past} period={period} days={days} ongoing={ongoing} />}

      <div className="card stack">
        <h2>Spending over time</h2>
        {series === null ? (
          <p className="muted">Loading…</p>
        ) : (
          <DailyColumns
            values={series.values}
            labelFor={series.labelFor}
            tick={series.tick}
            label={`Spending per ${series.step}`}
            hint={`Point at a ${series.step} to see its spending.`}
            format={(n) => w.money(n)}
            compact={(n) => formatCompact(n, w.settings)}
          />
        )}
      </div>

      <div className="card stack">
        <h2>Where it went</h2>
        {s === undefined ? (
          <p className="muted">Loading…</p>
        ) : expenses.length === 0 ? (
          <p className="muted">No spending recorded in this period.</p>
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

      {incomes.length > 0 && (
        <div className="card stack">
          <h2>Where it came from</h2>
          <CategoryBars items={incomes} format={(n) => w.money(n)} label="Income by category" />
        </div>
      )}
    </section>
  );
}

function Tile({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div className="tile">
      <span className="muted small">{label}</span>
      <strong className={className}>{value}</strong>
    </div>
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

function periodTitle(p: Period): string {
  if (p.span === 'month') return formatMonth(p.from);
  if (p.span === 'year') return String(p.from.getFullYear());
  return new Intl.DateTimeFormat(undefined, dayFormat).formatRange(p.from, addDays(p.to, -1));
}

/** The period cut off after its first n days. */
function samePoint(p: Period, n: number): Period {
  const end = addDays(p.from, n);
  return { ...p, to: end < p.to ? end : p.to };
}

/** "the week before", "September", "2025", "the 30 days before". */
function periodName(prev: Period, len = lengthInDays(prev)): string {
  return prev.span === 'week'
    ? 'the week before'
    : prev.span === 'month'
      ? prev.from.toLocaleDateString(undefined, { month: 'long' })
      : prev.span === 'year'
        ? String(prev.from.getFullYear())
        : `the ${len} days before`;
}

/** "¥1,200 less than September at this point", for the period before. */
function comparison(now: number, before: number, prev: Period, ongoing: boolean, money: (n: number) => string): string {
  const name = periodName(prev) + (ongoing ? ' at this point' : '');
  if (now === before) return before === 0 ? `Nothing spent here or in ${name}.` : `The same as ${name}.`;
  return `${money(Math.abs(now - before))} ${now < before ? 'less' : 'more'} than ${name}`;
}

// ---------- Trends ----------

/** Within this share of usual counts as "about usual". */
const usualBand = 0.1;

/**
 * Is this period's spending high? Compared with the average of the periods
 * before that have any records (up to three), each up to the same point.
 */
function Trends({ now, past, period, days, ongoing }: { now: Stats; past: Stats[]; period: Period; days: number; ongoing: boolean }) {
  const w = useWallet();
  const active = past.filter((s) => s.days.length > 0);
  const unit = period.span === 'custom' ? `${lengthInDays(period)}-day period` : period.span;
  const when = ongoing ? (period.span === 'year' || period.span === 'custom' ? ` by day ${days}` : ' by this point') : '';

  if (active.length === 0) {
    return (
      <div className="card stack">
        <h2>Trends</h2>
        <p className="muted">
          Not enough history yet. Once you have records from the {unit}s before this one, you’ll see how this {unit} compares.
        </p>
      </div>
    );
  }

  const usual = Math.round(active.reduce((t, s) => t + s.spent, 0) / active.length);
  const ratio = usual > 0 ? now.spent / usual - 1 : now.spent > 0 ? 1 : 0;
  const verdict = Math.abs(ratio) <= usualBand ? 'usual' : ratio > 0 ? 'more' : 'less';
  const basis = `the average of the last ${active.length === 1 ? unit : `${active.length} ${unit}s`}${ongoing ? ' at the same point' : ''}`;

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
    <div className="card stack trends" aria-label="Trends">
      <h2>Trends</h2>
      <div className={`trend-verdict ${verdict}`}>
        <span className="trend-mark" aria-hidden="true">
          <Icon d={verdict === 'more' ? icons.up : verdict === 'less' ? icons.down : icons.level} />
        </span>
        <div className="grow">
          <strong>
            {verdict === 'usual'
              ? `About your usual spending${when}`
              : `${Math.round(Math.abs(ratio) * 100)}% ${verdict} than usual${when}`}
          </strong>
          <span className="muted small block">
            {w.money(now.spent)} against {w.money(usual)}, {basis}.
          </span>
        </div>
      </div>
      {share !== null && (
        <p className="small">
          {share <= 100 ? (
            <>
              You spent <strong>{share}%</strong> of what came in{when}, keeping {w.money(now.income - now.spent)}.
            </>
          ) : (
            <>
              You spent <strong>{w.money(now.spent - now.income)}</strong> more than came in{when}.
            </>
          )}
        </p>
      )}
      {up.length > 0 && <Movers title="Higher than usual" items={up} />}
      {down.length > 0 && <Movers title="Lower than usual" items={down} />}
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
              <span className="grow ellipsis">{c?.name ?? 'No category'}</span>
              <span className="mover-figures">
                <strong>
                  {m.diff > 0 ? '+' : '−'}
                  {w.money(Math.abs(m.diff))}
                </strong>
                <span className="muted small block">
                  {w.money(m.amount)}, usually {w.money(m.before)}
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
      labelFor: (i) => dates[i].toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }),
      tick:
        p.span === 'week'
          ? (i) => dates[i].toLocaleDateString(undefined, { weekday: 'short' })
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
    labelFor: (i) => (byYear ? String(starts[i].getFullYear()) : formatMonth(starts[i])),
    tick: (i, slot) => {
      const d = starts[i];
      if (byYear) return slot >= 30 || i % 2 === 0 ? String(d.getFullYear()) : null;
      // A year's months by name; over longer ranges, where each year starts.
      if (months <= 12) return d.toLocaleDateString(undefined, { month: slot >= 26 ? 'short' : 'narrow' });
      return d.getMonth() === 0 || i === 0 ? String(d.getFullYear()) : null;
    },
  };
}

function categoryBars(s: Stats, kind: 'expense' | 'income', w: ReturnType<typeof useWallet>): BarItem[] {
  return s.categories
    .filter((c) => c.kind === kind)
    .map((t) => {
      const c = t.category_id === null ? undefined : w.category(t.category_id);
      return {
        key: t.category_id ?? 'none',
        label: c?.name ?? 'No category',
        icon: <CategoryIcon icon={c?.icon} name={c?.name} color={c?.color} size="small" />,
        amount: t.amount,
      };
    });
}
