// Line icons, all drawn on a 24-unit grid with the same round 1.7px stroke,
// so they read as one set. "h.01" segments render as dots.
import type { CSSProperties } from 'react';

/** A line icon from a path. */
export function Icon({ d, className = 'nav-icon' }: { d: string; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

export const icons = {
  home: 'M3 11l9-7 9 7M5 9.5V20h5v-6h4v6h5V9.5',
  records: 'M4 6h16M4 12h16M4 18h10',
  stats: 'M4 20h16M7 16v-4M12 16V6M17 16v-7',
  up: 'M4 17l6-6 4 4 6-7M15 8h5v5',
  down: 'M4 7l6 6 4-4 6 7M15 16h5v-5',
  level: 'M4 12h16M15 7l5 5-5 5',
  review: 'M3 6h18v12H3zM3 7l9 6 9-6',
  budgets: 'M12 3a9 9 0 1 0 9 9h-9zM15 3.5A9 9 0 0 1 20.5 9H15z',
  settings:
    'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1',
  check: 'M5 12.5l4.5 4.5L19 7',
  close: 'M6 6l12 12M18 6L6 18',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  mail: 'M3 6h18v12H3zM3 7l9 6 9-6',
  warning: 'M12 4l9.5 16h-19zM12 10v4M12 17h.01',
  stop: 'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18M12 7.5v5.5M12 16.5h.01',
  transfer: 'M4 8h15l-3.5-3.5M20 16H5l3.5 3.5',
  dot: 'M12 12h.01',
};

/**
 * Icons a category can wear. The key is what's stored in the category's
 * icon field (at most 8 characters, which the API allows).
 */
export const glyphs: Record<string, { label: string; d: string }> = {
  food: { label: 'Food', d: 'M4 12h16a8 8 0 0 1-16 0zM9 20.5h6M13 4l-2.5 8M18.5 5L14 12' },
  grocery: { label: 'Groceries', d: 'M3 4h2.5l2.2 11h10.6L20.5 8H6.4M9 19.5h.01M17 19.5h.01' },
  shopping: { label: 'Shopping', d: 'M5 8h14l-1 12.5H6zM9 10V6.5a3 3 0 0 1 6 0V10' },
  transit: { label: 'Transport', d: 'M6 4h12v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM6 11h12M9 14h.01M15 14h.01M9 21l1.5-4M15 21l-1.5-4' },
  home: { label: 'Home', d: icons.home },
  bills: {
    label: 'Bills',
    d: 'M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z',
  },
  health: { label: 'Health', d: 'M12 20s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 7.2a4.2 4.2 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z' },
  fun: { label: 'Entertainment', d: 'M4 9h16v10H4zM4 9l1.6-4h12.8L20 9M9.5 5l-1.5 4M15 5l-1.5 4' },
  travel: { label: 'Travel', d: 'M21 3L3 10.5l7.5 3L13.5 21zM10.5 13.5L21 3' },
  other: { label: 'Other', d: 'M4 8l8-4 8 4v8.5l-8 4-8-4zM4 8l8 4 8-4M12 12v8.5' },
  salary: { label: 'Salary', d: 'M3.5 8h17v11.5h-17zM9 8V5h6v3M3.5 13h17' },
  money: { label: 'Money', d: 'M3 7h18v10H3zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5M6.5 12h.01M17.5 12h.01' },
  coffee: { label: 'Coffee', d: 'M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 10.5h1.5a2.5 2.5 0 0 1 0 5H16M9 3.5v2.5M12.5 3.5v2.5' },
  gift: { label: 'Gifts', d: 'M4 9h16v4H4zM5.5 13h13v7.5h-13zM12 9v11.5M12 9C10 5 6.5 5 6.5 7.5S12 9 12 9zm0 0c2-4 5.5-4 5.5-1.5S12 9 12 9z' },
  school: { label: 'Education', d: 'M4 19.5V5a2 2 0 0 1 2-2h14v15H6a2 2 0 0 0-2 2 2 2 0 0 0 2 2h14' },
  phone: { label: 'Phone', d: 'M7.5 3h9v18h-9zM11 18h2' },
  car: { label: 'Car', d: 'M4 16v-4l2-5h12l2 5v4zM4 12h16M5 16v2.5h2.5V16M16.5 16v2.5H19V16M7.5 14h.01M16.5 14h.01' },
  sport: { label: 'Sport', d: 'M6.5 7v10M3.5 9.5v5M17.5 7v10M20.5 9.5v5M6.5 12h11' },
  beauty: { label: 'Beauty', d: 'M12 3l2 7 7 2-7 2-2 7-2-7-7-2 7-2z' },
  card: { label: 'Card', d: 'M3 6h18v12H3zM3 10h18M6.5 14.5h4' },
  bank: { label: 'Bank', d: 'M3 9.5L12 4l9 5.5M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20.5h18' },
  savings: { label: 'Savings', d: 'M9 3.5h6M7 6.5h10l1 3V19a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9.5zM10 13.5h4' },
  kids: { label: 'Kids', d: 'M12 4a3 3 0 1 0 0 6 3 3 0 1 0 0-6M6 21v-3a6 6 0 0 1 12 0v3' },
  pet: { label: 'Pets', d: 'M5 14.5a7 5 0 0 0 14 0c0-2.5-3-5-7-5s-7 2.5-7 5zM6 8h.01M10 5h.01M14 5h.01M18 8h.01' },
};

// Categories made before line icons stored an emoji; show its closest glyph.
const fromEmoji: Record<string, string> = {
  '🍜': 'food', '🍔': 'food', '🍽': 'food', '🍱': 'food', '🍣': 'food', '🍕': 'food',
  '🛒': 'grocery', '🛍': 'shopping', '👕': 'shopping',
  '🚆': 'transit', '🚃': 'transit', '🚌': 'transit', '🚇': 'transit', '🚕': 'car', '🚗': 'car', '⛽': 'car',
  '🏠': 'home', '🏡': 'home', '💡': 'bills', '🧾': 'bills', '💊': 'health', '🏥': 'health', '❤': 'health',
  '🎬': 'fun', '🎮': 'fun', '🎵': 'fun', '✈': 'travel', '🧳': 'travel', '📦': 'other',
  '💼': 'salary', '💰': 'money', '💴': 'money', '💵': 'money', '☕': 'coffee', '🎁': 'gift',
  '📚': 'school', '🎓': 'school', '📱': 'phone', '🏋': 'sport', '⚽': 'sport', '💄': 'beauty',
  '💳': 'card', '🏦': 'bank', '🐷': 'savings', '👶': 'kids', '🐶': 'pet', '🐱': 'pet',
};

/** The glyph key for a stored icon, or undefined if there is none. */
export function glyphKey(icon: string | undefined): string | undefined {
  if (!icon) return undefined;
  if (glyphs[icon]) return icon;
  return fromEmoji[icon.replace(/[️‍]/g, '')];
}

/** A category's mark: its line icon, or its initial if it has none, on a wash of its colour. */
export function CategoryIcon({
  icon,
  name,
  color,
  size,
}: {
  icon?: string;
  name?: string;
  color?: string;
  size?: 'small';
}) {
  const key = glyphKey(icon);
  const hex = color && /^#[0-9a-f]{6}$/i.test(color) ? color : undefined;
  const style: CSSProperties | undefined = hex
    ? { background: `${hex}1f`, color: `color-mix(in srgb, ${hex} 75%, var(--text))` }
    : undefined;
  return (
    <span className={size === 'small' ? 'cat-icon small' : 'cat-icon'} aria-hidden="true" style={style}>
      {key ? <Icon d={glyphs[key].d} className="glyph" /> : name ? <span className="initial">{name[0]}</span> : <Icon d={icons.dot} className="glyph" />}
    </span>
  );
}

/** The plus on the add buttons. */
export function Plus() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

/** The app's mark: a clay tile with a folded wallet. Same drawing as public/icon.svg. */
export function Logo() {
  return (
    <svg className="logo" viewBox="0 0 512 512" aria-hidden="true">
      <rect width="512" height="512" rx="112" fill="#c96442" />
      <rect x="112" y="160" width="288" height="200" rx="36" fill="#f5f3ec" />
      <path d="M112 212h288" stroke="#c96442" strokeWidth="22" />
      <circle cx="338" cy="292" r="20" fill="#c96442" />
    </svg>
  );
}
