#!/usr/bin/env bash
# Install or update the car ingest service on the estate host that holds the share.
#
#   install-ingest.sh SHARE_ROOT TAILNET_IP TAILSCALE_SOCKET
#
# SHARE_ROOT holds road-noise/, car-diag/ and models/ (created if missing, owned by sasha).
# Re-running it updates the code and restarts the service; the arguments file is rewritten.
set -euo pipefail

readonly LIB=/usr/local/lib/car-ingest
readonly UNIT=/etc/systemd/system/car-ingest.service
readonly ARGS_FILE=/etc/default/car-ingest
readonly OWNER=sasha
readonly GROUP=smbshare

if [ $# -ne 3 ]; then
  echo "usage: $0 SHARE_ROOT TAILNET_IP TAILSCALE_SOCKET" >&2
  exit 2
fi
share=$1 ip=$2 sock=$3
here=$(cd "$(dirname "$0")" && pwd)

# The share folders the trainer and the car agree on (CONTRACT.md on the share).
for d in road-noise car-diag models; do
  install -d -m 2775 -o "$OWNER" -g "$GROUP" "$share/$d"
done

install -d -m 0755 "$LIB"
install -m 0644 "$here/ingest.py" "$LIB/ingest.py"
install -m 0644 "$here/car-ingest.service" "$UNIT"
cat > "$ARGS_FILE" <<ARGS
INGEST_ARGS=--noise-root $share/road-noise --diag-root $share/car-diag --models-root $share/models --state-dir /var/lib/car-ingest --host $ip --port 8797 --tailscale-socket $sock
ARGS

systemctl daemon-reload
systemctl enable --now car-ingest.service
systemctl restart car-ingest.service
sleep 1
systemctl is-active car-ingest.service
