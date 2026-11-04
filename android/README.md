# Android app

The Android app is the web app (`web/`) packaged with [Capacitor](https://capacitorjs.com): the React build ships inside the app, runs in a web view at `https://localhost`, and talks to the server's API. Native code adds what the web can't do: home screen widgets, Sign in with Google through the phone's browser, and reading transit IC cards over NFC. The app is also moving to native screens, one page at a time (see Native screens).

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

The version is chosen for you: `version.sh` gives one above the `versionCode` the site offers (`app/build.gradle` holds only the floor), and the name is `build.gradle`'s `versionName` plus it, e.g. `2.1.5`. Set `WALLET_VERSION_CODE` or `WALLET_VERSION_NAME` to choose your own. For a Play Store release, upload `wallet.aab` in the Play Console. Versions 1 and 2 were the earlier Trusted Web Activity app, which this replaces under the same package and key, so it installs as an update.

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

## Starting up, and working offline

The splash screen (`styles.xml`, Android 12's, back-ported) stays up until the web app has drawn Home or the sign-in page: `SplashPlugin` hears it from `appDrawn()` in `web/src/native.ts`, and `MainActivity` gives it 3 seconds at most. The web app keeps who is signed in and the answers Home needs in the web view's storage (`web/src/api.ts`), so it usually opens on them at once and asks the server again meanwhile.

Haptics mark moments, not every touch (`HapticsPlugin`, called through `web/src/native.ts`): a number pad key feels like the phone's keyboard (`KEYBOARD_TAP`); picking a category, an account or expense/income is a light tick (`SEGMENT_TICK`, `CLOCK_TICK` before Android 14); a swipe passing the point where letting go acts is a detent (`GESTURE_THRESHOLD_ACTIVATE`); something saved or confirmed is `CONFIRM`; something refused, such as saving without an amount, is `REJECT` (both from Android 11, with the nearest older feedback before). Opening pages and rows doesn't buzz. All follow the system's touch feedback setting and need no permission; taps closer than 35 ms apart are merged, so fast typing doesn't run into a buzz. App builds from before simply don't tap.

A record added without a connection is kept on the phone (`web/src/outbox.ts`), counted in the balances and lists with a **Not sent yet** badge, and sent when the server answers again: on the next start, when the app comes back into view, or when the phone is back online. It carries a `client_id`, so a resend after a lost answer doesn't add it twice. Until it's sent it can be edited or deleted on the phone; other edits and deletes need a connection. One the server refuses (say, its account was deleted meanwhile) stays with **Couldn't send** until it's changed or deleted.

## Native screens

The app is moving from the web view to native screens (Kotlin and Jetpack Compose, `app/src/main/java/com/rudnam/wallet/screens`), one page at a time, so each release can ship a page without waiting for the rest. So far there is one: **Wallet** (`#/home`), off by default; Settings › Native Wallet turns it on.

- **Over the web view.** `NativeOverlay` draws the native screen over the web view, which keeps running underneath. The web app still decides where the user is: on Wallet it calls `NativeScreens.show()`, and anywhere else `hide()` (`web/src/native.ts`, `App.tsx`). So Android's back gesture, links from the widgets and every other page work as before, and going back to a web page is instant.
- **Taps on what isn't native yet** go back to the web app as an `open` event (`NativeScreensPlugin`): a page and its parameters (a tab, an account's card, search), `add` for the plus, or a record's JSON, which the web app opens in its form. The native screen hides while the form is open and reloads when it comes back.
- **The same session and data.** It reads the web app's session and server from Capacitor's preferences, as the widgets do, and asks the same API (`WalletApi`: `/api/wallet`, `/api/transactions`, `/api/budgets`). It keeps the last answers for the month, so it opens on them at once and shows them offline.
- **The same look.** `Theme.kt` holds the web's design tokens (the top of `web/src/styles.css`, light and dark), `Icons.kt` its line icons, and `res/font` its Inter, converted from `web/src/fonts` by `fonts.py`. Change them together. It follows the web app's language and Settings › Appearance, which `show()` passes on.
- **Where the web Wallet stays:** in a demo, while an email waits to be confirmed or an update is offered, and while the first-run checklist shows (banners the native Wallet doesn't have yet). Not native yet either: rearranging the cards by holding one, and records added offline (the outbox lives in the web view) until they're sent.
- **Testing.** `./gradlew testDebugUnitTest -Proborazzi.test.record=true` (in `android/`) runs `app/src/test` on the JVM with Robolectric and draws the screens at phone size, light, dark and Japanese, into `app/build/outputs/roborazzi/`. CI runs it in the `android` job and keeps the pictures as the `native-screenshots` artifact. `./verify.sh` doesn't run these; its browser tests cover the web app's side (`e2e/tests/native-home.spec.js`).

To make another page native: add its screen in `screens/`, show it from `App.tsx` for its route like Wallet, and send anything it can't do yet to the web app with `open`.

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
