import { useEffect, useState } from 'react';
import { api, ApiError, errorMessage, savedMe, type Me } from './api';
import { AuthForm } from './components/AuthForm';
import { ResetPasswordForm } from './components/ResetPasswordForm';
import { Splash } from './components/Splash';
import { Icon, icons, Logo, Plus } from './components/Icon';
import { TransactionForm } from './components/TransactionForm';
import { BudgetsPage } from './features/budgets/BudgetsPage';
import { ConnectPage, rememberConnect, resumeConnect } from './features/settings/ConnectPage';
import { HomePage } from './features/home/HomePage';
import { ActivityPage } from './features/records/ActivityPage';
import { DuplicatesPage } from './features/review/DuplicatesPage';
import { AccountPage } from './features/account/AccountPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { prefetchStats, StatsPage } from './features/stats/StatsPage';
import { addMonths, monthStart } from './money';
import { initialNotice, type Notice } from './notice';
import { href, navigate, replaceRoute, useRoute } from './router';
import { t, tx, type Key } from './i18n';
import { LanguagePicker } from './features/settings/LanguageSettings';
import { ToastProvider, useToast } from './toast';
import { useWallet, WalletProvider } from './wallet';

/** The app's name, shown in the sidebar and on the sign-in screen. Also in index.html and the manifest. */
export const appName = 'RudWallet';

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
  | { status: 'signed-in'; me: Me; notice?: string }
  | { status: 'error'; message: string };

export function App() {
  // Whoever was signed in last time opens straight on their wallet (kept on
  // this device, see api.ts) while the server confirms the session.
  const [session, setSession] = useState<Session>(() => {
    const me = savedMe.get();
    return me ? { status: 'signed-in', me } : { status: 'loading' };
  });
  const route = useRoute();

  useEffect(() => {
    api
      .me()
      .then((me) => {
        // Someone else's session (signed in elsewhere): not a glimpse of the last person's wallet.
        if (savedMe.get()?.email !== me.email) savedMe.set(null);
        setSession((s) => (s.status === 'signed-in' ? { ...s, me } : { status: 'signed-in', me }));
        void api.sendUnsent(); // records added while offline last time
      })
      .catch((err) => {
        if (!(err instanceof ApiError && err.status === 401)) {
          // Offline, say: a wallet opened from what was kept stays open.
          setSession((s) => (s.status === 'signed-in' ? s : { status: 'error', message: errorMessage(err) }));
          return;
        }
        // An app asking to connect waits for the user to sign in.
        rememberConnect();
        const connecting = location.hash.startsWith('#/connect?');
        // A demo never connects an app: that would reach outside it.
        if (demoRequested && !connecting) {
          startDemo();
          return;
        }
        setSession({ status: 'signed-out', notice: initialNotice ?? (connecting ? connectNotice() : null) });
      });
  }, []);

  // Remember who is signed in for the next launch; forget them, and what
  // they saw, on signing out or when the session has ended.
  const signedInMe = session.status === 'signed-in' ? session.me : null;
  useEffect(() => {
    if (signedInMe) savedMe.set(signedInMe);
  }, [signedInMe]);
  useEffect(() => {
    if (session.status === 'signed-out') savedMe.set(null);
  }, [session.status]);

  // Signing in with Google comes back to /app/, without the Connect request.
  useEffect(() => {
    if (session.status === 'signed-in') resumeConnect();
  }, [session.status]);

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

  // The emailed reset link, whether or not this browser is signed in.
  if (route.page === 'reset' && (session.status === 'signed-in' || session.status === 'signed-out')) {
    return (
      <main className="splash">
        <div className="auth">
          <div className="auth-brand">
            <Logo />
            <span className="auth-name">{appName}</span>
          </div>
          <ResetPasswordForm
            token={route.params.get('token') ?? ''}
            onDone={(me) => {
              // Off the reset page, without leaving the used link in the history.
              replaceRoute('home');
              setSession({ status: 'signed-in', me, notice: t('Your new password is saved.') });
            }}
            onCancel={() => replaceRoute('home')}
          />
        </div>
      </main>
    );
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
        {/* Another account (from a session started elsewhere) starts afresh. */}
        <WalletProvider key={session.me.email} me={session.me}>
          <Shell
            me={session.me}
            notice={session.notice}
            onSignOut={signOut}
            onCreateAccount={leaveDemo}
            onDeleted={() => signedOut({ message: t('Your account and everything in it were deleted.'), ok: true })}
          />
        </WalletProvider>
      </ToastProvider>
    );
  }
  if (session.status === 'loading') return <Splash label={session.demo ? t('Setting up your demo…') : undefined} />;
  return (
    <main className="splash">
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

const connectNotice = (): Notice => ({ message: t('Sign in to connect an app such as Claude to your wallet.'), ok: true });

/** The notice the app opened with is shown once, not on every sign-in. */
let noticeShown = false;

// The tabs. Their pages keep the names they had (records, stats), so links,
// widgets (android/…/widget) and bookmarks still open them.
const pages: { page: string; label: Key; icon: string }[] = [
  { page: 'home', label: 'Wallet', icon: icons.wallet },
  { page: 'records', label: 'Activity', icon: icons.records },
  { page: 'stats', label: 'Insights', icon: icons.stats },
  { page: 'settings', label: 'Settings', icon: icons.settings },
];

/** Asks ahead for what Home, Records, Stats, Review and Budgets open on. */
function prefetchPages() {
  const month = monthStart(new Date());
  const thisMonth = { from: month, to: addMonths(month, 1) };
  api.prefetch.transactions(thisMonth);
  api.prefetch.transactions({ ...thisMonth, status: 'confirmed' });
  api.prefetch.transactions({ status: 'review' });
  api.prefetch.budgets();
  api.prefetch.tags();
  prefetchStats();
}

/** Pages outside the main navigation, and the entry they belong under. */
const subpages: Record<string, string> = { card: 'home', account: 'home', review: 'records', duplicates: 'records', budgets: 'stats' };

function Shell({
  me,
  notice,
  onSignOut,
  onCreateAccount,
  onDeleted,
}: {
  me: Me;
  /** Shown once as a toast, e.g. after a new password is saved. */
  notice?: string;
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
  useEffect(() => {
    if (notice) toast(notice);
  }, [notice, toast]);
  const [adding, setAdding] = useState(false);
  // Bumped after adding a record, so the page reloads its data.
  const [version, setVersion] = useState(0);
  const { refresh } = w;
  const current = pages.some((p) => p.page === route.page) || route.page in subpages ? route.page : 'home';
  const navPage = subpages[current] ?? current;

  // #/add opens the record sheet, e.g. from a link or a bookmark.
  useEffect(() => {
    if (route.page !== 'add') return;
    history.replaceState(null, '', href('home'));
    setAdding(true);
  }, [route]);

  // Payments arrive while the app is open: reload balances and the review
  // count on every page change and whenever the app comes back into view.
  useEffect(() => {
    refresh();
  }, [route.page, refresh]);
  // Once the page is drawn, what the other pages open on loads in the
  // background, so moving between them is instant. What's already been seen
  // since the last change isn't asked for again.
  useEffect(() => {
    const id = setTimeout(prefetchPages, 500);
    return () => clearTimeout(id);
  }, [route.page]);
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      refresh();
      void api.sendUnsent();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refresh]);
  // Back online: send the records added meanwhile, and catch up.
  useEffect(() => {
    const onOnline = () => {
      void api.sendUnsent();
      refresh();
    };
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [refresh]);

  let page;
  switch (current) {
    // Activity: what's waiting for a check, then every record.
    case 'records':
    case 'review':
      page = <ActivityPage route={route} />;
      break;
    case 'duplicates':
      page = <DuplicatesPage />;
      break;
    case 'stats':
      page = <StatsPage route={route} />;
      break;
    case 'budgets':
      page = <BudgetsPage />;
      break;
    case 'account':
      page = <AccountPage route={route} />;
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
            {p.page === 'records' && w.review_count > 0 && (
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
