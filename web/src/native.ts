// The Android app (android/, Capacitor) runs this same web app from files
// inside the app, at https://localhost. There the API is another origin, so
// requests go to the server named at build time (VITE_SERVER, see
// .env.android) and sign in with a bearer token instead of a cookie (see
// internal/account/app.go). On the web, none of this applies.
import { App as CapApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { Capacitor, registerPlugin } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';

/** True inside the Android app. */
export const isApp = Capacitor.isNativePlatform();

/** Where the API is: '' (this site) on the web, the server in the app. */
export const server: string = isApp ? (import.meta.env.VITE_SERVER ?? '') : '';

/** Redraws the home screen widgets (android/…/widget/WidgetsPlugin.java). */
const widgets = registerPlugin<{ refresh(): Promise<void> }>('Widgets');

/** Payment notifications (android/…/notify): the listener sends them to the server itself. */
const paymentNotifications = registerPlugin<{
  status(): Promise<{ enabled: boolean }>;
  openSettings(): Promise<void>;
  flush(): Promise<void>;
}>('PaymentNotifications');

// Kept in Capacitor's preferences, which the widgets and the notification listener read too.
const sessionKey = 'session';
const serverKey = 'server';
let token = '';

/** Reads the saved session. Call once before the first request. */
export async function loadSession(): Promise<void> {
  if (!isApp) return;
  token = (await Preferences.get({ key: sessionKey })).value ?? '';
  await Preferences.set({ key: serverKey, value: server });
}

/** Headers that sign the app's requests in. */
export function appHeaders(): Record<string, string> {
  if (!isApp) return {};
  const h: Record<string, string> = { 'X-Wallet-Client': 'android' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

/** Keeps the token from a sign-in (or forgets it, with ''). */
export async function saveSession(t: string): Promise<void> {
  if (!isApp || t === token) return;
  token = t;
  if (t) await Preferences.set({ key: sessionKey, value: t });
  else await Preferences.remove({ key: sessionKey });
  refreshWidgets();
  if (t) flushNotifications();
}

/** Whether the app may read payment notifications; null on the web. */
export async function notificationAccess(): Promise<boolean | null> {
  if (!isApp) return null;
  try {
    return (await paymentNotifications.status()).enabled;
  } catch {
    return null; // an app build from before payment notifications
  }
}

/** Opens Android's notification access page at RudWallet's switch. */
export function openNotificationAccess(): Promise<void> {
  return paymentNotifications.openSettings();
}

/** Sends payment notifications the phone kept while offline or signed out. */
function flushNotifications(): void {
  if (isApp) paymentNotifications.flush().catch(() => {});
}

/** Asks the widgets to redraw, e.g. after the user changed something. */
export function refreshWidgets(): void {
  if (isApp) widgets.refresh().catch(() => {});
}

// ---------- Sign in with Google ----------

const verifierKey = 'google-verifier';

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Starts Google sign-in in the phone's browser (Google refuses web views).
 * The server sends the browser back to the app with a one-time code, which
 * onGoogleReturn trades, with the secret kept here, for a session.
 */
export async function startGoogleSignIn(): Promise<void> {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
  await Preferences.set({ key: verifierKey, value: verifier });
  await Browser.open({ url: `${server}/api/auth/google?app_challenge=${challenge}` });
}

let launchHandled = false;

/**
 * Links that open the app: <package>://auth?… from Google sign-in, and
 * <package>://open/<page> from the widgets. onAuth gets the code and secret,
 * or the reason sign-in failed.
 */
export function onAppLinks(handlers: {
  onAuth: (result: { code: string; verifier: string } | { error: string }) => void;
  onOpen: (page: string) => void;
}): () => void {
  if (!isApp) return () => {};
  const handle = async (raw: string) => {
    // Split by hand: web views before Chromium 130 don't parse a custom
    // scheme's host, so new URL() would see neither "auth" nor "open".
    const [where, query = ''] = raw.replace(/^[^:]+:\/\//, '').split('?');
    const [host, ...path] = where.split('/');
    const params = new URLSearchParams(query);
    if (host === 'auth') {
      Browser.close().catch(() => {}); // already closed on some phones
      const code = params.get('code');
      const verifier = (await Preferences.get({ key: verifierKey })).value ?? '';
      await Preferences.remove({ key: verifierKey });
      handlers.onAuth(code && verifier ? { code, verifier } : { error: params.get('signin_error') ?? 'failed' });
    } else if (host === 'open') {
      handlers.onOpen(path.join('/'));
    }
  };
  const listener = CapApp.addListener('appUrlOpen', (e) => void handle(e.url));
  // The link that started the app, e.g. a widget tapped while it was closed.
  if (!launchHandled) {
    launchHandled = true;
    CapApp.getLaunchUrl().then((l) => {
      if (l?.url) void handle(l.url);
    });
  }
  // Leaving the app is a good moment to bring the widgets up to date, and
  // to send notifications that waited for the network.
  const pause = CapApp.addListener('pause', () => {
    refreshWidgets();
    flushNotifications();
  });
  return () => {
    listener.then((l) => l.remove());
    pause.then((l) => l.remove());
  };
}
