// Settings › Home screen widgets: what the Android app's widgets show, and
// how to add one. They use the app's own sign-in, so there's nothing to set up.
import { isApp } from '../../native';

const widgets = [
  { name: 'This month', about: 'Spent so far, what’s left of your budget, and payments to review.' },
  { name: 'Budgets', about: 'Each monthly budget: what’s spent, and what’s left.' },
  { name: 'New record', about: 'One tap to add a record.' },
];

export function WidgetSettings() {
  return (
    <>
      <p className="muted lede">
        {isApp ? 'Keep this month in view from your home screen.' : 'The Wallet app for Android keeps this month in view from your home screen.'}{' '}
        Touch and hold an empty part of the home screen, tap <strong>Widgets</strong>, and find <strong>Wallet</strong>:
      </p>
      <ul className="list card flush" aria-label="Widgets">
        {widgets.map((w) => (
          <li key={w.name} className="record">
            <span className="grow">
              <span className="record-title">{w.name}</span>
              <span className="muted small">{w.about}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="muted small lede">They show what this app is signed in to, and update about every half hour and whenever you leave the app.</p>
    </>
  );
}
