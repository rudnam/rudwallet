import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyAppearance } from './appearance';
import { useLanguage } from './i18n';
import { embedded, loadSession } from './native';
import './styles.css';

// Light or dark as chosen in Settings, before anything is drawn.
applyAppearance();

// In an Android app build with native navigation, the app draws the tabs and the plus (native.ts, styles.css).
if (embedded) document.documentElement.classList.add('embedded');

// In the Android app, the saved sign-in must be read before the first request.
await loadSession();

/** Picking another language draws the app afresh in it. */
function Root() {
  return <App key={useLanguage()} />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
