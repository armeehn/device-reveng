#!/usr/bin/env bash
# GT6-EAU debloat — REVERSIBLE (pm uninstall --user 0, keeps system image).
# Restore any: adb shell cmd package install-existing <pkg>
# Usage: bash debloat.sh [ip:port]   (defaults to first adb 'device')
set -uo pipefail
DEV="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -z "$DEV" ] && { echo "no adb device"; exit 1; }
echo ">> target: $DEV"

# ---- TIER 1: de-spyware / telemetry (recommended) ----
TIER1=(
  com.szchoiceway.logcatupload
  com.choiceway.logcapture
  com.szchoiceway.update
  com.es.file.explorer.manager
  com.syu.market
  com.google.android.partnersetup
)

# ---- TIER 2: bloat (optional — uncomment to include) ----
TIER2=(
  # com.mmbox.xbrowser
  # com.choiceway.weather
  # com.szchoiceway.photoreader
  # com.szchoiceway.videoplayer
  # com.szchoiceway.musicplayer
  # com.szchoiceway.instructions
  # com.example.android.systemupdatersample
  # com.example.android.locationattribution
)

remove() {
  local pkg="$1"
  # verify it exists first
  if ! adb -s "$DEV" shell pm path "$pkg" >/dev/null 2>&1; then
    printf "  %-45s SKIP (not installed)\n" "$pkg"; return
  fi
  out=$(adb -s "$DEV" shell "pm uninstall -k --user 0 $pkg" 2>&1 | tr -d '\r')
  printf "  %-45s %s\n" "$pkg" "$out"
}

echo "=== TIER 1: de-spyware ==="
for p in "${TIER1[@]}"; do remove "$p"; done
echo "=== TIER 2: bloat (only uncommented) ==="
for p in "${TIER2[@]}"; do [[ "$p" =~ ^# ]] && continue; remove "$p"; done

echo; echo ">> Done. Reboot the unit and verify car functions (reverse, SWC, radio, BT, AA)."
echo ">> Restore anything with: adb -s $DEV shell cmd package install-existing <pkg>"
