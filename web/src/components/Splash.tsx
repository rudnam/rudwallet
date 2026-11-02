import { t } from '../i18n';
import { Logo } from './Icon';

/**
 * What shows while the app starts: the logo, breathing, in the middle of the
 * screen, with words only if it takes a while. index.html draws the same
 * before the script runs, so nothing moves when the app takes over.
 */
export function Splash({ label = t('Loading…') }: { label?: string }) {
  return (
    <main className="splash">
      <div className="loading" role="status">
        <Logo />
        <span className="loading-bar" aria-hidden="true" />
        <span className="loading-label">{label}</span>
      </div>
    </main>
  );
}
