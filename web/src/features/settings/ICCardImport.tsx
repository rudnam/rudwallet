// Settings › Import › IC card: transit IC cards (Suica, PASMO, ICOCA…) are
// read over NFC by the Android app, which imports them itself; here it says so.
import { t } from '../../i18n';

export function ICCardImport() {
  return (
    <div className="card stack">
      <h2>{t('IC card')}</h2>
      <p className="small muted">
        {t(
          "Hold a Suica, PASMO, ICOCA or other transit IC card to the back of the phone to import its train and bus fares, shop payments and top-ups. The card keeps only its last 20 entries, so read it every week or two. Mobile Suica on this phone can't be read this way.",
        )}
      </p>
      <p className="muted">{t('This works in the RudWallet app on an Android phone with NFC.')}</p>
    </div>
  );
}
