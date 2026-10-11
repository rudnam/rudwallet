import { useEffect, useState, type ReactNode } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { TransactionForm } from './components/TransactionForm';
import { BudgetsPage } from './features/budgets/BudgetsPage';
import { HomePage } from './features/home/HomePage';
import { RecordsPage } from './features/records/RecordsPage';
import { ReviewPage } from './features/review/ReviewPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { href, useRoute } from './router';
import { useWallet, WalletProvider } from './wallet';

/** The app's name, shown in the top bar. Also in index.html and the manifest. */
export const appName = 'Kakeibo';

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
    setSession({ status: 'signed-out' });
  }

  return (
    <>
      <header className="topbar">
        <span className="brand">{appName}</span>
        {session.status === 'signed-in' && (
          <span className="account">
            <span className="muted">{session.me.email}</span>
            <button className="link" onClick={signOut}>
              Sign out
            </button>
          </span>
        )}
      </header>
      {session.status === 'signed-in' ? (
        <WalletProvider>
          <Shell />
        </WalletProvider>
      ) : (
        <main className="page">
          {session.status === 'loading' && <p className="muted">Loading…</p>}
          {session.status === 'error' && (
            <p role="alert" className="error">
              Could not reach the server: {session.message}
            </p>
          )}
          {session.status === 'signed-out' && (
            <AuthForm onSignedIn={(me) => setSession({ status: 'signed-in', me })} />
          )}
        </main>
      )}
    </>
  );
}

/** A 24px line icon; paths drawn on a 24-unit grid. */
function Icon({ d }: { d: string }) {
  return (
    <svg className="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

const pages: { page: string; label: string; icon: ReactNode }[] = [
  { page: 'home', label: 'Home', icon: <Icon d="M3 11l9-7 9 7M5 9.5V20h5v-6h4v6h5V9.5" /> },
  { page: 'records', label: 'Records', icon: <Icon d="M4 6h16M4 12h16M4 18h10" /> },
  { page: 'review', label: 'Review', icon: <Icon d="M3 6h18v12H3zM3 7l9 6 9-6" /> },
  { page: 'budgets', label: 'Budgets', icon: <Icon d="M12 3a9 9 0 1 0 9 9h-9zM15 3.5A9 9 0 0 1 20.5 9H15z" /> },
  {
    page: 'settings',
    label: 'Settings',
    icon: <Icon d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" />,
  },
];

function Shell() {
  const route = useRoute();
  const w = useWallet();
  const [adding, setAdding] = useState(false);
  // Bumped after adding a record, so the page reloads its data.
  const [version, setVersion] = useState(0);
  const { refresh } = w;

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
  switch (route.page) {
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
      page = <SettingsPage route={route} />;
      break;
    default:
      page = <HomePage />;
  }

  return (
    <div className="shell">
      <nav className="nav" aria-label="Main">
        {pages.map((p) => (
          <a
            key={p.page}
            href={href(p.page)}
            className={route.page === p.page || (p.page === 'home' && !pages.some((x) => x.page === route.page)) ? 'on' : ''}
            aria-current={route.page === p.page ? 'page' : undefined}
          >
            {p.icon}
            <span>{p.label}</span>
            {p.page === 'review' && w.review_count > 0 && (
              <span className="count" aria-label={`${w.review_count} to review`}>
                {w.review_count}
              </span>
            )}
          </a>
        ))}
      </nav>
      <main className="page" key={`${route.page}-${version}`}>
        {page}
      </main>
      <button type="button" className="fab" aria-label="Add record" onClick={() => setAdding(true)}>
        +
      </button>
      {adding && (
        <TransactionForm onClose={() => setAdding(false)} onSaved={() => setVersion((v) => v + 1)} />
      )}
    </div>
  );
}
