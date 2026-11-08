// The signed-in user's accounts, categories and settings, loaded once and
// shared by every page. Call refresh() after anything that changes them
// (or balances, or the review count).
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, errorMessage, type Account, type Category, type Me, type Overview } from './api';
import { Splash } from './components/Splash';
import { t } from './i18n';
import { formatMoney } from './money';
import { balanceChange, outbox, useUnsent } from './outbox';

interface WalletState extends Overview {
  /** Who is signed in; me.demo for a demo account. */
  me: Me;
  refresh: () => Promise<void>;
  money: (amount: number, signed?: boolean) => string;
  account: (id: number | null) => Account | undefined;
  category: (id: number | null) => Category | undefined;
}

const Ctx = createContext<WalletState | null>(null);

export function WalletProvider({ me, children }: { me: Me; children: ReactNode }) {
  // What the app saw last time, while the fresh answer is on its way.
  const [overview, setOverview] = useState<Overview | null>(() => api.seen.overview() ?? null);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    try {
      setOverview(await api.overview());
      setError('');
    } catch (err) {
      setError(errorMessage(err));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  // Sent records leave the outbox once the balances that count them are here.
  useEffect(() => outbox.onSent(refresh), [refresh]);
  const unsent = useUnsent(me.email);

  if (error && !overview) {
    return (
      <main className="splash">
        <p role="alert" className="error">
          {t('Could not load your wallet: {error}', { error })}
        </p>
      </main>
    );
  }
  // The same splash as App's, so the wait for the wallet doesn't move it.
  if (!overview) return <Splash />;

  // Records not sent yet count already.
  const accounts = unsent.length
    ? overview.accounts.map((a) => ({ ...a, balance: unsent.reduce((sum, u) => sum + balanceChange(u, a.id), a.balance) }))
    : overview.accounts;
  const value: WalletState = {
    ...overview,
    accounts,
    me,
    refresh,
    money: (amount, signed) => formatMoney(amount, overview.settings, signed),
    account: (id) => accounts.find((a) => a.id === id),
    category: (id) => overview.categories.find((c) => c.id === id),
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useWallet(): WalletState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useWallet outside WalletProvider');
  return v;
}
