#!/usr/bin/env bash
# Builds the Android app for release: the web app (bundled into the app),
# then a signed app bundle for the Play Store and an APK to install directly.
#
#   ./android/build.sh            -> android/wallet.aab and android/wallet.apk
#   ./android/build.sh install    also installs the APK on the phone plugged in (adb)
#
# Needs a JDK and the Android SDK (JAVA_HOME and ANDROID_HOME, or the paths in
# ~/.bubblewrap/config.json), Node, and android.keystore with its passwords in
# signing-key.txt, next to this script.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f ~/.bubblewrap/config.json ]]; then
  : "${JAVA_HOME:=$(sed -n 's/.*"jdkPath": *"\([^"]*\)".*/\1/p' ~/.bubblewrap/config.json)}"
  : "${ANDROID_HOME:=$(sed -n 's/.*"androidSdkPath": *"\([^"]*\)".*/\1/p' ~/.bubblewrap/config.json)}"
fi
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}" "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
export JAVA_HOME ANDROID_HOME

[[ -f android.keystore && -f signing-key.txt ]] || { echo "android.keystore and signing-key.txt must be in android/" >&2; exit 1; }
WALLET_KEYSTORE_PASSWORD=$(sed -n 's/^Keystore password: //p' signing-key.txt)
WALLET_KEY_PASSWORD=$(sed -n 's/^Key password: //p' signing-key.txt)
export WALLET_KEYSTORE_PASSWORD WALLET_KEY_PASSWORD

# The web app, built for the app (web/.env.android) and copied in.
(cd ../web && { [[ -d node_modules ]] || npm ci --no-audit --no-fund; } && npm run android)

./gradlew --quiet bundleRelease assembleRelease
cp app/build/outputs/bundle/release/app-release.aab wallet.aab
cp app/build/outputs/apk/release/app-release.apk wallet.apk
# What the server offers on the introduction page (ANDROID_APK in docker-compose.yml).
mkdir -p ../downloads
if [[ ! -w ../downloads ]]; then
  echo "downloads/ isn't writable (Docker creates it as root if it's missing when the server starts)." >&2
  echo "Fix it with: sudo chown $(id -un): $(cd ../downloads && pwd)" >&2
  exit 1
fi
cp wallet.apk ../downloads/wallet.apk
echo "Built android/wallet.aab (Play Store) and android/wallet.apk ($(sed -n 's/.*versionName "\(.*\)"/\1/p' app/build.gradle))"

if [[ ${1:-} == install ]]; then
  "$ANDROID_HOME/platform-tools/adb" install -r wallet.apk
fi
