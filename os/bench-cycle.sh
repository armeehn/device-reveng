#!/usr/bin/env bash
# One bench iteration of Riposte OS 0.2: a release launcher from launcher.hq, the image set
# built here, shipped to the laptop at the bench, flashed over fastbootd without a wipe.
#
#     launcher.hq ──apk──▶ build.sh --profile gsi --bench ──▶ share/.../0.2-bench
#         ──rsync──▶ laptop:~/rav4-headunit/os/0.2-bench ──▶ adb reboot fastboot ──▶ fastboot-flash-set.sh
#         ──boot──▶ bench-verify.sh (every logical partition hashed against SHA256SUMS over adb)
#
# Usage: bench-cycle.sh <vcNNN> [--wipe]        runs as root on x (build.sh loop-mounts)
#   vcNNN   a launcher versionCode listed at launcher.hq (the release job publishes one per
#           push to main); its .sha256 is checked before the build.
#   --wipe  erase userdata on the flash (needed when crossing 0.1 <-> 0.2, see BENCH.md).
# Env: BENCH_HOST (default sasha@100.107.107.95), BENCH_SERIAL (adb serial, default da40e9ac),
#      SHARE_HOST (how the laptop reaches this share, default sasha@x.hq.ripostelabs.xyz),
#      SHARE (default /z1-pool/share/carlauncher/os), APPS (default $SHARE/apps-bench),
#      LAUNCHER_URL (default https://launcher.hq.ripostelabs.xyz), AAPT2 (build.sh finds one).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

VC=${1:?usage: bench-cycle.sh <vcNNN> [--wipe]}
WIPE=${2:-}
BENCH_HOST=${BENCH_HOST:-sasha@100.107.107.95}
BENCH_SERIAL=${BENCH_SERIAL:-da40e9ac}
SHARE_HOST=${SHARE_HOST:-sasha@x.hq.ripostelabs.xyz}
SHARE=${SHARE:-/z1-pool/share/carlauncher/os}
APPS=${APPS:-$SHARE/apps-bench}
LAUNCHER_URL=${LAUNCHER_URL:-https://launcher.hq.ripostelabs.xyz}
OUT=$SHARE/0.2-bench
GSI=$SHARE/gsi/system-td-arm64-ab-vanilla-ci20240226.img.xz   # the last Android 14 GSI that boots on kernel 4.14
readonly FASTBOOTD_USB_ID=18d1:4ee0

log() { echo "[bench] $*"; }
die() { echo "[bench] ERROR: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root (build.sh loop-mounts)"
[ -d "$APPS/suite" ] || die "$APPS/suite missing: the suite APKs and bootanimation.zip live there"
[ "$(cat "$HERE/../.git/HEAD" 2>/dev/null)" = "ref: refs/heads/main" ] || die "checkout is not on main"

# 1. the release launcher, checksum verified against launcher.hq
curl -sf -o "$APPS/carlauncher.apk" "$LAUNCHER_URL/carlauncher-0.7-$VC.apk" || die "no $VC at $LAUNCHER_URL"
want=$(curl -sf "$LAUNCHER_URL/carlauncher-0.7-$VC.apk.sha256" | awk '{print $1}')
have=$(sha256sum "$APPS/carlauncher.apk" | awk '{print $1}')
[ "$want" = "$have" ] || die "launcher $VC checksum mismatch"
log "launcher $VC ok"

# 2. build. The images are replaced; RESULT.md, the record ACCEPTANCE.md asks for next to
# them, stays (a rebuild once deleted it).
mkdir -p "$OUT"
find "$OUT" -mindepth 1 -maxdepth 1 ! -name RESULT.md -exec rm -rf {} +
"$HERE/build.sh" --base "$SHARE/base-ota" --boot "$SHARE/base-ota/boot-magisk.img" --apps "$APPS" \
  --out "$OUT" --profile gsi --bench --system "$GSI"
grep -E "^version=|^launcher=|^bench=" "$OUT/MANIFEST"
chown -R sasha:smbshare "$OUT" 2>/dev/null || true

# 3. ship to the bench laptop (the images, and the two scripts the laptop runs), then flash
scp -q -o BatchMode=yes "$HERE/fastboot-flash-set.sh" "$HERE/bench-verify.sh" "$BENCH_HOST:rav4-headunit/os/"
FLASH_ARGS="~/rav4-headunit/os/0.2-bench"
[ "$WIPE" = --wipe ] && FLASH_ARGS="$FLASH_ARGS wipe"
# The remote shell is bash: pipefail so a flash that fails behind the tail still fails here.
# A failed write leaves the unit in fastbootd with a half-written slot; never reboot it, rerun.
ssh -o BatchMode=yes "$BENCH_HOST" "
  set -eo pipefail
  rsync -a --delete $SHARE_HOST:$OUT/ ~/rav4-headunit/os/0.2-bench/
  cd ~/rav4-headunit/os/0.2-bench && sha256sum -c --quiet SHA256SUMS && echo '[bench] on the laptop: SUMS-OK'
  adb -s $BENCH_SERIAL reboot fastboot
  for i in \$(seq 1 24); do sleep 5; lsusb | grep -q '$FASTBOOTD_USB_ID' && break; done
  lsusb | grep -q '$FASTBOOTD_USB_ID' || { echo '[bench] ERROR: fastbootd never enumerated'; exit 1; }
  bash ~/rav4-headunit/os/fastboot-flash-set.sh $FLASH_ARGS 2>&1 | grep -v '^Sending\|^Writing' | tail -12
" || die "flash failed: the unit is still in fastbootd, rerun fastboot-flash-set.sh there (BENCH.md)"
log "flashed $VC; the unit is booting"

# 4. Prove the bytes: fastboot has no payload checksum and a marginal link once wrote bad
# blocks without an error. bench-verify.sh hashes each logical partition against SHA256SUMS
# over adb root; the bench build's adbd is up from init, before the framework.
ssh -o BatchMode=yes "$BENCH_HOST" "
  set -eo pipefail
  for i in \$(seq 1 60); do adb -s $BENCH_SERIAL get-state >/dev/null 2>&1 && break; sleep 5; done
  sleep 15
  UNIT=$BENCH_SERIAL bash ~/rav4-headunit/os/bench-verify.sh ~/rav4-headunit/os/0.2-bench
" || die "verify failed: the flash did not write what was sent, reflash"
log "verified $VC on the unit"
