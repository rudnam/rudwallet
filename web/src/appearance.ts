// Light, dark, or whatever the phone uses (the default). Kept on this
// device: it's how this screen looks, not part of the wallet. styles.css
// reads html[data-theme]; with none, it follows prefers-color-scheme.
import { useSyncExternalStore } from 'react';
import { nativeSetAppearance } from './native';

export type Appearance = 'auto' | 'light' | 'dark';

const key = 'wallet:appearance';
const listeners = new Set<() => void>();

export function appearance(): Appearance {
  try {
    const v = localStorage.getItem(key);
    return v === 'light' || v === 'dark' ? v : 'auto';
  } catch {
    return 'auto';
  }
}

/** Applies the saved choice to the page: call once, before the first draw. */
export function applyAppearance(): void {
  const a = appearance();
  const root = document.documentElement;
  if (a === 'auto') root.removeAttribute('data-theme');
  else root.dataset.theme = a;
  // The browser's toolbar (and the Android app's status bar) follows the page.
  const dark = a === 'dark' || (a === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    if (a === 'auto') meta.content = meta.media.includes('dark') ? '#0c0d0f' : '#f5f5f3';
    else meta.content = dark ? '#0c0d0f' : '#f5f5f3';
  }
}

/** [fromApp]: the Android app said so (native.ts), so it isn't told again. */
export function setAppearance(a: Appearance, fromApp = false): void {
  if (!fromApp) nativeSetAppearance(a);
  try {
    if (a === 'auto') localStorage.removeItem(key);
    else localStorage.setItem(key, a);
  } catch {
    /* not kept: it still applies until the app is closed */
  }
  applyAppearance();
  for (const l of listeners) l();
}

export function useAppearance(): Appearance {
  return useSyncExternalStore((l) => {
    listeners.add(l);
    return () => listeners.delete(l);
  }, appearance);
}
