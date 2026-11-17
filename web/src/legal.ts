// The privacy policy (/privacy) and how to delete an account
// (/delete-account): plain pages in the introduction's styles, linked from
// the app's Play Store listing. Like the introduction, each is written in
// English with every text's Japanese in its data-ja, shown in the reader's
// language and switched with the button in the bar.
import './styles.css';
import './landing.css';
import './legal.css';
import { language, setLanguage } from './language';

translate();
document.querySelector('#language-toggle')?.addEventListener('click', () => {
  setLanguage(language() === 'ja' ? 'en' : 'ja');
  translate();
});

function translate() {
  const ja = language() === 'ja';
  document.documentElement.lang = ja ? 'ja' : 'en';
  document.querySelectorAll<HTMLElement>('[data-ja]').forEach((el) => {
    el.dataset.en ??= el.innerHTML;
    el.innerHTML = ja ? el.dataset.ja! : el.dataset.en;
  });
  const toggle = document.querySelector<HTMLElement>('#language-toggle');
  if (toggle) toggle.lang = ja ? 'en' : 'ja';
}
