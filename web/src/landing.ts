// The introduction page at /. The app moved from / to /app/, so anything
// still meant for the app is sent on there: links to its pages (/#/records),
// the notices older servers redirect to (/?email=confirmed), and the app
// installed from / before the move, which still opens at /.
import './styles.css';
import './landing.css';

const forApp =
  location.hash.startsWith('#/') ||
  /[?&](email|signin_error)=/.test(location.search) ||
  matchMedia('(display-mode: standalone)').matches;
if (forApp) location.replace('/app/' + location.search + location.hash);
else {
  revealOnScroll();
  followSteps();
  offerAPK();
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

// "How it works": the step that has scrolled past a line across the screen
// is the one the stage shows. On a phone the stage sits on top, so the line
// is lower.
function followSteps() {
  const section = document.querySelector('.how');
  const stage = document.querySelector<HTMLElement>('.how-stage');
  const steps = [...document.querySelectorAll<HTMLElement>('.how-step')];
  if (!section || !stage || !steps.length) return;
  section.classList.add('live');
  const narrow = matchMedia('(max-width: 860px)');
  let queued = false;
  const update = () => {
    queued = false;
    const line = innerHeight * (narrow.matches ? 0.72 : 0.58);
    let current = steps[0];
    for (const step of steps) if (step.getBoundingClientRect().top < line) current = step;
    if (stage.dataset.step === current.dataset.step && current.classList.contains('on')) return;
    stage.dataset.step = current.dataset.step;
    for (const step of steps) step.classList.toggle('on', step === current);
  };
  const queue = () => {
    if (!queued) requestAnimationFrame(update);
    queued = true;
  };
  addEventListener('scroll', queue, { passive: true });
  addEventListener('resize', queue);
  update();
}

// The Android app's download shows only when the server has an APK to give.
async function offerAPK() {
  try {
    const res = await fetch('/api/downloads');
    if (!res.ok) return;
    const { android } = (await res.json()) as { android: { url: string; size: number; updated: string } | null };
    if (!android) return;
    document.querySelector<HTMLAnchorElement>('#apk-link')!.href = android.url;
    const size = android.size < 1e6 ? `${Math.max(1, Math.round(android.size / 1e3))} KB` : `${(android.size / 1e6).toFixed(1)} MB`;
    const day = new Date(android.updated).toLocaleDateString('en', { day: 'numeric', month: 'short', year: 'numeric' });
    document.querySelector('#apk-meta')!.textContent = `APK · ${size} · updated ${day}`;
    document.querySelectorAll<HTMLElement>('[data-apk-hidden]').forEach((el) => (el.hidden = false));
  } catch {
    // Offline or an older server: just don't offer it.
  }
}
