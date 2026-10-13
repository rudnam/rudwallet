# Android app

The Android app is the web app (`web/`) packaged with [Capacitor](https://capacitorjs.com): the React build ships inside the app, runs in a web view at `https://localhost`, and talks to the server's API. Native code adds what the web can't do: home screen widgets, and Sign in with Google through the phone's browser.

- Package `com.rudnam.wallet` (permanent once published), talking to https://wallet.rudnam.com (`web/.env.android`).
- Web app changes reach phones only with a new app build. Server changes reach them at once, so keep the API compatible with the last release (add fields; don't rename or remove them).
- Signing key: `android.keystore` (alias `android`), with its passwords in `signing-key.txt`. Both are git-ignored and exist only on this machine: **back them up together somewhere safe**. Without them you can't publish updates.
- Upload key SHA-256: `A1:58:90:F7:AA:46:CF:83:3D:D8:F5:3B:E2:B8:51:EF:82:43:D3:2E:CF:4E:30:49:19:3F:E9:06:D8:8A:04:6D`

## Build

You need a JDK 21, the Android SDK (`JAVA_HOME` and `ANDROID_HOME`, or the paths in `~/.bubblewrap/config.json` on this machine) and Node.

```sh
./android/build.sh            # android/wallet.aab (Play Store) and android/wallet.apk
./android/build.sh install    # and install the APK on the phone plugged in (USB debugging on)
```

It builds the web app for the app (`npm run android` in `web/`, which also copies it in with `cap sync`), then signs both files with `android.keystore`, reading the passwords from `signing-key.txt`.

For each Play Store release, raise `versionCode` (and `versionName`) in `app/build.gradle`, then upload `wallet.aab` in the Play Console. Versions 1 and 2 were the earlier Trusted Web Activity app, which this replaces under the same package and key, so it installs as an update.

A copy installed from the Play Store is signed by Google, so installing `wallet.apk` over it fails; uninstall it first.

While working on the app, `cd web && npm run android`, then open `android/` in Android Studio, or `cd android && ./gradlew installDebug`. A debug build needs no signing key.

## How the app signs in

The app's requests come from `https://localhost`, another origin, so the browser's cookie doesn't apply. The server allows that origin (CORS), and the app signs in with a bearer token instead: it sends `X-Wallet-Client` when signing in, keeps the token it gets back in Capacitor's preferences, and sends it with every request. App sessions last a year. `web/src/native.ts` and `internal/account/app.go` explain the details.

**Sign in with Google.** Google refuses sign-in inside a web view, so the app opens the phone's browser at `/api/auth/google`. When Google sends the browser back, the server sends it on to `com.rudnam.wallet://auth?code=…`, which Android hands to the app; the app trades the code, with a secret only it knows, for a session token. This needs `ANDROID_PACKAGE` on the server (it names the app to return to) and nothing new in Google's console: the redirect address is the same as for the web.

## Home screen widgets

Three widgets, written natively (Java, framework APIs only) in `app/src/main/java/com/rudnam/wallet/widget`:

- **This month**: spent this month, what's left of the budget for all expenses (or the total balance if there is none), and payments to review. Its plus opens the new record form.
- **Budgets**: each monthly budget with a meter, as many as the widget is tall enough for.
- **New record**: one tap opens the new record form.

They read `GET /api/widget` with the app's session, so they work once the app is signed in, and say so when it isn't. Taps open the app at a page through `com.rudnam.wallet://open/<page>`. They update about every 30 minutes (Android's minimum for `updatePeriodMillis`), whenever the app goes to the background, and on sign-in and sign-out; offline, they keep the last figures.

## Payment notifications

`app/src/main/java/com/rudnam/wallet/notify` reads Google Wallet's payment notifications. The user turns it on in Settings › Payment notifications, which opens Android's notification access page; until then Android doesn't run it.

- `PaymentListener` is a `NotificationListenerService`. It sees every notification but keeps only those from the apps in its `APPS` list (Google Wallet), so nothing else leaves the phone. To add an app, add its package there and a rule in `internal/notify/parse.go`.
- `Outbox` keeps them in SharedPreferences and sends them to `POST /api/notifications` with the app's session: at once, with the next notification, or when the app is next left (up to 7 days, 200 notifications). Sending one twice is harmless.
- The server reads the text (so a change in Google Wallet's wording needs only a server deploy) and records each payment once: a notification sent again is recognised by its app and time, and a payment already recorded from its email, an import or by hand is matched, not added.
- An APK installed outside the Play Store gets *restricted settings* on Android 13+: the notification access switch is greyed out until App info › ⋮ › Allow restricted settings.

## Icons

`web/public/icon.svg` (full bleed) and `favicon.svg` (rounded) are the source. `./android/icons.sh` redraws every PNG from them: the web icons, `store_icon.png`, and the launcher icons for Android 7 and older in `app/src/main/res/mipmap-*`. On Android 8+ the launcher icon is the adaptive one in `mipmap-anydpi-v26/ic_launcher.xml`: the wallet as a vector (`drawable/ic_launcher_foreground.xml`, plus a one-colour `ic_launcher_monochrome.xml` for themed icons) on the clay colour. The splash screen shows the same vector on the paper colour (`values/styles.xml`).
