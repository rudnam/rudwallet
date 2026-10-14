// Settings › Language, and the small switch under the sign-in form. The
// choice is kept on this device; without one the app follows the device.
import { useState } from 'react';
import { t, useLanguage } from '../../i18n';
import { deviceLanguage, languages, pickedLanguage, setLanguage, type Language } from '../../language';

/** Each language's name, always in that language, so it can be found from either. */
const nameOf = (l: Language) => languages.find((x) => x.code === l)!.name;

export function LanguageSettings() {
  // Picking another language redraws the whole app; picking Automatic when
  // the device speaks the same one changes only this.
  const [picked, setPicked] = useState(pickedLanguage);
  const choices: { value: Language | null; label: string }[] = [
    { value: null, label: t('Automatic ({language})', { language: nameOf(deviceLanguage()) }) },
    ...languages.map((l) => ({ value: l.code, label: l.name })),
  ];
  return (
    <>
      <div className="card stack" role="radiogroup" aria-label={t('Language')}>
        {choices.map((c) => (
          <label key={c.value ?? 'auto'} className="check">
            <input
              type="radio"
              name="language"
              checked={picked === c.value}
              onChange={() => {
                setPicked(c.value);
                setLanguage(c.value);
              }}
            />
            <span lang={c.value ?? undefined}>{c.label}</span>
          </label>
        ))}
      </div>
      <p className="muted small lede">
        {t('Automatic follows the language your phone or browser is set to. Names you gave accounts and categories stay as they are.')}
      </p>
    </>
  );
}

/** "English · 日本語" under the sign-in form, for before there's a Settings page. */
export function LanguagePicker() {
  const current = useLanguage();
  return (
    <nav className="language-switch" aria-label={t('Language')}>
      {languages.map((l) =>
        l.code === current ? (
          <span key={l.code} lang={l.code} aria-current="true">
            {l.name}
          </span>
        ) : (
          <button key={l.code} type="button" className="link" lang={l.code} onClick={() => setLanguage(l.code)}>
            {l.name}
          </button>
        ),
      )}
    </nav>
  );
}
