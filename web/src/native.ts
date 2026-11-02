// The Android app (android/, Capacitor) runs this same web app from files
// inside the app, at https://localhost. There the API is another origin, so
// requests go to the server named at build time (VITE_SERVER, see
// .env.android) and sign in with a bearer token instead of a cookie (see
// internal/account/app.go). On the web, none of this applies.
import { App as CapApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import type { ICCardRead } from './api';

/** True inside the Android app. */
export const isApp = Capacitor.isNativePlatform();

/** Where the API is: '' (this site) on the web, the server in the app. */
export const server: string = isApp ? (import.meta.env.VITE_SERVER ?? '') : '';

/** Redraws the home screen widgets (android/…/widget/WidgetsPlugin.java). */
const widgets = registerPlugin<{ refresh(): Promise<void> }>('Widgets');

/** The splash screen (android/…/SplashPlugin.java), kept until the app has drawn. */
const splash = registerPlugin<{ hide(): Promise<void> }>('Splash');

/** Takes the phone's splash screen away: call once there's something to see. */
export function appDrawn(): void {
  if (isApp) splash.hide().catch(() => {});
}

/** Payment notifications (android/…/notify): the listener sends them to the server itself. */
const paymentNotifications = registerPlugin<{
  status(): Promise<{ enabled: boolean }>;
  openSettings(): Promise<void>;
  flush(): Promise<void>;
}>('PaymentNotifications');

/** Transit IC cards held to the phone (android/…/ICCardPlugin.java). */
const icCard = registerPlugin<{
  status(): Promise<{ nfc: boolean; enabled: boolean }>;
  openSettings(): Promise<void>;
  read(): Promise<ICCardRead>;
  cancel(): Promise<void>;
  addListener(event: 'tapped', handler: (tap: ICCardTap) => void): Promise<PluginListenerHandle>;
}>('ICCard');

/** A card tapped to the phone, which opened RudWallet: what it read, or why it couldn't (a read error code). */
export type ICCardTap = ICCardRead | { error: string };

// The last card tapped, until the import takes it, and the import while
// it is open.
let tappedCard: ICCardTap | null = null;
let tapHandler: ((tap: ICCardTap) => void) | null = null;

/** Takes the card tapped to the phone, if one is waiting for the import. */
export function takeTappedICCard(): ICCardTap | null {
  const tap = tappedCard;
  tappedCard = null;
  return tap;
}

/** Hands a card read to the import, which takes it as if it had been tapped (the card screen's Review first). */
export function keepICCard(read: ICCardRead): void {
  tappedCard = read;
}

/** Hands cards tapped while the card screen or the import is open straight to it. */
export function onICCardTapped(handler: (tap: ICCardTap) => void): () => void {
  tapHandler = handler;
  return () => {
    if (tapHandler === handler) tapHandler = null;
  };
}

/**
 * Whether this phone can read IC cards: ready, off (NFC is turned off), or
 * null where it can't: on the web, on a phone without NFC, or in an app
 * build from before the reader.
 */
export async function icCardReader(): Promise<'ready' | 'off' | null> {
  if (!isApp) return null;
  try {
    const s = await icCard.status();
    return !s.nfc ? null : s.enabled ? 'ready' : 'off';
  } catch {
    return null;
  }
}

/**
 * Waits for a card to be held to the phone, and reads it. Fails with an
 * error whose code is nfc-off, not-transit, lost (moved away too soon) or
 * cancelled.
 */
export function readICCard(): Promise<ICCardRead> {
  return icCard.read();
}

/** Stops waiting for a card. */
export function cancelICCardRead(): void {
  if (isApp) icCard.cancel().catch(() => {});
}

/** Opens Android's NFC settings. */
export function openNFCSettings(): void {
  icCard.openSettings().catch(() => {});
}

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
  /** A card was tapped to the phone: open the card screen, which takes it (takeTappedICCard). */
  onICCard: () => void;
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
  // A card tapped to the phone. The plugin keeps it until this listens.
  const tapped = icCard
    .addListener('tapped', (tap) => {
      if (tapHandler) {
        tapHandler(tap);
        return;
      }
      tappedCard = tap;
      handlers.onICCard();
    })
    .catch(() => null); // an app build from before the reader
  // Leaving the app is a good moment to bring the widgets up to date, and
  // to send notifications that waited for the network.
  const pause = CapApp.addListener('pause', () => {
    refreshWidgets();
    flushNotifications();
  });
  return () => {
    listener.then((l) => l.remove());
    pause.then((l) => l.remove());
    tapped.then((l) => l?.remove());
  };
}
