# Design direction

Mockups of the redesign agreed so far. Each screen has a PNG (as reviewed) and
the HTML it was drawn from; open the HTML in a browser at 390 px wide. Fonts
come from Google Fonts. These are drawings, not the app: the app itself is in
`web/src/`.

| Screen | Image | Source |
|---|---|---|
| Wallet (home) | `wallet.png` | `wallet.html` |
| Activity | `activity.png` | `activity.html` |
| Account (opened from the card stack) | `account.png` | `account.html` |
| Add a record | `add.png` | `add.html` |
| Insights | `insights.png` | `insights.html` |
| Settings | `settings.png` | `settings.html` |

Each screen also has a light version, `<screen>-light.png`: the same HTML
with `light.css` added after its own styles.

`style.css` holds what the screens share (except `wallet.html`, which keeps
its own).

## Decisions

- **Dark and light**, both flat: `#0C0D0F` and `#F5F5F3`, with no glow or
  gradient. Appearance follows the phone unless changed in Settings. In
  light mode the cards keep their full colour, and the main action (the
  plus, Save) turns near-black instead of white.
- **Geist** for text and figures. Japanese uses the phone's own font (Noto
  Sans CJK JP on Android), so no Japanese font ships with the app.
- **Accounts are a stack of cards** in each account's colour, straight (not
  tilted), separated by a faint edge and a soft shadow. Tapping a card opens
  that account.
- **Lists are thin outlined panels**; one accent of white for the main
  action (the plus, Save). Orange only for something that needs a look.
- **Four tabs**: Wallet, Activity, Insights, Settings, plus a floating add
  button. Review lives at the top of Activity as "to check", shown only when
  something is waiting (most payments confirm themselves with auto-confirm).
- **The app never asks on a schedule.** It shows the state and offers the
  action: Cash shows when it was last counted and a Count cash button, with
  no weekly prompt.
- **Adding by hand** is three taps: amount on the docked number pad,
  category, Save.
- **Settings is one list of plain rows**, grouped as Money, Recording
  payments and App, with a muted line icon and the current value on the
  right.
