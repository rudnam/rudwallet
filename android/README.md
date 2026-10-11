# Android app

The web app is already installable on Android: open the site in Chrome and choose **Add to Home screen**. That needs no extra code, and is enough for personal use.

To ship it on the **Play Store**, wrap the site in a **Trusted Web Activity (TWA)**: a small Android app that opens your site full screen in Chrome, without browser UI. Because the site is the app:

- every web deploy updates the Android app too, with no store review;
- sign-in, cookies and the API work exactly as on the web; nothing in the backend changes.

The cost: it needs a network connection, and it can't use native APIs that the web can't. If you later need those (offline use, background work, deep device access), the next step is Capacitor or a native Kotlin app that calls the same `/api`.

## This app

- Package `com.rudnam.wallet` (permanent once published), opening https://wallet.rudnam.com.
- Settings live in `twa-manifest.json`; the Gradle project and `app/` are generated from it.
- Signing key: `android.keystore` (alias `android`), with its passwords in `signing-key.txt`. Both are git-ignored and exist only on this machine: **back them up together somewhere safe**. Without them you can't publish updates.
- Upload key SHA-256: `A1:58:90:F7:AA:46:CF:83:3D:D8:F5:3B:E2:B8:51:EF:82:43:D3:2E:CF:4E:30:49:19:3F:E9:06:D8:8A:04:6D`

## Build

You need a JDK (17+) and the Android SDK; `~/.bubblewrap/config.json` points Bubblewrap at both. (Bubblewrap still looks for the old `tools/` folder, so the SDK has `tools` symlinked to `cmdline-tools/latest`.)

```sh
cd android
export BUBBLEWRAP_KEYSTORE_PASSWORD=... BUBBLEWRAP_KEY_PASSWORD=...   # from signing-key.txt
npx @bubblewrap/cli build      # makes app-release-signed.apk and app-release-bundle.aab
```

To release an update to the store, raise `appVersionCode` (and `appVersion`) in `twa-manifest.json`, run `npx @bubblewrap/cli update --skipVersionUpgrade`, then build. Changes to the site itself need no new build.

## Prove the site and the app belong together

Without this step, the app opens with a browser address bar.

1. Take the upload key's fingerprint above (or `keytool -list -v -keystore android.keystore`, the `SHA256:` line). If Google Play signs your app (the default), also add the fingerprint from **Play Console → Setup → App signing**.
2. On the server, in `.env`:
   ```sh
   ANDROID_PACKAGE=com.rudnam.wallet
   ANDROID_CERT_FINGERPRINTS=AA:BB:...,CC:DD:...   # comma-separated
   ```
3. `docker compose up -d`, then check that `https://wallet.rudnam.com/.well-known/assetlinks.json` lists them.

## Test and publish

- Install on a phone with USB debugging on: `npx @bubblewrap/cli install`.
- Upload `app-release-bundle.aab` in the Play Console.
- After changing the web manifest (name, colors, icons), mirror it in `twa-manifest.json`, run `npx @bubblewrap/cli update --skipVersionUpgrade` and build again.

## Icons

`web/public/icon.svg` (full bleed) and `favicon.svg` (rounded) are the source. `./android/icons.sh` redraws every PNG from them: the web icons, `store_icon.png`, and the launcher and splash images in `app/src/main/res`. On Android 8+ the launcher icon is the adaptive one in `mipmap-anydpi-v26/ic_launcher.xml`: the wallet as a vector (`drawable/ic_launcher_foreground.xml`, plus a one-colour `ic_launcher_monochrome.xml` for themed icons) on the clay colour.

`bubblewrap update` replaces all of these with its own versions built from the live site's PNGs. After an update, restore them with `git checkout -- app/src/main/res` (and delete any `ic_maskable.png` it adds), or run `./android/icons.sh`.
