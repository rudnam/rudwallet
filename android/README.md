# Android app

The Android app is becoming a native app (Kotlin, Jetpack Compose, Material 3), a page at a time. The app's shell is native: the activity, the four tabs, navigation, **Wallet** (holding a card rearranges the stack), **Activity**, the **account page** (with Count cash), and the **record sheet** (adding, changing, confirming and deleting a record). The pages not ported yet are still the web app (`web/`), built into the app and shown by [Capacitor](https://capacitorjs.com) in a web view under the native tabs. When the last page is native, Capacitor and the web bundle leave the app; the website stays. See **Native app** below for how it's built, and **The web view, until it's gone** for how the two fit together.

Native code also does what the web can't: home screen widgets, Sign in with Google through the phone's browser, reading payment notifications, and reading transit IC cards over NFC.

- Package `com.rudnam.wallet` (permanent once published), talking to https://wallet.rudnam.com (`web/.env.android`).
- Web app changes reach phones only with a new app build. Server changes reach them at once, so keep the API compatible with the last release (add fields; don't rename or remove them).
- Signing key: `android.keystore` (alias `android`), with its password in `signing-key.txt`. Both live in `~/.config/rudwallet/` on the developer's machine, outside any checkout or worktree (`android/` has links to them, git-ignored), and in the `android-release` GitHub environment, which CI releases with. GitHub can't give secrets back, so **back the two files up together somewhere safe**: without them no update installs over the app.
- Upload key SHA-256: `82:07:7F:FC:52:BC:0C:27:7F:AE:A2:6F:D0:33:D4:03:0C:5F:8D:9D:56:3D:70:94:E8:48:64:18:54:E3:CB:61` (since versionCode 4, October 2026). The key before it (`A1:58:90:…:04:6D`, versions 1–3) was lost, so phones with version 3 or older must uninstall the app once and install the new one; their data is on the server.

## Build

You need a JDK 21, the Android SDK (`JAVA_HOME` and `ANDROID_HOME`, or the paths in `~/.bubblewrap/config.json` on this machine) and Node.

```sh
./android/build.sh            # android/wallet.aab (Play Store) and android/wallet.apk
./android/build.sh install    # and install the APK on the phone plugged in (USB debugging on)
```

It builds the web app for the app (`npm run android` in `web/`, which also copies it in with `cap sync`), then signs both files with `android.keystore`, reading the passwords from `signing-key.txt`.

CI builds a debug APK on every push and pull request (the `android` job in `.github/workflows/ci.yml`, kept for a week as the `android-debug-apk` artifact), so a change that breaks the native code fails there. It needs no signing key.

A debug build is **RudWallet Preview** (`com.rudnam.wallet.preview`, `app/src/debug`): it installs beside the real app and talks to the same server. On a pull request, CI also publishes it as a pre-release, `android-pr-<number>`, and comments the download link, which opens on a phone (GitHub Mobile can't download artifacts). The repository is private, so the link needs the phone's browser signed in to GitHub; signed out, GitHub answers 404. It's rebuilt on every push, and deleted when the pull request closes (`android-preview-cleanup.yml`). Google sign-in returns to the real app (the server names its package), so sign in to the preview with email and password.

The version is chosen for you: `version.sh` gives one above the `versionCode` the site offers (`app/build.gradle.kts` holds only the floor), and the name is its `versionName` plus it, e.g. `2.1.5`. Set `WALLET_VERSION_CODE` or `WALLET_VERSION_NAME` to choose your own. For a Play Store release, upload `wallet.aab` in the Play Console. Versions 1 and 2 were the earlier Trusted Web Activity app, which this replaces under the same package and key, so it installs as an update.

### Releasing

**From GitHub (usual):** label a pull request `android-release`, and once it's merged and CI has passed on `main`, the **Android release** workflow releases it. Or run it by hand: Actions → **Android release** → Run workflow, on `main`, once CI has passed on it. It builds and signs the app with `build.sh`, publishes the APK with `publish.sh`, and keeps the APK and the `.aab` as a GitHub release named `android-<versionCode>`. The signing key is in that environment's secrets (`.github/workflows/android-release.yml` lists them); to put it there again, e.g. after rotating it:

```sh
gh secret set ANDROID_KEYSTORE_BASE64 --env android-release < <(base64 -w0 android/android.keystore)
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release --body "$(sed -n 's/^Keystore password: //p' android/signing-key.txt)"
gh secret set ANDROID_KEY_PASSWORD --env android-release --body "$(sed -n 's/^Key password: //p' android/signing-key.txt)"
```

**From this machine**, the same steps by hand.

The introduction page offers the APK as **Download for Android** while the server has one, with no restart needed. To put a new build on https://wallet.rudnam.com:

```sh
./android/build.sh && ./android/publish.sh   # needs: aws login --profile rudnam-ec2
```

`publish.sh` uploads it to the deploy bucket on AWS and copies it onto the server; every deploy copies it again. `build.sh` also copies it to `downloads/wallet.apk`, which the local stack (`docker compose up`) offers the same way.

A copy installed from the Play Store is signed by Google, so installing `wallet.apk` over it fails; uninstall it first.

### Updates

The app tells people when a newer APK is out. When it starts, and when it comes back into view (at most hourly), it asks `GET /api/downloads`, where the server reports the `versionCode` it reads from the APK's manifest, and compares it with its own (`web/src/updates.ts`). If the server's is higher, a banner offers **Update**, which downloads the APK in the phone's browser, or **Later**, which hides it until a still newer build. Every release gets a higher `versionCode` (`version.sh`), so everyone on a build that checks is told. Copies from the Play Store (`UpdatesPlugin` reports the installer) are left to the Play Store.

Builds from before this (`versionCode` 3 and older) don't check, and were signed with the old key, so those phones need to uninstall once and install the new build by hand.

While working on the app, `cd web && npm run android`, then open `android/` in Android Studio, or `cd android && ./gradlew installDebug`. A debug build needs no signing key.

## Native app

The structure follows Android's [architecture guide](https://developer.android.com/topic/architecture): one activity, Compose screens with a ViewModel each, data flowing one way, an offline-first data layer, and Hilt putting it together.

| Module | What it is |
|---|---|
| `app` | The activity (`MainActivity`: edge to edge, the splash screen, links into the app), the app's scaffold and tabs (`ui/RudWalletApp.kt`), navigation (`ui/RudWalletAppState.kt`, `navigation/`), and the web view host (`web/`). Also the Java that predates the native app: widgets, payment notifications, the Capacitor plugins. |
| `feature/wallet` | Wallet: `WalletViewModel` (state as a `StateFlow` of `WalletUiState`), `WalletScreen`, the card stack, and its navigation entry. Each tab becomes a `feature/*` module like this one. |
| `feature/activity` | Activity (`ActivityRoute`, also the website's `records` and `review` paths with their filters): a month's records with account, category and tag filters (on the phone, so they work offline), search through every month (on the server), "N to check" (records to review, kept on the phone: pick a category and Confirm, swipe right, or confirm all that are ready; a possible duplicate shows its match), and choosing many records to recategorise, move, tag or delete at once (with Undo). The screen takes `ActivityActions`, which the ViewModel implements. |
| `feature/account` | One account (`AccountRoute`, the website's `account?id=…`): its card, this month in and out, its records (with the time under each amount), and **Count cash** for cash accounts, which records the difference (`/api/accounts/{id}/reconcile`). |
| `feature/insights` | Insights, the third tab (`InsightsRoute`, the website's `stats` with its `span`, `start`, `end` and `account`): a week, month, year or range of dates (Material's date range picker), what was spent with income and net, the rest of the month (where it's heading, and what each budget allows a day), spending by category (a tap opens those records in Activity), trends against the three periods before at the same point, and a column chart drawn on a `Canvas` (tap or drag across it for a column's amount). The figures are the server's (`/api/stats`), asked for each period and the ones it's compared with; `Periods.kt` and `Insights.kt` work them out in plain Kotlin. The budgets (`BudgetsRoute`, the website's `budgets`): a meter each, and a sheet to make, change or delete one. |
| `feature/settings` | Settings, the fourth tab (`SettingsRoute`): the menu in groups and who's signed in, with Sign out (or End demo) and Delete account (confirmed with the password, or a Google-only account's email). Native pages (`SettingsSectionRoute`, the website's `settings?section=…`): Accounts (kind, name with suggestions, balance or what a card owes, colour, archived), Categories (name, kind, colour and icon, and the default ones not added yet), Currency (`general`), Appearance and Language (Android's per-app language), Shops and cards (`rules`: each shop's category and each card's account, to change or forget, and shops added by hand), Payment notifications (notification access, checked again on coming back from Android's settings; auto-confirm, where payments go, and the recent notifications), Home screen widgets (each can be put on the home screen from here) and Claude (the MCP address, connection tokens shown once, and how to connect). Import is still a web page. |
| `feature/record` | The record sheet (`RecordRoute`, a dialog destination holding Material's modal bottom sheet): the number pad, category tiles (the most used first), a shop's usual category and account, accounts, date and time pickers, note and tags; "Save changes?" for an edit, and confirming a record to review. `RecordForm.kt` is its logic in plain Kotlin. |
| `core/model` | The app's types (accounts, categories, records, budgets) in plain Kotlin. |
| `core/network` | The API with Retrofit and kotlinx.serialization (`WalletNetworkDataSource`); `AuthInterceptor` signs requests in as the app. The server's address is `VITE_SERVER` in `web/.env.android`, or `-Prudwallet.server=…`. |
| `core/database` | Room: the phone's copy of the wallet. Schemas are kept in `core/database/schemas`; a new version adds a migration. |
| `core/data` | Repositories, offline first: screens read Room's flows, and `refresh` brings Room up to date from the server (`OfflineFirstWalletRepository`). `RecordRepository` adds, changes and deletes records; a new one without a connection goes to the outbox, and `OutboxWorker` (WorkManager, Hilt) sends it when there's one. `RecordEvents` tells the app what the sheet did, for its snackbar and Undo. `ActivityRepository` keeps the records to review, searches every month, and changes many records at once (`/api/transactions/bulk`). `AccountRepository` counts an account and saves the card stack's order (`/api/accounts/order`, on the phone at once). `InsightsRepository` asks for stats (kept in memory since the app started, so a period seen before shows at once while it's asked again; not in Room, as they're totals over any period) and saves budgets (on the server, then in Room). `SettingsRepository` changes the currency, accounts and categories on the server, then fetches the wallet again. `UserRepository` knows who's signed in (`/api/me`, kept in DataStore for offline), signs out (sending the outbox first) and deletes the account; `SessionEvents` tells the app, which tells the web view. `UpdateRepository` offers newer builds. |
| `core/datastore` | The session (for now, the token the web app keeps in Capacitor's preferences) and the user's preferences (DataStore). |
| `core/designsystem` | RudWallet on Material 3: the colours (`theme/Theme.kt`, the same tokens as `web/src/styles.css`, including `chart` for charts' one series), Inter (`res/font`, converted from `web/src/fonts` by `android/fonts.py`), the line icons, `WalletPanel`. |
| `core/ui` | Pieces that know the models: `MoneyFormat` (money, compact amounts for axes, dates and date ranges in the app's language), `ServerText` (the server's English messages in Japanese: the website's table, which `ServerTextTest` keeps the same), `AccountCard`, `CategoryIcon`, the record list. |
| `core/common`, `core/testing` | Dispatchers and the clock, injected; fakes and a sample wallet for tests. |

Build setup: versions in `gradle/libs.versions.toml`, shared module setup in `build-logic` (convention plugins `rudwallet.android.*`). AGP and Kotlin stay on the versions Capacitor's modules are built for until Capacitor leaves. Release builds are shrunk with R8 (`app/proguard-rules.pro`; Capacitor and the libraries bring their own rules). Strings are resources, in English and Japanese (`values-ja`), and the app's language is Android's per-app language: the system's app settings, or the web app's Settings › Language, which passes it on.

**Tests** run on the JVM: `./gradlew testDebugUnitTest` in `android/`. ViewModels against fake repositories (`core/testing`), the repository against an in-memory database, the API's JSON and sign-in against a mock server, navigation, and screenshots of the screens with Robolectric and Roborazzi (light, dark, Japanese) in each module's `build/outputs/roborazzi` with `-Proborazzi.test.record=true`. CI runs them in the `android` job, runs Android Lint on the native modules (`./gradlew lintNative`), builds a release (to check R8), and keeps the screenshots as the `native-screenshots` artifact. `./verify.sh` doesn't run them; its browser tests cover the web app's side (`e2e/tests/embedded.spec.js`).

### The web view, until it's gone

`WebPageFragment` holds Capacitor's bridge: one web view for the life of the activity, laid out under the native tabs, clear of the system bars and the keyboard (`RudWalletApp.kt`, `WebHost`). A page that isn't native is a `WebRoute(path)` (or a tab's own route): its destination is empty, and the web view underneath shows it.

The web app knows it's inside the native app when the `NativeNav` plugin exists (`embedded` in `web/src/native.ts`), and then:

- It draws no tabs and no plus of its own (`.embedded` in `web/src/styles.css`).
- It asks for every page change (`NativeNav.go`) instead of making it, and shows the page the app says (`show`); native navigation keeps the back stack, and Android's back gesture moves through it. A sheet or dialog that's open tells the app (`overlay`), so back closes it first (`back`). Capacitor's own back handling is off (`App.disableBackButtonHandler` in `web/capacitor.config.json`).
- Asking for `#/add` (the widget's New record, say) opens the app's own record sheet.
- Appearance and Language follow the app (`config`, and the `appearance` event when it changes; a new language remakes the activity and the web view with it).
- Signing out or deleting the account in the app's Settings tells it (`signedOut`), and it forgets the session too.
- It handles Google sign-in coming back and IC cards itself (`MainActivity` passes those intents to the web view); `<package>://open/<page>` from the widgets the app opens itself.

Signing in still happens in the web view: the app follows the session token (`SessionStore`), showing the sign-in page when it goes and Wallet when it comes, and empties its database on signing out.

To make a page native: add its `feature/*` module with a route, open its paths natively in `RudWalletAppState.openPath`, add it to `NativeNavPlugin.NATIVE_PAGES`, and make it its tab's route if it's a tab.

## Starting up, and working offline

The splash screen (`styles.xml`, Android 12's, back-ported) stays up until the session is known and, signed out, until the web view has drawn the sign-in page (`SplashPlugin`), 3 seconds at most. Wallet opens on what's in the database at once and asks the server meanwhile; offline, it shows what it has, and pulling down to refresh says the server can't be reached.

A record added in the record sheet without a connection goes to the outbox (Room's `outbox` table) with a `client_id`, and shows at once in the lists with **Not sent yet**, counted in its account's balance. `OutboxWorker` sends what's waiting, oldest first, as soon as there's a connection, even if the app was closed since; sending one twice adds it once. Until it's sent it can be changed or deleted on the phone; changing or deleting a record the server has needs a connection. One the server refuses stays with **Couldn't send** until it's changed or deleted. Signing out empties the database, the outbox with it.

Haptics mark moments, not every touch (`HapticsPlugin`, called through `web/src/native.ts`): a number pad key feels like the phone's keyboard (`KEYBOARD_TAP`); picking a category, an account or expense/income is a light tick (`SEGMENT_TICK`, `CLOCK_TICK` before Android 14); a swipe passing the point where letting go acts is a detent (`GESTURE_THRESHOLD_ACTIVATE`); something saved or confirmed is `CONFIRM`; something refused, such as saving without an amount, is `REJECT` (both from Android 11, with the nearest older feedback before). Opening pages and rows doesn't buzz. All follow the system's touch feedback setting and need no permission; taps closer than 35 ms apart are merged, so fast typing doesn't run into a buzz. App builds from before simply don't tap.

In the web view's pages, a record added there without a connection is kept by the web app (`web/src/outbox.ts`), counted in the balances and lists with a **Not sent yet** badge, and sent when the server answers again: on the next start, when the app comes back into view, or when the phone is back online. It carries a `client_id`, so a resend after a lost answer doesn't add it twice. Until it's sent it can be edited or deleted on the phone; other edits and deletes need a connection. One the server refuses (say, its account was deleted meanwhile) stays with **Couldn't send** until it's changed or deleted.

## How the app signs in

The app's requests come from `https://localhost`, another origin, so the browser's cookie doesn't apply. The server allows that origin (CORS), and the app signs in with a bearer token instead: it sends `X-Wallet-Client` when signing in, keeps the token it gets back in Capacitor's preferences, and sends it with every request. App sessions last a year. `web/src/native.ts` and `internal/account/app.go` explain the details.

**Sign in with Google.** Google refuses sign-in inside a web view, so the app opens the phone's browser at `/api/auth/google`. When Google sends the browser back, the server sends it on to `com.rudnam.wallet://auth?code=…`, which Android hands to the app; the app trades the code, with a secret only it knows, for a session token. This needs `ANDROID_PACKAGE` on the server (it names the app to return to) and nothing new in Google's console: the redirect address is the same as for the web.

## Home screen widgets

Three widgets, written natively (Java, framework APIs only) in `app/src/main/java/com/rudnam/wallet/widget`:

- **This month**: what Wallet's month line says: spent this month, the meter of the budget for all expenses, and what a day can take to stay within it (or how far over it is; the total balance if there's no such budget), and how many payments there are to check. Its plus opens the new record form.
- **Budgets**: each monthly budget with a meter, as many as the widget is tall enough for. Meters turn orange at 80% and red when over, as in the app.
- **New record**: one tap opens the new record form.

They read `GET /api/widget` with the app's session, so they work once the app is signed in, and say so when it isn't. Taps open the app at a page through `com.rudnam.wallet://open/<page>`. They update about every 30 minutes (Android's minimum for `updatePeriodMillis`), whenever the app goes to the background, and on sign-in and sign-out; offline, they keep the last figures.

## Payment notifications

`app/src/main/java/com/rudnam/wallet/notify` reads PayPay's and Google Wallet's payment notifications. The user turns it on in Settings › Payment notifications, which opens Android's notification access page; until then Android doesn't run it.

- `PaymentListener` is a `NotificationListenerService`. It sees every notification but keeps only those from the apps in its `APPS` list (Google Wallet and PayPay), so nothing else leaves the phone. To add an app, add its package there and a rule in `internal/notify/parse.go`.
- `Outbox` keeps them in SharedPreferences and sends them to `POST /api/notifications` with the app's session: at once, with the next notification, or when the app is next left (up to 7 days, 200 notifications). Sending one twice is harmless.
- The server reads the text (so a change in an app's wording needs only a server deploy) and records each payment once: a notification sent again is recognised by its app and time, a payment already recorded from an import or by hand is matched, not added, and so is PayPay's 支払いが完了しました when its 支払いを受け付けました came first.
- An APK installed outside the Play Store gets *restricted settings* on Android 13+: the notification access switch is greyed out until App info › ⋮ › Allow restricted settings.

## Transit IC cards

Settings › Import › IC card reads a Suica, PASMO, ICOCA or other transit IC card held to the back of the phone (`ICCardPlugin.java`, called from `web/src/native.ts`). Tapping a card to the phone while RudWallet isn't reading one also works: the manifest's `TECH_DISCOVERED` filter (`res/xml/nfc_tech_filter.xml`, NFC-F) makes Android offer RudWallet for the card, the plugin reads it from that intent and sends it as a `tapped` event, and the app opens the card screen (`#/card`, `web/src/features/card/CardPage.tsx`): the balance, read from the card itself so it shows at once, the latest entries, and a button to import what's new (in one tap for a card imported before, else through the import's preview). The card is FeliCa (NFC-F): the plugin polls for system code `0003`, reads the history service `090F` one block at a time without a key, and hands the blocks to the web app in hex. The server decodes them (`internal/wallet/importic.go`) and names stations and bus companies from the lists in `internal/wallet/icstations/`, so fixing either needs no new app build.

- The card keeps only its last ~20 entries, and no amounts: each is the change from the balance before it, so the oldest entry read only starts the history.
- The phone can't read its own Mobile Suica. A physical card only.
- NFC is optional in the manifest (`android.hardware.nfc`, `required="false"`), so phones without it still install the app; the section then says it needs NFC.
- Browser tests stand in for the card with `asAndroidApp(page, { icCard })`, and for a tap with `tapICCard(page, read)` (`e2e/tests/iccard.spec.js`).

## Icons

`web/public/icon.svg` (full bleed) and `favicon.svg` (rounded) are the source. `./android/icons.sh` redraws every PNG from them: the web icons, `store_icon.png`, and the launcher icons for Android 7 and older in `app/src/main/res/mipmap-*`. `./android/icons.sh` also draws `web/public/favicon.ico` and, on Android 8+, the adaptive icon's foreground: the wallet without its tile, as bitmaps in `mipmap-*/ic_launcher_foreground.png` (a vector can't draw the leather's grain or soft shadows). `mipmap-anydpi-v26/ic_launcher.xml` puts it on the tile's cream (`drawable/ic_launcher_gradient.xml`), with a one-colour vector (`drawable/ic_launcher_monochrome.xml`) for themed icons. The splash screen shows the same foreground on the cream (`values/styles.xml`).
