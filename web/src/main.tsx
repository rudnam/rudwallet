import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyAppearance } from './appearance';
import { useLanguage } from './i18n';
import './styles.css';

// Light or dark as chosen in Settings, before anything is drawn.
applyAppearance();

/** Picking another language draws the app afresh in it. */
function Root() {
  return <App key={useLanguage()} />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
