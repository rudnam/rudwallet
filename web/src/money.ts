// Money and date helpers. Amounts are integers in minor units.
import type { Settings } from './api';

/** Formats minor units as money, e.g. 4994 JPY → "¥4,994". */
export function formatMoney(amount: number, s: Pick<Settings, 'currency' | 'currency_exponent'>, signed = false): string {
  const value = amount / 10 ** s.currency_exponent;
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: s.currency,
    minimumFractionDigits: s.currency_exponent,
    maximumFractionDigits: s.currency_exponent,
    signDisplay: signed ? 'exceptZero' : 'auto',
  }).format(value);
}

/** Short form for axis ticks: ¥12K. */
export function formatCompact(amount: number, s: Pick<Settings, 'currency' | 'currency_exponent'>): string {
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: s.currency,
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount / 10 ** s.currency_exponent);
}

/** Parses what the user typed ("1,200", "12.50") into minor units, or null. */
export function parseAmount(text: string, exponent: number): number | null {
  const cleaned = text.replace(/[,\s¥$€£]/g, '');
  if (!/^\d+(\.\d+)?$/.test(cleaned)) return null;
  const [whole, frac = ''] = cleaned.split('.');
  if (frac.length > exponent) return null;
  return Number(whole) * 10 ** exponent + Number(frac.padEnd(exponent, '0') || '0');
}

/** The amount as the user would type it back: 1250 (USD) → "12.50". */
export function amountInput(amount: number, exponent: number): string {
  return exponent === 0 ? String(amount) : (amount / 10 ** exponent).toFixed(exponent);
}

/** First moment of the month containing d, in local time. */
export function monthStart(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

export function addMonths(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth() + n, 1);
}

export function daysInMonth(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
}

export function formatMonth(d: Date): string {
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'long' });
}

/** A local date key, YYYY-MM-DD, for grouping by day. */
export function dayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function formatDay(d: Date): string {
  const today = dayKey(new Date());
  const yesterday = dayKey(new Date(Date.now() - 86_400_000));
  const key = dayKey(d);
  if (key === today) return 'Today';
  if (key === yesterday) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
}

export function formatTime(d: Date): string {
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

/** Value for <input type="datetime-local">, in local time. */
export function toLocalInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${dayKey(d)}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** The currency's sign as the user's locale writes it: "¥", "$", "€". */
export function currencySymbol(s: Pick<Settings, 'currency'>): string {
  return (
    new Intl.NumberFormat(undefined, { style: 'currency', currency: s.currency })
      .formatToParts(0)
      .find((p) => p.type === 'currency')?.value ?? s.currency
  );
}
