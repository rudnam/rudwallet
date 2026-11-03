// A large amount, as on the balance card: the currency sign set smaller and
// lighter than the digits, so the eye lands on the number.
import { useEffect, useRef, useState } from 'react';
import { moneyParts } from '../money';
import { useWallet } from '../wallet';

// The balance counts up once, the first time the app shows it; after that
// it only rolls from the old amount to a new one.
let countedUp = false;

/**
 * The amount, rolling to it over a moment when it changes (animate). Not
 * for people who ask for less motion: they see the amount straight away.
 */
function useRolling(amount: number, animate: boolean): number {
  const still = !animate || matchMedia('(prefers-reduced-motion: reduce)').matches;
  const [shown, setShown] = useState(() => (still || countedUp ? amount : 0));
  const from = useRef(shown);
  useEffect(() => {
    if (still) {
      setShown(amount);
      return;
    }
    countedUp = true;
    const start = from.current;
    if (start === amount) return;
    const began = performance.now();
    const ms = 700;
    let frame = 0;
    const step = (now: number) => {
      const p = Math.min((now - began) / ms, 1);
      const eased = 1 - (1 - p) ** 3;
      const v = p === 1 ? amount : Math.round(start + (amount - start) * eased);
      from.current = v;
      setShown(v);
      if (p < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [amount, still]);
  return still ? amount : shown;
}

export function Figure({
  amount,
  signed,
  className = '',
  animate = false,
}: {
  amount: number;
  signed?: boolean;
  className?: string;
  /** Roll to the amount when it changes. */
  animate?: boolean;
}) {
  const w = useWallet();
  const value = useRolling(amount, animate);
  return (
    <span className={`figure ${className}`.trim()}>
      {moneyParts(value, w.settings, signed).map((p, i) =>
        p.type === 'currency' ? (
          <span key={i} className="figure-cur">
            {p.value}
          </span>
        ) : p.type === 'decimal' || p.type === 'fraction' ? (
          <span key={i} className="figure-frac">
            {p.value}
          </span>
        ) : (
          p.value
        ),
      )}
    </span>
  );
}
