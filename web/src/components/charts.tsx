// Small, dependency-free charts. One series each, so one validated chart
// colour (--chart); status colours appear only in meters, always with an
// icon and words. Values are written out in text, so nothing is colour-only.
import { useEffect, useRef, useState } from 'react';

export interface BarItem {
  key: string | number;
  label: string;
  icon: string;
  amount: number;
}

/** Spending by category: a ranked bar list, value at each bar's tip. */
export function CategoryBars({
  items,
  format,
  onSelect,
}: {
  items: BarItem[];
  format: (n: number) => string;
  onSelect?: (key: BarItem['key']) => void;
}) {
  const max = Math.max(...items.map((i) => i.amount), 1);
  const total = items.reduce((s, i) => s + i.amount, 0);
  return (
    <ul className="bars" aria-label="Spending by category">
      {items.map((i) => {
        const share = total ? Math.round((i.amount / total) * 100) : 0;
        const content = (
          <>
            <span className="bar-label">
              <span aria-hidden="true">{i.icon}</span> {i.label}
            </span>
            <span className="bar-track" aria-hidden="true">
              <span className="bar-fill" style={{ width: `${Math.max((i.amount / max) * 100, 1)}%` }} />
            </span>
            <span className="bar-value">
              {format(i.amount)} <span className="muted small">{share}%</span>
            </span>
          </>
        );
        return (
          <li key={i.key} title={`${i.label}: ${format(i.amount)} (${share}% of spending)`}>
            {onSelect ? (
              <button type="button" className="bar-row" onClick={() => onSelect(i.key)}>
                {content}
              </button>
            ) : (
              <span className="bar-row">{content}</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** Spending per day of a month, with a tooltip on hover or focus. */
export function DailyColumns({
  values,
  labelFor,
  format,
  compact,
}: {
  values: number[];
  labelFor: (dayIndex: number) => string;
  format: (n: number) => string;
  compact: (n: number) => string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  // Drawn at its real width, so text stays readable on a phone.
  const box = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(640);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(240, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const H = 160;
  const top = 12;
  const left = 44;
  const bottom = 20;
  const max = niceMax(Math.max(...values, 0));
  const slot = (W - left) / values.length;
  const barW = Math.min(slot - 2, 24); // 2px surface gap, never over 24px
  const y = (v: number) => top + (H - top - bottom) * (1 - v / max);
  const ticks = [0, max / 2, max];

  return (
    <div className="columns" ref={box}>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Spending per day this month">
        {ticks.map((t) => (
          <g key={t}>
            <line className="grid" x1={left} x2={W} y1={y(t)} y2={y(t)} />
            <text className="tick" x={left - 6} y={y(t) + 4} textAnchor="end">
              {compact(t)}
            </text>
          </g>
        ))}
        {values.map((v, i) => {
          const x = left + i * slot + (slot - barW) / 2;
          const h = y(0) - y(v);
          return (
            <g key={i}>
              {v > 0 && <path className={hover === i ? 'col on' : 'col'} d={roundedTop(x, y(v), barW, h, Math.min(4, barW / 2))} />}
              {(i === 0 || (i + 1) % (slot < 14 ? 10 : 5) === 0) && (
                <text className="tick" x={left + i * slot + slot / 2} y={H - 4} textAnchor="middle">
                  {i + 1}
                </text>
              )}
              {/* Hit target: the whole slot, taller than the mark. */}
              <rect
                className="hit"
                x={left + i * slot}
                y={top}
                width={slot}
                height={H - top - bottom}
                tabIndex={v > 0 ? 0 : -1}
                aria-label={`${labelFor(i)}: ${format(v)}`}
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(null)}
              />
            </g>
          );
        })}
      </svg>
      <p className="tooltip" aria-live="polite">
        {hover !== null ? (
          <>
            <strong>{labelFor(hover)}</strong> {format(values[hover])}
          </>
        ) : (
          <span className="muted">Point at a day to see its spending.</span>
        )}
      </p>
    </div>
  );
}

function roundedTop(x: number, y: number, w: number, h: number, r: number): string {
  r = Math.min(r, h);
  return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`;
}

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (v <= m * p) return m * p;
  return 10 * p;
}

/** A budget meter: the fill turns warning at 80% and critical past 100%. */
export function Meter({ spent, limit, format }: { spent: number; limit: number; format: (n: number) => string }) {
  const pct = limit > 0 ? spent / limit : 0;
  const state = pct > 1 ? 'over' : pct >= 0.8 ? 'warn' : 'ok';
  const status =
    state === 'over'
      ? `⛔ Over by ${format(spent - limit)}`
      : state === 'warn'
        ? `⚠ ${format(limit - spent)} left`
        : `${format(limit - spent)} left`;
  return (
    <div className={`meter ${state}`}>
      <div
        className="meter-track"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(spent, limit)}
        aria-valuetext={`${format(spent)} of ${format(limit)}`}
      >
        <span className="meter-fill" style={{ width: `${Math.min(pct, 1) * 100}%` }} />
      </div>
      <div className="row small">
        <span className="grow">
          {format(spent)} of {format(limit)}
        </span>
        <span className="meter-status">{status}</span>
      </div>
    </div>
  );
}
