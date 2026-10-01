#!/bin/bash
# Set zero up to update the head unit over USB with no network, from the latest builds.
#
#   curl -sf https://launcher.hq.ripostelabs.xyz/car-update/setup.sh | bash
#
# Run it at home (it needs launcher.hq), as your normal user; it asks for sudo once for the
# udev rule and the service. Re-run it any time to refresh: files that did not change are not
# downloaded again. After that, plugging the head unit into zero runs the update by itself.
set -euo pipefail

BASE=https://launcher.hq.ripostelabs.xyz
DIR="$HOME/rav4-update"
BIN=/usr/local/bin/rav4-usb-update
UNIT=/etc/systemd/system/rav4-usb-update.service
RULE=/etc/udev/rules.d/99-rav4-usb-update.rules
ADB_IFACE=":ff4201:"   # USB class ff, subclass 42, protocol 01: an adb interface

die() { echo "setup: $*" >&2; exit 1; }
command -v adb >/dev/null || die "adb is missing: sudo pacman -S android-tools"
command -v curl >/dev/null || die "curl is missing"
mkdir -p "$DIR/suite"

# 1. Launcher: the newest one launcher.hq links to, checked against its .sha256.
name=$(curl -sf "$BASE/" | grep -oE 'carlauncher-[^"]*-vc[0-9]+\.apk' | sort -u \
       | sed 's/.*-vc\([0-9]*\)\.apk/\1 &/' | sort -n | tail -1 | cut -d' ' -f2)
[ -n "$name" ] || die "launcher.hq lists no launcher"
sum=$(curl -sf "$BASE/$name.sha256" | cut -d' ' -f1)

if [ ! -f "$DIR/$name" ] || [ "$(sha256sum "$DIR/$name" | cut -d' ' -f1)" != "$sum" ]; then
    curl -sfo "$DIR/$name.part" "$BASE/$name"
    [ "$(sha256sum "$DIR/$name.part" | cut -d' ' -f1)" = "$sum" ] || die "$name failed its checksum"
    mv "$DIR/$name.part" "$DIR/$name"
    echo "launcher $name downloaded"
else
    echo "launcher $name already here"
fi
find "$DIR" -maxdepth 1 -name 'carlauncher-*.apk' ! -name "$name" -delete   # keep only the newest

# 2. Suite: Caddy's JSON listing gives name, size and mtime; a file whose three match is kept.
listing=$(curl -sf -H 'Accept: application/json' "$BASE/suite/" \
          | grep -oE '"name":"[^"]+\.apk","size":[0-9]+,"url":"[^"]*","mod_time":"[^"]+"' \
          | sed -E 's/"name":"([^"]+)","size":([0-9]+),"url":"[^"]*","mod_time":"([^"]+)"/\1 \2 \3/')
[ -n "$listing" ] || die "launcher.hq lists no suite apps"
touch "$DIR/suite.list"

fetched=0
while read -r apk size mtime; do
    if [ -f "$DIR/suite/$apk" ] && grep -qxF "$apk $size $mtime" "$DIR/suite.list"; then
        continue
    fi
    curl -sfo "$DIR/suite/$apk.part" "$BASE/suite/$apk"
    [ "$(stat -c %s "$DIR/suite/$apk.part")" = "$size" ] || die "$apk arrived truncated"
    mv "$DIR/suite/$apk.part" "$DIR/suite/$apk"
    fetched=$((fetched + 1))
done <<<"$listing"
echo "$listing" > "$DIR/suite.list"

# An app launcher.hq no longer serves leaves this folder too.
for f in "$DIR"/suite/*.apk; do
    grep -q "^$(basename "$f") " "$DIR/suite.list" || rm -f "$f"
done
echo "suite: $(wc -l < "$DIR/suite.list") apps, $fetched downloaded"

# The timer runs this file from here.
# Rename, never overwrite: bash reads a running script lazily, and an in-place write breaks it.
curl -sfo "$DIR/setup.sh.part" "$BASE/car-update/setup.sh" && chmod 755 "$DIR/setup.sh.part" \
    && mv "$DIR/setup.sh.part" "$DIR/setup.sh"

# 3. The runner, the service that starts it, and the rule that starts the service on plug-in.
# The service runs it from here, so a refresh never needs sudo; only the rule and unit do.
curl -sfo "$DIR/rav4-usb-update.part" "$BASE/car-update/rav4-usb-update"
chmod 755 "$DIR/rav4-usb-update.part"
mv "$DIR/rav4-usb-update.part" "$DIR/rav4-usb-update"
curl -sfo "$DIR/road-noise-pull.part" "$BASE/car-update/road-noise-pull"
chmod 755 "$DIR/road-noise-pull.part"
mv "$DIR/road-noise-pull.part" "$DIR/road-noise-pull"
unit_text="[Unit]
Description=Update the RAV4 head unit from $DIR when it is plugged in

[Service]
Type=oneshot
User=$USER
Environment=HOME=$HOME
ExecStart=$DIR/rav4-usb-update"

rule_text='# RAV4 head unit on USB: any adb interface starts the update (the runner checks it is the car).
ACTION=="add", SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ENV{ID_USB_INTERFACES}=="*'"$ADB_IFACE"'*", TAG+="systemd", ENV{SYSTEMD_WANTS}+="rav4-usb-update.service"'

# The hourly refresh timer passes this: it may not prompt, so system files wait for a human run.
if [ "${RAV4_REFRESH_ONLY:-0}" = 1 ]; then
    echo "refreshed: launcher ${name#carlauncher-}, $(wc -l < "$DIR/suite.list") suite apps"
    exit 0
fi

# sudo only when something on the system side actually changes, so a refresh never prompts.
if [ -e "$BIN" ] || [ "$(cat "$UNIT" 2>/dev/null)" != "$unit_text" ] || [ "$(cat "$RULE" 2>/dev/null)" != "$rule_text" ]; then
    sudo rm -f "$BIN"   # an older setup installed it system-wide
    printf '%s\n' "$unit_text" | sudo tee "$UNIT" >/dev/null
    printf '%s\n' "$rule_text" | sudo tee "$RULE" >/dev/null
    sudo systemctl daemon-reload
    sudo udevadm control --reload
    echo "system files updated"
fi

# Hourly refresh as the user (no sudo): new builds arrive whenever launcher.hq is reachable.
TIMER_DIR="$HOME/.config/systemd/user"
mkdir -p "$TIMER_DIR"
printf '%s\n' "[Unit]" "Description=Refresh $DIR from launcher.hq" "" "[Service]" "Type=oneshot" \
    "Environment=RAV4_REFRESH_ONLY=1" "ExecStart=/bin/bash $DIR/setup.sh" > "$TIMER_DIR/rav4-update-refresh.service"
printf '%s\n' "[Unit]" "Description=Refresh $DIR hourly" "" "[Timer]" "OnBootSec=5min" "OnUnitActiveSec=1h" \
    "Persistent=true" "" "[Install]" "WantedBy=timers.target" > "$TIMER_DIR/rav4-update-refresh.timer"
systemctl --user daemon-reload
systemctl --user enable --now rav4-update-refresh.timer >/dev/null 2>&1 || echo "refresh timer not enabled (no user session?)"

echo
echo "ready: launcher ${name#carlauncher-}, $(wc -l < "$DIR/suite.list") suite apps in $DIR"
echo "at the car: plug zero into the head unit's USB and wait for the note on its screen."
echo "the first time, tap Allow on the USB debugging prompt. Log: $DIR/last.log"
