#!/usr/bin/env bash
# Publishes a Lumen release where TVs in China can reach it: lumen.siabuilt.com (Cloudflare Pages).
# GitHub and jsDelivr are unreliable from China, so the TV reads version.txt and downloads the APK
# here first (update/Updates.kt).
#
#   tools/publish-update.sh            # version.txt and the guard build from this checkout
#
# Run after building (./gradlew assembleGuardRelease) and after the GitHub release of the same
# version. Needs wrangler logged in to the Cloudflare account that holds siabuilt.com.
set -euo pipefail
cd "$(dirname "$0")/.."
ACCOUNT=fbf20d660397a192c21f387fd02bd7d8
VERSION=$(tr -d '[:space:]' < version.txt)
APK=app/build/outputs/apk/guard/release/app-guard-release.apk
[ -f "$APK" ] || { echo "Build first: ./gradlew assembleGuardRelease" >&2; exit 1; }

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cp version.txt "$OUT/version.txt"
cp "$APK" "$OUT/lumen-guard.apk"
cat > "$OUT/index.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>Lumen</title>
<meta http-equiv="refresh" content="0; url=https://github.com/Charleyxx1209/lumen-tv">
<a href="https://github.com/Charleyxx1209/lumen-tv">Lumen</a>
HTML
# The version file must never be cached for long: TVs check it every few hours.
cat > "$OUT/_headers" <<'H'
/version.txt
  Cache-Control: no-cache
/lumen-guard.apk
  Cache-Control: no-cache
H
CLOUDFLARE_ACCOUNT_ID=$ACCOUNT wrangler pages deploy "$OUT" --project-name lumen --branch main --commit-dirty=true
echo "Published Lumen $VERSION to https://lumen.siabuilt.com"
