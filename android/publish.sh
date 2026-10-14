#!/usr/bin/env bash
# Publishes android/wallet.apk (from ./android/build.sh) to production. It
# uploads the APK to the deploy bucket and copies it onto the server, where
# the introduction page offers it right away (no restart or deploy needed).
# Later deploys copy it again, so it survives a rebuilt server.
#
#   ./android/build.sh && ./android/publish.sh
#
# Needs the AWS CLI signed in to the "rudnam ec2" project:
#   aws login --profile rudnam-ec2
# How downloads work on the server: rudnam/rudnam-infra, README "Downloads".
set -euo pipefail
cd "$(dirname "$0")"

export AWS_PROFILE=${AWS_PROFILE:-rudnam-ec2} AWS_REGION=${AWS_REGION:-ap-southeast-2}
bucket=rudnam-deploy-394567896817   # rudnam-infra: terraform output deploy_bucket
site=https://wallet.rudnam.com

[[ -f wallet.apk ]] || { echo "android/wallet.apk not found; run ./android/build.sh first" >&2; exit 1; }

aws s3 cp --only-show-errors --content-type application/vnd.android.package-archive \
  wallet.apk "s3://$bucket/downloads/wallet-app/wallet.apk"

id=$(aws ssm send-command \
  --document-name AWS-RunShellScript \
  --targets Key=tag:Role,Values=apps \
  --parameters 'commands=["/usr/local/bin/rudnam-downloads wallet-app"]' \
  --comment "publish wallet.apk" \
  --query Command.CommandId --output text)
for _ in $(seq 30); do
  status=$(aws ssm list-command-invocations --command-id "$id" \
    --query 'CommandInvocations[0].Status' --output text)
  case $status in Pending|InProgress|Delayed|None) sleep 3 ;; *) break ;; esac
done
[ "$status" = Success ] || { echo "copying onto the server: $status" >&2; exit 1; }

echo "Published: $(curl -fsS "$site/api/downloads")"
echo "Download:  $site/download/wallet.apk"
