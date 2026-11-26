#!/usr/bin/env bash
# Checks that every @HiltViewModel in the app's sources is in the built APK's Hilt map, so a
# page can't ship whose ViewModel Hilt can't make. Hilt finds a ViewModel by its class name
# (proguard-rules.pro keeps the names), so each one's name must be in the APK as a string.
#
# Why: from 2.1.43 to 2.1.47, KSP's incremental and cached output left some modules' Hilt
# modules out of the release build, and opening those pages crashed ("Cannot create an
# instance of class …"). The JVM tests don't build the app's Hilt graph, so nothing else
# catches it.
#
#   android/check-viewmodels.sh android/app/build/outputs/apk/preview/app-preview.apk
set -euo pipefail
apk=$(realpath "${1:?usage: $0 path/to/app.apk}")
[ -s "$apk" ] || { echo "::error::$apk not found"; exit 1; }
cd "$(dirname "$0")"
names=$(unzip -p "$apk" 'classes*.dex' | strings -n 8)
expected=0 missing=()
while IFS= read -r file; do
  pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -1)
  for cls in $(grep -A3 '^@HiltViewModel' "$file" | sed -n 's/^\(open \|internal \)*class \([A-Za-z0-9_]*\).*/\2/p'); do
    expected=$((expected + 1))
    grep -qF "$pkg.$cls" <<<"$names" || missing+=("$pkg.$cls")
  done
done < <(grep -rl --include='*.kt' '^@HiltViewModel' app/src/main feature/*/src/main)
[ "$expected" -gt 0 ] || { echo "::error::found no @HiltViewModel classes to check"; exit 1; }
if [ ${#missing[@]} -gt 0 ]; then
  printf '::error::Not in the APK'"'"'s Hilt map, so its page would crash: %s\n' "${missing[@]}"
  echo "A KSP output was probably stale; rebuild without the cache (./gradlew clean, --no-build-cache)."
  exit 1
fi
echo "All $expected Hilt ViewModels are in $apk."
