#!/usr/bin/env bash
# Uploads an app bundle to a Play Console track (internal by default) with the
# Google Play Developer API. CI runs it (android-play.yml) after each Android
# release; it needs no key: GitHub's OIDC token is traded for a Google one
# (workload identity federation) as the play-publisher service account, which
# the Play Console lets release to testing tracks. android/README.md, "Play Store".
#
#   android/play-upload.sh rudwallet-2.1.35.aab 2.1.35
#
# Needs, from the workflow: GCP_WORKLOAD_IDENTITY_PROVIDER, PLAY_SERVICE_ACCOUNT,
# and GitHub's ACTIONS_ID_TOKEN_REQUEST_URL/TOKEN (permissions: id-token: write).
# Optional: PLAY_TRACK (internal), PLAY_RELEASE_STATUS (completed; "draft"
# leaves it for you to roll out in the Play Console).
set -euo pipefail

aab=${1:?usage: play-upload.sh <bundle.aab> <version name>}
name=${2:?usage: play-upload.sh <bundle.aab> <version name>}
package=com.rudnam.wallet
track=${PLAY_TRACK:-internal}
status=${PLAY_RELEASE_STATUS:-completed}
: "${GCP_WORKLOAD_IDENTITY_PROVIDER:?not set}" "${PLAY_SERVICE_ACCOUNT:?not set}"
: "${ACTIONS_ID_TOKEN_REQUEST_URL:?no OIDC token: the job needs permissions: id-token: write}"
[[ -f $aab ]] || { echo "$aab not found" >&2; exit 1; }

# Fails on an HTTP error, printing Google's message (never a token).
call() {
  local out code
  out=$(mktemp)
  code=$(curl -sS -o "$out" -w '%{http_code}' "$@")
  if [[ $code != 2* ]]; then
    echo "::error::HTTP $code from ${*: -1}: $(jq -r '.error.message? // .error_description? // .' "$out" 2>/dev/null | head -c 2000)" >&2
    rm -f "$out"; return 1
  fi
  cat "$out"; rm -f "$out"
}

audience=//iam.googleapis.com/$GCP_WORKLOAD_IDENTITY_PROVIDER
oidc=$(call -H "Authorization: Bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=$audience" | jq -r .value)
echo "::add-mask::$oidc"
federated=$(call -X POST https://sts.googleapis.com/v1/token \
  --data-urlencode grant_type=urn:ietf:params:oauth:grant-type:token-exchange \
  --data-urlencode "audience=$audience" \
  --data-urlencode scope=https://www.googleapis.com/auth/cloud-platform \
  --data-urlencode requested_token_type=urn:ietf:params:oauth:token-type:access_token \
  --data-urlencode subject_token_type=urn:ietf:params:oauth:token-type:jwt \
  --data-urlencode "subject_token=$oidc" | jq -r .access_token)
echo "::add-mask::$federated"
token=$(call -X POST -H "Authorization: Bearer $federated" -H 'Content-Type: application/json' \
  -d '{"scope":["https://www.googleapis.com/auth/androidpublisher"],"lifetime":"900s"}' \
  "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/$PLAY_SERVICE_ACCOUNT:generateAccessToken" |
  jq -r .accessToken)
echo "::add-mask::$token"
auth=(-H "Authorization: Bearer $token")

api=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package
edit=$(call -X POST "${auth[@]}" "$api/edits" | jq -r .id)
echo "Edit $edit: uploading $aab"
code=$(call -X POST "${auth[@]}" -H 'Content-Type: application/octet-stream' --data-binary "@$aab" \
  "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$package/edits/$edit/bundles?uploadType=media" |
  jq -r .versionCode)
echo "Uploaded versionCode $code; releasing $name on $track ($status)"
jq -n --arg t "$track" --arg n "$name" --arg c "$code" --arg s "$status" \
  '{track: $t, releases: [{name: $n, versionCodes: [$c], status: $s}]}' |
  call -X PUT "${auth[@]}" -H 'Content-Type: application/json' -d @- "$api/edits/$edit/tracks/$track" >/dev/null
call -X POST "${auth[@]}" "$api/edits/$edit:commit" >/dev/null
echo "Play Console: $name (versionCode $code) on $track, $status"
