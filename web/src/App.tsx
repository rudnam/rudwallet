import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { Icon, icons, Logo, Plus } from './components/Icon';
import { TransactionForm } from './components/TransactionForm';
import { BudgetsPage } from './features/budgets/BudgetsPage';
import { ConnectPage, rememberConnect, resumeConnect } from './features/settings/ConnectPage';
import { HomePage } from './features/home/HomePage';
import { OrganizePage } from './features/organize/OrganizePage';
import { RecordsPage } from './features/records/RecordsPage';
import { ReviewPage } from './features/review/ReviewPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { StatsPage } from './features/stats/StatsPage';
import { onAppLinks } from './native';
import { initialNotice, signinErrors, type Notice } from './notice';
import { href, navigate, useRoute } from './router';
import { ToastProvider, useToast } from './toast';
import { useWallet, WalletProvider } from './wallet';

/** The app's name, shown in the sidebar and on the sign-in screen. Also in index.html and the manifest. */
export const appName = 'Wallet';

type Session =
  | { status: 'loading' }
  | { status: 'signed-out'; notice?: Notice | null }
  | { status: 'signed-in'; me: Me }
  | { status: 'error'; message: string };

export function App() {
  const [session, setSession] = useState<Session>({ status: 'loading' });
  const route = useRoute();

  // In the Android app: Google sign-in coming back from the browser, and
  // widgets opening a page.
  useEffect(
    () =>
      onAppLinks({
        onAuth: (result) => {
          if ('error' in result) {
            setSession({ status: 'signed-out', notice: { message: signinErrors[result.error] ?? signinErrors.failed, ok: false } });
            return;
          }
          api.appToken(result.code, result.verifier).then(
            (me) => setSession({ status: 'signed-in', me }),
            (err) => setSession({ status: 'signed-out', notice: { message: errorMessage(err), ok: false } }),
          );
        },
        onOpen: (page) => navigate(page),
      }),
    [],
  );

  useEffect(() => {
    api
      .me()
      .then((me) => setSession({ status: 'signed-in', me }))
      .catch((err) => {
        if (!(err instanceof ApiError && err.status === 401)) {
          setSession({ status: 'error', message: errorMessage(err) });
          return;
        }
        // An app asking to connect waits for the user to sign in.
        rememberConnect();
        const connecting = location.hash.startsWith('#/connect?');
        setSession({ status: 'signed-out', notice: initialNotice ?? (connecting ? connectNotice : null) });
      });
  }, []);

  // Signing in with Google comes back to /app/, without the Connect request.
  useEffect(() => {
    if (session.status === 'signed-in') resumeConnect();
  }, [session.status]);

  async function signOut() {
    await api.signOut();
    signedOut();
  }

  function signedOut(notice?: Notice) {
    navigate('home'); // the next person to sign in starts at Home
    setSession({ status: 'signed-out', notice });
  }

  if (session.status === 'signed-in' && route.page === 'connect') {
    return (
      <main className="splash">
        <div className="auth">
          <div className="auth-brand">
            <Logo />
            <span className="auth-name">{appName}</span>
          </div>
          <ConnectPage route={route} email={session.me.email} />
        </div>
      </main>
    );
  }
  if (session.status === 'signed-in') {
    return (
      <ToastProvider>
        <WalletProvider>
          <Shell
            me={session.me}
            onSignOut={signOut}
            onDeleted={() => signedOut({ message: 'Your account and everything in it were deleted.', ok: true })}
          />
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
          <AuthForm key={session.notice?.message} notice={session.notice} onSignedIn={(me) => setSession({ status: 'signed-in', me })} />
        </div>
      )}
    </main>
  );
}

const connectNotice: Notice = { message: 'Sign in to connect an app such as Claude to your wallet.', ok: true };

/** The notice the app opened with is shown once, not on every sign-in. */
let noticeShown = false;

const pages = [
  { page: 'home', label: 'Home', icon: icons.home },
  { page: 'records', label: 'Records', icon: icons.records },
  { page: 'stats', label: 'Stats', icon: icons.stats },
  { page: 'review', label: 'Review', icon: icons.review },
  { page: 'budgets', label: 'Budgets', icon: icons.budgets },
  { page: 'settings', label: 'Settings', icon: icons.settings },
];

/** Pages outside the main navigation, and the entry they belong under. */
const subpages: Record<string, string> = { organize: 'settings' };

function Shell({ me, onSignOut, onDeleted }: { me: Me; onSignOut: () => void; onDeleted: () => void }) {
  const route = useRoute();
  const w = useWallet();
  const toast = useToast();
  // e.g. "Your email address is confirmed" after opening the emailed link.
  useEffect(() => {
    if (initialNotice && !noticeShown) toast(initialNotice.message);
    noticeShown = true;
  }, [toast]);
  const [adding, setAdding] = useState(false);
  // Bumped after adding a record, so the page reloads its data.
  const [version, setVersion] = useState(0);
  const { refresh } = w;
  const current = pages.some((p) => p.page === route.page) || route.page in subpages ? route.page : 'home';
  const navPage = subpages[current] ?? current;

  // The Android widget's New record button opens #/add.
  useEffect(() => {
    if (route.page !== 'add') return;
    history.replaceState(null, '', href('home'));
    setAdding(true);
  }, [route]);

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
    case 'stats':
      page = <StatsPage route={route} />;
      break;
    case 'review':
      page = <ReviewPage />;
      break;
    case 'budgets':
      page = <BudgetsPage />;
      break;
    case 'organize':
      page = <OrganizePage />;
      break;
    case 'settings':
      page = <SettingsPage route={route} me={me} onSignOut={onSignOut} onDeleted={onDeleted} />;
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
            className={navPage === p.page ? 'nav-link on' : 'nav-link'}
            aria-current={navPage === p.page ? 'page' : undefined}
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
        {me.confirm_email && <ConfirmEmail email={me.email} />}
        {page}
      </main>
      <button type="button" className="fab" aria-label="Add record" onClick={() => setAdding(true)}>
        <Plus />
      </button>
      {adding && <TransactionForm onClose={() => setAdding(false)} onSaved={() => setVersion((v) => v + 1)} />}
    </div>
  );
}

/** A reminder to open the link we emailed, with a way to send it again. */
function ConfirmEmail({ email }: { email: string }) {
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | string>('idle');
  async function resend() {
    setState('sending');
    try {
      await api.resendConfirmation();
      setState('sent');
    } catch (err) {
      setState(errorMessage(err));
    }
  }
  return (
    <div className="notice confirm-email">
      <Icon d={icons.mail} className="inline-icon" />
      <span className="grow">
        {state === 'sent' ? (
          <>
            Sent. Check your inbox at <strong>{email}</strong>.
          </>
        ) : (
          <>
            Confirm your email: open the link we sent to <strong>{email}</strong>.
          </>
        )}
        {state !== 'idle' && state !== 'sending' && state !== 'sent' && <span className="error block small">{state}</span>}
      </span>
      {state !== 'sent' && (
        <button type="button" className="link" disabled={state === 'sending'} onClick={resend}>
          Send again
        </button>
      )}
    </div>
  );
}
