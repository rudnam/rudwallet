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

`style.css` holds what the screens share (except `wallet.html`, which keeps
its own).

## Decisions

- **Dark first**, flat background `#0C0D0F`: no glow or gradient behind it.
  Light mode is still to be drawn.
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
