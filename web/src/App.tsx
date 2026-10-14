import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { Icon, icons, Logo, Plus } from './components/Icon';
import { TransactionForm } from './components/TransactionForm';
import { BudgetsPage } from './features/budgets/BudgetsPage';
import { HomePage } from './features/home/HomePage';
import { OrganizePage } from './features/organize/OrganizePage';
import { RecordsPage } from './features/records/RecordsPage';
import { ReviewPage } from './features/review/ReviewPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { StatsPage } from './features/stats/StatsPage';
import { onAppLinks } from './native';
import { initialNotice, signinErrors, type Notice } from './notice';
import { href, navigate, useRoute } from './router';
import { t, tx, type Key } from './i18n';
import { LanguagePicker } from './features/settings/LanguageSettings';
import { ToastProvider, useToast } from './toast';
import { useWallet, WalletProvider } from './wallet';

/** The app's name, shown in the sidebar and on the sign-in screen. Also in index.html and the manifest. */
export const appName = 'Wallet';

/**
 * The introduction page's "Try the demo" opens /app/?demo. Read once when
 * the app loads, then dropped from the address so a reload doesn't start
 * another demo.
 */
const demoRequested = (() => {
  const params = new URLSearchParams(location.search);
  if (!params.has('demo')) return false;
  params.delete('demo');
  const qs = params.toString();
  history.replaceState(null, '', location.pathname + (qs ? `?${qs}` : '') + location.hash);
  return true;
})();

type Session =
  | { status: 'loading'; demo?: boolean }
  | { status: 'signed-out'; notice?: Notice | null; mode?: 'sign-up' }
  | { status: 'signed-in'; me: Me }
  | { status: 'error'; message: string };

export function App() {
  const [session, setSession] = useState<Session>({ status: 'loading' });

  // In the Android app: Google sign-in coming back from the browser, and
  // widgets opening a page.
  useEffect(
    () =>
      onAppLinks({
        onAuth: (result) => {
          if ('error' in result) {
            setSession({ status: 'signed-out', notice: { message: t(signinErrors[result.error] ?? signinErrors.failed), ok: false } });
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
        if (err instanceof ApiError && err.status === 401 && demoRequested) startDemo();
        else
          setSession(
            err instanceof ApiError && err.status === 401
              ? { status: 'signed-out', notice: initialNotice }
              : { status: 'error', message: errorMessage(err) },
          );
      });
  }, []);

  /** Signs in to a new demo account full of sample data. */
  async function startDemo() {
    setSession({ status: 'loading', demo: true });
    try {
      const me = await api.startDemo();
      navigate('home');
      setSession({ status: 'signed-in', me });
    } catch (err) {
      setSession({ status: 'signed-out', notice: { message: errorMessage(err), ok: false } });
    }
  }

  /** From a demo to the sign-up form. Signing out deletes the demo. */
  async function leaveDemo() {
    await api.signOut();
    navigate('home');
    setSession({ status: 'signed-out', mode: 'sign-up' });
  }

  async function signOut() {
    await api.signOut();
    signedOut();
  }

  function signedOut(notice?: Notice) {
    navigate('home'); // the next person to sign in starts at Home
    setSession({ status: 'signed-out', notice });
  }

  if (session.status === 'signed-in') {
    return (
      <ToastProvider>
        <WalletProvider me={session.me}>
          <Shell
            me={session.me}
            onSignOut={signOut}
            onCreateAccount={leaveDemo}
            onDeleted={() => signedOut({ message: t('Your account and everything in it were deleted.'), ok: true })}
          />
        </WalletProvider>
      </ToastProvider>
    );
  }
  return (
    <main className="splash">
      {session.status === 'loading' && <p className="muted">{session.demo ? t('Setting up your demo…') : t('Loading…')}</p>}
      {session.status === 'error' && (
        <p role="alert" className="error">
          {t('Could not reach the server: {error}', { error: session.message })}
        </p>
      )}
      {session.status === 'signed-out' && (
        <div className="auth">
          <div className="auth-brand">
            <Logo />
            <span className="auth-name">{appName}</span>
            <p>{t('Your money, quietly kept in order.')}</p>
          </div>
          <AuthForm
            key={`${session.mode}-${session.notice?.message}`}
            mode={session.mode}
            notice={session.notice}
            onSignedIn={(me) => setSession({ status: 'signed-in', me })}
            onDemo={startDemo}
          />
          <LanguagePicker />
        </div>
      )}
    </main>
  );
}

/** The notice the app opened with is shown once, not on every sign-in. */
let noticeShown = false;

const pages: { page: string; label: Key; icon: string }[] = [
  { page: 'home', label: 'Home', icon: icons.home },
  { page: 'records', label: 'Records', icon: icons.records },
  { page: 'stats', label: 'Stats', icon: icons.stats },
  { page: 'review', label: 'Review', icon: icons.review },
  { page: 'budgets', label: 'Budgets', icon: icons.budgets },
  { page: 'settings', label: 'Settings', icon: icons.settings },
];

/** Pages outside the main navigation, and the entry they belong under. */
const subpages: Record<string, string> = { organize: 'settings' };

function Shell({
  me,
  onSignOut,
  onCreateAccount,
  onDeleted,
}: {
  me: Me;
  onSignOut: () => void;
  onCreateAccount: () => void;
  onDeleted: () => void;
}) {
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
      <nav className="nav" aria-label={t('Main')}>
        <a className="nav-brand" href={href('home')}>
          <Logo />
          {appName}
        </a>
        <button type="button" className="nav-add" aria-label={t('Add record')} onClick={() => setAdding(true)}>
          <Plus /> {t('New record')}
        </button>
        {pages.map((p) => (
          <a
            key={p.page}
            href={href(p.page)}
            className={navPage === p.page ? 'nav-link on' : 'nav-link'}
            aria-current={navPage === p.page ? 'page' : undefined}
          >
            <Icon d={p.icon} />
            <span>{t(p.label)}</span>
            {p.page === 'review' && w.review_count > 0 && (
              <span className="count" aria-label={t('{n} to review', { n: w.review_count })}>
                {w.review_count}
              </span>
            )}
          </a>
        ))}
        <span className="nav-foot ellipsis" title={me.demo ? undefined : me.email}>
          {me.demo ? t('Demo') : me.email}
        </span>
      </nav>
      <main className="page" key={`${route.page}-${version}`}>
        {me.demo && <DemoBanner onCreateAccount={onCreateAccount} />}
        {me.confirm_email && <ConfirmEmail email={me.email} />}
        {page}
      </main>
      <button type="button" className="fab" aria-label={t('Add record')} onClick={() => setAdding(true)}>
        <Plus />
      </button>
      {adding && <TransactionForm onClose={() => setAdding(false)} onSaved={() => setVersion((v) => v + 1)} />}
    </div>
  );
}

/** In a demo: what this is, and the way to an account of one's own. */
function DemoBanner({ onCreateAccount }: { onCreateAccount: () => void }) {
  return (
    <div className="notice demo-banner" role="note">
      <Icon d={icons.spark} className="inline-icon" />
      <span className="grow">
        <strong>{t('This is a demo with sample data.')}</strong>{' '}
        {t('Change anything you like: nobody else sees it, and it’s deleted within a day.')}
      </span>
      <button type="button" className="secondary" onClick={onCreateAccount}>
        {t('Create your account')}
      </button>
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
        {state === 'sent'
          ? tx('Sent. Check your inbox at {email}.', { email: <strong>{email}</strong> })
          : tx('Confirm your email: open the link we sent to {email}.', { email: <strong>{email}</strong> })}
        {state !== 'idle' && state !== 'sending' && state !== 'sent' && <span className="error block small">{state}</span>}
      </span>
      {state !== 'sent' && (
        <button type="button" className="link" disabled={state === 'sending'} onClick={resend}>
          {t('Send again')}
        </button>
      )}
    </div>
  );
}
