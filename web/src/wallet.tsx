// The signed-in user's accounts, categories and settings, loaded once and
// shared by every page. Call refresh() after anything that changes them
// (or balances, or the review count).
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, errorMessage, type Account, type Category, type Me, type Overview } from './api';
import { t } from './i18n';
import { formatMoney } from './money';

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
  const [overview, setOverview] = useState<Overview | null>(null);
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

  if (error && !overview) {
    return (
      <p role="alert" className="error">
        {t('Could not load your wallet: {error}', { error })}
      </p>
    );
  }
  if (!overview) return <p className="muted">{t('Loading…')}</p>;

  const value: WalletState = {
    ...overview,
    me,
    refresh,
    money: (amount, signed) => formatMoney(amount, overview.settings, signed),
    account: (id) => overview.accounts.find((a) => a.id === id),
    category: (id) => overview.categories.find((c) => c.id === id),
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useWallet(): WalletState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useWallet outside WalletProvider');
  return v;
}
