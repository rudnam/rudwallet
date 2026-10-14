import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { useLanguage } from './i18n';
import { loadSession } from './native';
import './styles.css';

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
