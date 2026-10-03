#!/usr/bin/env bash
# riposte-uplink.sh on the host, with fakes for the device: getprop, curl, log, sleep and the
# two tailscale programs. Checks the owner gate, the sha256 pin, the daemon flags, and that
# the single-use key file goes only after a successful login. Runs anywhere with bash.
set -euo pipefail
cd "$(dirname "$0")"
SRC=overlay/system/bin/riposte-uplink.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*"; exit 1; }

# A tarball shaped like the official one, with recording fakes for the two programs.
mkdir -p "$T/pkg/tailscale_9.9.9_arm64"
cat > "$T/pkg/tailscale_9.9.9_arm64/tailscaled" <<'F'
#!/bin/sh
logs=none; [ -n "$TS_LOGS_DIR" ] && [ -d "$TS_LOGS_DIR" ] && logs=dir
echo "tailscaled $* logs=$logs" >> "$CALLS"
F
cat > "$T/pkg/tailscale_9.9.9_arm64/tailscale" <<'F'
#!/bin/sh
echo "tailscale $*" >> "$CALLS"
exit "${UP_RC:-0}"
F
chmod +x "$T/pkg/tailscale_9.9.9_arm64/"*
tar -czf "$T/good.tgz" -C "$T/pkg" tailscale_9.9.9_arm64
GOOD=$(sha256sum "$T/good.tgz" | cut -d' ' -f1)

# Device fakes. sleep ends the script: every retry loop is one pass in a test.
mkdir -p "$T/bin"
printf '#!/bin/sh\necho "${OWNER:-1}"\n' > "$T/bin/getprop"
printf '#!/bin/sh\nwhile [ $# -gt 1 ]; do [ "$1" = -o ] && out=$2; shift; done\ncp "$TARBALL" "$out"\n' > "$T/bin/curl"
printf '#!/bin/sh\necho "log $*" >> "$CALLS"\n' > "$T/bin/log"
printf '#!/bin/sh\necho slept >> "$CALLS"\nkill -TERM $PPID\n' > "$T/bin/sleep"
chmod +x "$T/bin/"*

# run CASE PIN TARBALL [ENV...]: a copy of the script with this case's dir and pin.
run() {
  local name=$1 pin=$2 tarball=$3
  shift 3
  local dir=$T/$name/ts
  mkdir -p "$T/$name"
  sed -e "s|^DIR=.*|DIR=$dir|" -e "s|^TS_SHA256=.*|TS_SHA256=$pin|" -e "s|^TS_VERSION=.*|TS_VERSION=9.9.9|" \
    "$SRC" > "$T/$name/uplink.sh"
  env PATH="$T/bin:$PATH" CALLS="$T/$name/calls" TARBALL="$tarball" "$@" \
    sh "$T/$name/uplink.sh" > "$T/$name/out" 2>&1 || true
  touch "$T/$name/calls"
  echo "$dir"
}

enroll() {
  mkdir -p "$1"
  printf 'LOGIN_SERVER=https://hs.example\nAUTH_KEY=k-123\nNODE_NAME=rav4\n' > "$1/enroll.env"
}

# 1. Not a car-owner build: nothing happens.
d=$(run gate "$GOOD" "$T/good.tgz" OWNER=0)
[ ! -e "$d" ] || fail "owner gate: created $d"

# 2. A tarball that does not match the pin is refused and removed; nothing is installed.
d=$(run badsha "$(printf '0%.0s' {1..64})" "$T/good.tgz")
grep -q "refused" "$T/badsha/calls" || fail "bad sha: no refusal logged"
[ ! -e "$d/9.9.9/tailscaled" ] || fail "bad sha: binary installed"
[ ! -e "$d/ts.tgz" ] || fail "bad sha: tarball left behind"

# 3. Good tarball, enroll file present: install, start, log in, delete the key file.
d=$T/good/ts
enroll "$d"
run good "$GOOD" "$T/good.tgz" >/dev/null
[ -x "$d/9.9.9/tailscaled" ] || fail "good: tailscaled not installed"
[ "$(stat -c %a "$d")" = 700 ] || fail "good: dir mode $(stat -c %a "$d"), want 700"
grep -q -- "tailscaled --tun=userspace-networking .*--socks5-server=127.0.0.1:1055" "$T/good/calls" \
  || fail "good: daemon flags: $(cat "$T/good/calls")"
up=$(grep "^tailscale .* up " "$T/good/calls") || fail "good: no login"
for f in --shields-up --accept-routes=false --accept-dns=false --login-server=https://hs.example --hostname=rav4; do
  case "$up" in *" $f"*) ;; *) fail "good: login lacks $f" ;; esac
done
[ ! -e "$d/enroll.env" ] || fail "good: key file kept after login"

# 4. Login fails: the key file stays for the next attempt.
d=$T/upfail/ts
enroll "$d"
run upfail "$GOOD" "$T/good.tgz" UP_RC=1 >/dev/null
[ -e "$d/enroll.env" ] || fail "login failure: key file deleted"
grep -q slept "$T/upfail/calls" || fail "login failure: no retry wait"

# 5. Already joined (no enroll file): daemon only, no login.
d=$T/joined/ts
run joined "$GOOD" "$T/good.tgz" >/dev/null
grep -q "^tailscaled " "$T/joined/calls" || fail "joined: daemon not started"
! grep -q " up " "$T/joined/calls" || fail "joined: tried to log in"

# 6. Started by init: no HOME and a read-only cwd. tailscaled finds no place for its log
#    state and panics unless the script hands it an existing TS_LOGS_DIR. Its logs stay on
#    the car (--no-logs-no-support), never uploaded to the vendor.
d=$T/init/ts
mkdir -p "$T/init"
sed -e "s|^DIR=.*|DIR=$d|" -e "s|^TS_SHA256=.*|TS_SHA256=$GOOD|" -e "s|^TS_VERSION=.*|TS_VERSION=9.9.9|" \
  "$SRC" > "$T/init/uplink.sh"
(cd / && env -i PATH="$T/bin:/usr/bin:/bin" CALLS="$T/init/calls" TARBALL="$T/good.tgz" \
  sh "$T/init/uplink.sh" > "$T/init/out" 2>&1) || true
grep -q "^tailscaled .* logs=dir" "$T/init/calls" || fail "init: no log dir for tailscaled: $(cat "$T/init/calls")"
grep -q "^tailscaled .*--no-logs-no-support" "$T/init/calls" || fail "init: tailscaled would upload logs"

echo "UPLINK PASS"
