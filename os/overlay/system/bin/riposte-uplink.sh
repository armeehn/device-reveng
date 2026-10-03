#!/system/bin/sh
# The car's private link to the estate: a userspace tailscaled, for the launcher's uplink
# (road-noise captures and diag logs up, noise models down). No VPN, no tun device, no
# route change: only a client that dials the local proxy reaches the tailnet.
#
#   launcher uplink ──SOCKS5 127.0.0.1:1055──▶ tailscaled --tun=userspace-networking ──▶ ingest
#
# The binary is too large for the system image (two files near 70 MB against about 50 MB
# of room in super), so this script fetches the official static build once onto /data and
# refuses it unless its sha256 matches the pin below. Bump TS_VERSION and TS_SHA256 together.
#
# The node joins once, from enroll.env (os/uplink/enroll.sh writes it: login server, a
# single-use tag:car key, host name), and the file is deleted as soon as the login succeeds.
# --shields-up: the tailnet can reach nothing on the car, whatever the policy says.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
TS_VERSION=1.102.4
TS_ARCH=arm64
TS_SHA256=9dd1e6a592a014bbaea0103167ffe299adeda4ba14e078ce9c2895364f6c4c3f
TS_URL=https://pkgs.tailscale.com/stable/tailscale_${TS_VERSION}_${TS_ARCH}.tgz
DIR=/data/misc/riposte/tailscale
BIN=$DIR/$TS_VERSION
ENROLL=$DIR/enroll.env
SOCK=$DIR/tailscaled.sock
SOCKS=127.0.0.1:1055
RETRY_S=300

umask 077
mkdir -p "$DIR"
chmod 0700 "$DIR"

# 1. The binary: fetch, verify, unpack. Retries until the car is online.
fetch() {
  tgz=$DIR/ts.tgz
  curl -fsSL --retry 3 -o "$tgz" "$TS_URL" || return 1
  got=$(sha256sum "$tgz" | cut -d' ' -f1)
  if [ "$got" != "$TS_SHA256" ]; then
    log -t riposte-uplink "tailscale tarball sha256 $got, want $TS_SHA256: refused"
    rm -f "$tgz"
    return 1
  fi
  rm -rf "$DIR/unpack" && mkdir -p "$DIR/unpack"
  tar -xzf "$tgz" -C "$DIR/unpack" || return 1
  mkdir -p "$BIN"
  mv "$DIR/unpack"/tailscale_*/tailscaled "$DIR/unpack"/tailscale_*/tailscale "$BIN/"
  chmod 0700 "$BIN/tailscaled" "$BIN/tailscale"
  rm -rf "$DIR/unpack" "$tgz"
}
until [ -x "$BIN/tailscaled" ] && [ -x "$BIN/tailscale" ]; do
  fetch && break
  sleep "$RETRY_S"
done

# 2. The daemon. State on /data, so the node keeps its identity across reboots.
# init gives no HOME and a read-only cwd, where tailscaled panics ("no safe place found to
# store log state"); TS_LOGS_DIR must already exist. Its logs stay on the car.
export TS_LOGS_DIR=$DIR/logs
mkdir -p "$TS_LOGS_DIR"
"$BIN/tailscaled" --tun=userspace-networking --statedir="$DIR/state" --socket="$SOCK" \
  --socks5-server="$SOCKS" --port=0 --no-logs-no-support &
DAEMON=$!

# 3. First login, once. The key is single use; it goes the moment the node is in.
if [ -f "$ENROLL" ]; then
  . "$ENROLL"
  until "$BIN/tailscale" --socket="$SOCK" up --login-server="$LOGIN_SERVER" --auth-key="$AUTH_KEY" \
      --hostname="$NODE_NAME" --shields-up --accept-dns=false \
      --accept-routes=false --timeout=60s; do
    sleep "$RETRY_S"
  done
  rm -f "$ENROLL"
  log -t riposte-uplink "joined the tailnet as $NODE_NAME"
fi

wait "$DAEMON"
