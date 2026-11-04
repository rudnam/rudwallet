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

/**
 * The Android app's native navigation (android/…/web/NativeNavPlugin.kt).
 * In an app build with native screens, the app draws the tabs, the plus
 * and the pages that are native, and shows this web app only for the
 * pages that aren't yet, under its own tabs. This web app is then
 * "embedded": it doesn't draw its own navigation, asks the app for every
 * page change (go) and shows the page the app tells it to (show). Older
 * app builds have no NativeNav, and the web app runs the whole app as before.
 */
const nativeNav = registerPlugin<{
  config(): Promise<NativeConfig>;
  go(o: { path: string; replace?: boolean }): Promise<void>;
  back(): Promise<void>;
  overlay(o: { open: boolean }): Promise<void>;
  setAppearance(o: { appearance: string }): Promise<void>;
  setLanguage(o: { language: string }): Promise<void>;
  addListener(event: 'show', f: (e: { path: string }) => void): Promise<PluginListenerHandle>;
  addListener(event: 'back', f: () => void): Promise<PluginListenerHandle>;
}>('NativeNav');

export interface NativeConfig {
  /** The pages the app draws itself, e.g. ['home']. */
  nativePages: string[];
  appearance: 'auto' | 'light' | 'dark';
  /** 'ja', 'en', or '' for the phone's. */
  language: string;
}

/** True in an app build whose navigation is native (see nativeNav). */
export const embedded: boolean = isApp && Capacitor.isPluginAvailable('NativeNav');

/** What the app says at start: which pages it draws itself, and the settings this web app follows. */
export function nativeConfig(): Promise<NativeConfig | null> {
  return embedded ? nativeNav.config().catch(() => null) : Promise.resolve(null);
}

/** Asks the app for a page, e.g. "records?account=3". [replace]: instead of the page shown now. */
export function nativeGo(path: string, replace = false): void {
  nativeNav.go({ path, replace }).catch(() => {});
}

/** Leaves the page shown now (a sheet opened on its own was closed). */
export function nativeBack(): void {
  nativeNav.back().catch(() => {});
}

/** Tells the app a sheet or dialog is open, so its back gesture closes that first. */
export function nativeOverlay(open: boolean): void {
  if (embedded) nativeNav.overlay({ open }).catch(() => {});
}

/** Settings still chosen here, for the app to follow. */
export function nativeSetAppearance(appearance: string): void {
  if (embedded) nativeNav.setAppearance({ appearance }).catch(() => {});
}

export function nativeSetLanguage(language: string | null): void {
  if (embedded) nativeNav.setLanguage({ language: language ?? '' }).catch(() => {});
}

/** The page the app asks this web app to show. */
export function onNativeShow(f: (path: string) => void): void {
  if (embedded) nativeNav.addListener('show', (e) => f(e.path)).catch(() => {});
}

/** The app's back gesture while a sheet or dialog is open. */
export function onNativeBack(f: () => void): void {
  if (embedded) nativeNav.addListener('back', f).catch(() => {});
}

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
// The address of the page that took the handler: a page on its way out (the address
// has moved on, but a page transition hasn't swapped it yet) doesn't take taps.
let tapOwner = '';

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
  tapOwner = location.hash;
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
    } else if (host === 'open' && !embedded) {
      // Embedded, the app opens its own links (MainActivity).
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
      if (tapHandler && location.hash === tapOwner) {
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

/** The phone's haptics (android/…/HapticsPlugin.java); nothing on the web or in an app build from before them. */
const haptics = registerPlugin<Record<'key' | 'select' | 'threshold' | 'success' | 'error', () => Promise<void>>>('Haptics');

// Haptics mark moments, not every touch: a short gap keeps fast typing on
// the number pad from running together into a buzz.
let lastHaptic = 0;
function haptic(kind: 'key' | 'select' | 'threshold' | 'success' | 'error', gap = 35): void {
  if (!isApp) return;
  const now = performance.now();
  if (now - lastHaptic < gap && kind !== 'success' && kind !== 'error') return;
  lastHaptic = now;
  haptics[kind]().catch(() => {});
}

/** A key on the number pad: as light as the phone's keyboard. */
export const hapticKey = () => haptic('key');
/** A choice made: a category, an account, expense or income. */
export const hapticSelect = () => haptic('select');
/** A swipe passing the point where letting go acts. */
export const hapticThreshold = () => haptic('threshold');
/** Something saved or confirmed. */
export const hapticSuccess = () => haptic('success');
/** Something that couldn't be done, such as saving without an amount. */
export const hapticError = () => haptic('error');
