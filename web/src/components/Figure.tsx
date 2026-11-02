// A large amount, as on the balance card: the currency sign set smaller and
// lighter than the digits, so the eye lands on the number.
import { moneyParts } from '../money';
import { useWallet } from '../wallet';

export function Figure({ amount, signed, className = '' }: { amount: number; signed?: boolean; className?: string }) {
  const w = useWallet();
  return (
    <span className={`figure ${className}`.trim()}>
      {moneyParts(amount, w.settings, signed).map((p, i) =>
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
