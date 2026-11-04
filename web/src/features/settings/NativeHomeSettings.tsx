// Settings › Native Wallet (Android app only): turns on Wallet drawn natively
// by the app rather than by this web app (android/…/screens). Off by
// default while it's new; the other pages are still the web app's.
import { useEffect, useState } from 'react';
import { nativeHome, setNativeHome } from '../../native';
import { t } from '../../i18n';

export function NativeHomeSettings() {
  const [on, setOn] = useState<boolean | null>(null);
  useEffect(() => {
    nativeHome().then(setOn, () => setOn(null));
  }, []);

  if (on === null) return <p className="muted">{t('This version of the app can’t do this yet. Update it to try.')}</p>;
  return (
    <>
      <p className="muted lede">
        {t('Wallet drawn by Android itself rather than as a web page: quicker to open, smoother to scroll, and pull down to refresh. The other pages open as before.')}
      </p>
      <div className="card">
        <label className="check">
          <input
            type="checkbox"
            checked={on}
            onChange={async (e) => {
              const next = e.target.checked;
              setOn(next);
              await setNativeHome(next).catch(() => setOn(!next));
            }}
          />
          {t('Use the native Wallet')}
        </label>
      </div>
      <p className="muted small lede">
        {t('New and still being finished: records you added offline show once they’re sent, and cards are rearranged with it turned off.')}
      </p>
    </>
  );
}
