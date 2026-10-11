#!/usr/bin/env bash
# Redraws the PNG icons (web, Play Store, Android launcher and splash) from
# web/public/icon.svg and favicon.svg. Run it after changing either SVG, and
# after `bubblewrap update`, which overwrites the Android ones.
set -euo pipefail
cd "$(dirname "$0")/.."
PW_VERSION=$(sed -n 's/.*"@playwright\/test": *"\([0-9.]*\)".*/\1/p' e2e/package.json)
docker run --rm -v "$PWD:/repo" -w /repo/e2e -u "$(id -u):$(id -g)" -e HOME=/tmp \
  "mcr.microsoft.com/playwright:v${PW_VERSION}-noble" \
  sh -c 'ls node_modules/playwright >/dev/null 2>&1 || npm ci --no-audit --no-fund --loglevel=error; cp ../android/icons/render.mjs ./.render-icons.mjs && node ./.render-icons.mjs /repo; rm -f ./.render-icons.mjs'
