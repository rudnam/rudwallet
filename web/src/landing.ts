// The introduction page at /. The app moved from / to /app/, so anything
// still meant for the app is sent on there: links to its pages (/#/records),
// the notices older servers redirect to (/?email=confirmed), and the app
// installed from / before the move, which still opens at /.
import './styles.css';
import './landing.css';
import { language, setLanguage } from './language';

const forApp =
  location.hash.startsWith('#/') ||
  /[?&](email|signin_error)=/.test(location.search) ||
  matchMedia('(display-mode: standalone)').matches;
if (forApp) location.replace('/app/' + location.search + location.hash);
else {
  translate();
  document.querySelector('#language-toggle')?.addEventListener('click', () => {
    setLanguage(language() === 'ja' ? 'en' : 'ja');
    translate();
  });
  revealOnScroll();
  offerDemo();
}

// The page is written in English; each text's Japanese is in its data-ja
// (HTML, ours). The English is kept in data-en to switch back.
function translate() {
  const ja = language() === 'ja';
  document.querySelectorAll<HTMLElement>('[data-ja]').forEach((el) => {
    el.dataset.en ??= el.innerHTML;
    el.innerHTML = ja ? el.dataset.ja! : el.dataset.en;
  });
  const description = document.querySelector<HTMLMetaElement>('meta[data-ja-content]');
  if (description) {
    description.dataset.en ??= description.content;
    description.content = ja ? description.dataset.jaContent! : description.dataset.en;
  }
  // The switch names the other language, in that language.
  const toggle = document.querySelector<HTMLElement>('#language-toggle');
  if (toggle) toggle.lang = ja ? 'en' : 'ja';
}

// Sections fade up as they come into view, once each. With reduced motion
// (or no IntersectionObserver) nothing is hidden in the first place.
function revealOnScroll() {
  if (matchMedia('(prefers-reduced-motion: reduce)').matches || !('IntersectionObserver' in window)) return;
  document.documentElement.classList.add('motion');
  const seen = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        e.target.classList.add('in');
        seen.unobserve(e.target);
      }
    },
    { rootMargin: '0px 0px -12% 0px' },
  );
  document.querySelectorAll('.reveal').forEach((el) => seen.observe(el));
}

// "Try the demo" shows only when the server offers demos.
async function offerDemo() {
  try {
    const res = await fetch('/api/auth/options');
    if (!res.ok) return;
    const { demo } = (await res.json()) as { demo?: boolean };
    if (demo) document.querySelectorAll<HTMLElement>('[data-demo-hidden]').forEach((el) => (el.hidden = false));
  } catch {
    // Offline or an older server: just don't offer it.
  }
}
