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

// Moving between pages: the new page is drawn at once and slides in briefly (well
// under a fifth of a second), with only transform and opacity animating, so it runs
// on the GPU. The old page is gone at once: nothing is captured and nothing
// crossfades. Tabs slide in from the side of the tab chosen, a page opened from one
// comes in from the right and its tab from the left on the way back, a card on
// Wallet grows into its account's page, and on the way back from that page to Wallet
// it shrinks back into its place in the stack. With reduced motion, the page just changes.
const tabs = ['home', 'records', 'stats', 'settings'];
/** Pages opened from a tab, and the tab they belong to (App.tsx's subpages). */
const opened: Record<string, string> = { card: 'home', account: 'home', review: 'records', duplicates: 'records', budgets: 'stats' };

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

const ease = 'cubic-bezier(0.2, 0.8, 0.2, 1)';
const entrances: Record<Move, [Keyframe[], number]> = {
  next: [[{ opacity: 0, transform: 'translateX(14px)' }, { opacity: 1, transform: 'none' }], 160],
  prev: [[{ opacity: 0, transform: 'translateX(-14px)' }, { opacity: 1, transform: 'none' }], 160],
  forward: [[{ opacity: 0, transform: 'translateX(28px)' }, { opacity: 1, transform: 'none' }], 190],
  back: [[{ opacity: 0, transform: 'translateX(-20px)' }, { opacity: 1, transform: 'none' }], 170],
  fade: [[{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], 150],
};

/** The card just tapped on Wallet, so its account's page can grow out of it. */
let tappedCard: { id: string; rect: DOMRect; at: number } | null = null;

/** Called as a card on Wallet is tapped, before its page opens. */
export function openingCard(id: number, rect: DOMRect) {
  tappedCard = { id: String(id), rect, at: performance.now() };
}

/** Grows the account's card out of the card tapped on Wallet, and brings the rest of the page in. */
function growCard(main: HTMLElement, id: string): boolean {
  const from = tappedCard;
  tappedCard = null;
  if (!from || from.id !== id || performance.now() - from.at > 1000) return false;
  const card = main.querySelector<HTMLElement>(`[data-card="${CSS.escape(id)}"]`);
  if (!card) return false;
  const to = card.getBoundingClientRect();
  if (!to.width || !to.height) return false;
  const dx = from.rect.left - to.left;
  const dy = from.rect.top - to.top;
  card.animate(
    [
      { transformOrigin: 'top left', transform: `translate(${dx}px, ${dy}px) scale(${from.rect.width / to.width}, ${from.rect.height / to.height})` },
      { transformOrigin: 'top left', transform: 'none' },
    ],
    { duration: 240, easing: ease },
  );
  for (const el of card.parentElement?.children ?? []) {
    if (el !== card) el.animate([{ opacity: 0, transform: 'translateY(10px)' }, { opacity: 1, transform: 'none' }], { duration: 200, delay: 60, easing: ease, fill: 'backwards' });
  }
  return true;
}

/** Shrinks the account's card on Wallet out of where the account page's card was, and brings the rest of Wallet in. */
function shrinkCard(main: HTMLElement, id: string, from: DOMRect | undefined): boolean {
  if (!from?.width || !from.height || from.bottom < 0 || from.top > innerHeight) return false;
  const card = main.querySelector<HTMLElement>(`[data-card="${CSS.escape(id)}"]`);
  if (!card) return false;
  const to = card.getBoundingClientRect();
  if (!to.width || !to.height) return false;
  const dx = from.left - to.left;
  const dy = from.top - to.top;
  // On its own layer throughout, so it slides under the cards stacked over it as they
  // fade in, rather than landing on top of them and then dropping behind.
  card.animate(
    [
      { transformOrigin: 'top left', transform: `translate(${dx}px, ${dy}px) scale(${from.width / to.width}, ${from.height / to.height})` },
      { transformOrigin: 'top left', transform: 'none' },
    ],
    { duration: 240, easing: ease },
  );
  // Everything else on Wallet fades in around it: the other cards, and the rest of the page.
  for (let el: HTMLElement | null = card; el && el !== main; el = el.parentElement) {
    for (const sib of el.parentElement?.children ?? []) {
      if (sib !== el) sib.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 200, delay: 40, easing: ease, fill: 'backwards' });
    }
  }
  return true;
}

let current = parse();
const listeners = new Set<() => void>();

function onHashChange() {
  const next = parse();
  const how = move(current, next);
  // The account page's card, measured before that page goes, to shrink back into Wallet.
  const leaving =
    current.page === 'account' && next.page === 'home'
      ? document.querySelector<HTMLElement>(`main.page [data-card="${CSS.escape(current.params.get('id') ?? '')}"]`)?.getBoundingClientRect()
      : undefined;
  const leftId = current.params.get('id') ?? '';
  current = next;
  // Drawn now, so the new page is on screen in this same frame and can be animated.
  flushSync(() => listeners.forEach((l) => l()));
  window.scrollTo(0, 0);
  const html = document.documentElement;
  const main = document.querySelector<HTMLElement>('main.page');
  if (!how || !main || next.page === 'add' || matchMedia('(prefers-reduced-motion: reduce)').matches) {
    tappedCard = null;
    return;
  }
  // The last move, for the tests.
  if (how === 'forward' && next.page === 'account' && growCard(main, next.params.get('id') ?? '')) {
    html.dataset.nav = 'card';
    return;
  }
  if (how === 'back' && leaving && shrinkCard(main, leftId, leaving)) {
    html.dataset.nav = 'card-back';
    return;
  }
  tappedCard = null;
  html.dataset.nav = how;
  const [frames, duration] = entrances[how];
  main.animate(frames, { duration, easing: ease });
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
