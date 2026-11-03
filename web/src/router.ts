// A tiny hash router: #/records?category=3. Hash routes need no server
// support and survive reloads.
import { useSyncExternalStore } from 'react';
import { flushSync } from 'react-dom';

export interface Route {
  page: string;
  params: URLSearchParams;
}

function parse(): Route {
  const [path, qs = ''] = location.hash.replace(/^#\/?/, '').split('?');
  return { page: path || 'home', params: new URLSearchParams(qs) };
}

// Moving between pages animates with the browser's View Transitions (styles.css,
// "Page transitions"): tabs slide towards the tab chosen, a page opened from one
// comes in from the right and goes back the way it came, and a card on Wallet
// grows into its account's page. Without the API, or with reduced motion, the page
// just changes.
const tabs = ['home', 'records', 'stats', 'settings'];
/** Pages opened from a tab, and the tab they belong to (App.tsx's subpages). */
const opened: Record<string, string> = { card: 'home', account: 'home', review: 'records', budgets: 'stats' };

type Move = 'next' | 'prev' | 'forward' | 'back' | 'fade';

function place(r: Route) {
  return {
    tab: tabs.indexOf(opened[r.page] ?? r.page),
    deep: r.page in opened || (r.page === 'settings' && r.params.has('section')),
  };
}

/** How a change of route moves, or null when it stays on the same page (a new month, a filter). */
function move(from: Route, to: Route): Move | null {
  if (from.page === to.page && from.params.get('section') === to.params.get('section')) return null;
  const a = place(from);
  const b = place(to);
  if (a.tab < 0 || b.tab < 0) return 'fade';
  if (a.tab !== b.tab) return b.tab > a.tab ? 'next' : 'prev';
  if (b.deep && !a.deep) return 'forward';
  if (a.deep && !b.deep) return 'back';
  return 'fade';
}

let current = parse();
const listeners = new Set<() => void>();

function onHashChange() {
  const next = parse();
  const how = move(current, next);
  const show = () => {
    current = next;
    listeners.forEach((l) => l());
    window.scrollTo(0, 0);
  };
  const html = document.documentElement;
  if (
    !how ||
    next.page === 'add' || // the new record sheet, not a page
    !document.startViewTransition ||
    document.visibilityState !== 'visible' ||
    matchMedia('(prefers-reduced-motion: reduce)').matches
  ) {
    show();
    return;
  }
  html.dataset.nav = how;
  // The card opened (or returned to) on Wallet and the card at the top of its account's
  // page share a name, before and after the change, so the one grows into the other.
  const account = (next.page === 'account' ? next : current.page === 'account' ? current : null)?.params.get('id');
  const card = () => (account ? document.querySelector<HTMLElement>(`[data-card="${CSS.escape(account)}"]`) : null);
  const name = (el: HTMLElement | null) => el?.style.setProperty('view-transition-name', 'account-card');
  name(card());
  const transition = document.startViewTransition(() => {
    flushSync(show);
    name(card());
  });
  // A transition skipped for the next one, or one the page was too busy to draw,
  // rejects these; the page has changed either way.
  transition.ready.catch(() => {});
  transition.updateCallbackDone.catch(() => {});
  void transition.finished.finally(() => {
    card()?.style.removeProperty('view-transition-name');
    if (html.dataset.nav === how) delete html.dataset.nav;
  });
}
window.addEventListener('hashchange', onHashChange);

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useRoute(): Route {
  return useSyncExternalStore(subscribe, () => current);
}

export function href(page: string, params?: Record<string, string | number>): string {
  const qs = params ? new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString() : '';
  return `#/${page}${qs ? `?${qs}` : ''}`;
}

export function navigate(page: string, params?: Record<string, string | number>) {
  location.hash = href(page, params);
}

/** navigate, replacing the current history entry, e.g. to drop a used link's token. */
export function replaceRoute(page: string, params?: Record<string, string | number>) {
  history.replaceState(null, '', href(page, params));
  window.dispatchEvent(new HashChangeEvent('hashchange'));
}
