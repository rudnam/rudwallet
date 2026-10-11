import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { Icon, icons, Logo, Plus } from './components/Icon';
import { TransactionForm } from './components/TransactionForm';
import { BudgetsPage } from './features/budgets/BudgetsPage';
import { HomePage } from './features/home/HomePage';
import { RecordsPage } from './features/records/RecordsPage';
import { ReviewPage } from './features/review/ReviewPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { href, navigate, useRoute } from './router';
import { ToastProvider } from './toast';
import { useWallet, WalletProvider } from './wallet';

/** The app's name, shown in the sidebar and on the sign-in screen. Also in index.html and the manifest. */
export const appName = 'Wallet';

type Session =
  | { status: 'loading' }
  | { status: 'signed-out' }
  | { status: 'signed-in'; me: Me }
  | { status: 'error'; message: string };

export function App() {
  const [session, setSession] = useState<Session>({ status: 'loading' });

  useEffect(() => {
    api
      .me()
      .then((me) => setSession({ status: 'signed-in', me }))
      .catch((err) =>
        setSession(
          err instanceof ApiError && err.status === 401
            ? { status: 'signed-out' }
            : { status: 'error', message: errorMessage(err) },
        ),
      );
  }, []);

  async function signOut() {
    await api.signOut();
    navigate('home'); // the next person to sign in starts at Home
    setSession({ status: 'signed-out' });
  }

  if (session.status === 'signed-in') {
    return (
      <ToastProvider>
        <WalletProvider>
          <Shell me={session.me} onSignOut={signOut} />
        </WalletProvider>
      </ToastProvider>
    );
  }
  return (
    <main className="splash">
      {session.status === 'loading' && <p className="muted">Loading…</p>}
      {session.status === 'error' && (
        <p role="alert" className="error">
          Could not reach the server: {session.message}
        </p>
      )}
      {session.status === 'signed-out' && (
        <div className="auth">
          <div className="auth-brand">
            <Logo />
            <span className="auth-name">{appName}</span>
            <p>Your money, quietly kept in order.</p>
          </div>
          <AuthForm onSignedIn={(me) => setSession({ status: 'signed-in', me })} />
        </div>
      )}
    </main>
  );
}

const pages = [
  { page: 'home', label: 'Home', icon: icons.home },
  { page: 'records', label: 'Records', icon: icons.records },
  { page: 'review', label: 'Review', icon: icons.review },
  { page: 'budgets', label: 'Budgets', icon: icons.budgets },
  { page: 'settings', label: 'Settings', icon: icons.settings },
];

function Shell({ me, onSignOut }: { me: Me; onSignOut: () => void }) {
  const route = useRoute();
  const w = useWallet();
  const [adding, setAdding] = useState(false);
  // Bumped after adding a record, so the page reloads its data.
  const [version, setVersion] = useState(0);
  const { refresh } = w;
  const current = pages.some((p) => p.page === route.page) ? route.page : 'home';

  // Emails arrive while the app is open: reload balances and the review
  // count on every page change and whenever the app comes back into view.
  useEffect(() => {
    refresh();
  }, [route.page, refresh]);
  useEffect(() => {
    const onVisible = () => document.visibilityState === 'visible' && refresh();
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refresh]);

  let page;
  switch (current) {
    case 'records':
      page = <RecordsPage route={route} />;
      break;
    case 'review':
      page = <ReviewPage />;
      break;
    case 'budgets':
      page = <BudgetsPage />;
      break;
    case 'settings':
      page = <SettingsPage route={route} me={me} onSignOut={onSignOut} />;
      break;
    default:
      page = <HomePage onAdd={() => setAdding(true)} />;
  }

  return (
    <div className="shell">
      <nav className="nav" aria-label="Main">
        <a className="nav-brand" href={href('home')}>
          <Logo />
          {appName}
        </a>
        <button type="button" className="nav-add" aria-label="Add record" onClick={() => setAdding(true)}>
          <Plus /> New record
        </button>
        {pages.map((p) => (
          <a
            key={p.page}
            href={href(p.page)}
            className={current === p.page ? 'nav-link on' : 'nav-link'}
            aria-current={current === p.page ? 'page' : undefined}
          >
            <Icon d={p.icon} />
            <span>{p.label}</span>
            {p.page === 'review' && w.review_count > 0 && (
              <span className="count" aria-label={`${w.review_count} to review`}>
                {w.review_count}
              </span>
            )}
          </a>
        ))}
        <span className="nav-foot ellipsis" title={me.email}>
          {me.email}
        </span>
      </nav>
      <main className="page" key={`${route.page}-${version}`}>
        {page}
      </main>
      <button type="button" className="fab" aria-label="Add record" onClick={() => setAdding(true)}>
        <Plus />
      </button>
      {adding && <TransactionForm onClose={() => setAdding(false)} onSaved={() => setVersion((v) => v + 1)} />}
    </div>
  );
}
