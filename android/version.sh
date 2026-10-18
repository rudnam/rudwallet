#!/usr/bin/env bash
# Prints the versionCode for the next release: one above the APK that
# wallet.rudnam.com offers (GET /api/downloads), and never below the floor in
# app/build.gradle. The app offers a build with a higher versionCode as an
# update (web/src/updates.ts), so every release must raise it; this does.
#
#   ./android/version.sh     -> e.g. 7
#
# build.sh uses it unless WALLET_VERSION_CODE is already set. Fails if the
# site can't be reached rather than guessing.
set -euo pipefail
cd "$(dirname "$0")"

site=${WALLET_SITE:-https://wallet.rudnam.com}
floor=$(sed -n "s/.*WALLET_VERSION_CODE') ?: '\([0-9]*\)'.*/\1/p" app/build.gradle)
[[ $floor =~ ^[0-9]+$ ]] || { echo "no versionCode floor found in app/build.gradle" >&2; exit 1; }

downloads=$(curl -fsS --retry 3 "$site/api/downloads") || { echo "couldn't ask $site which version it offers" >&2; exit 1; }
published=$(grep -o '"version":[0-9]*' <<<"$downloads" | cut -d: -f2 || true)

next=$(( ${published:-0} + 1 ))
(( next > floor )) || next=$floor
echo "$next"
