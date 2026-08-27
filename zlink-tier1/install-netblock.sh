#!/usr/bin/env bash
# install-netblock.sh — install the systemless hosts module to Magisk. Reversible.
#   ./install-netblock.sh            install + reboot
#   ./install-netblock.sh uninstall  remove the module + reboot
#   ./install-netblock.sh verify     check resolution is null-routed (post-reboot)
set -uo pipefail
SERIAL="${ADB_SERIAL:-}"; ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
SU() { "${ADB[@]}" shell su -c "$*"; }
MOD=/data/adb/modules/netblock_zjinnova
HERE="$(cd "$(dirname "$0")" && pwd)"

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "!! no device"; exit 1; }
SU id | grep -q uid=0 || { echo "!! need Magisk root"; exit 1; }

case "${1:-install}" in
  install)
    echo "[*] pushing module ..."
    "${ADB[@]}" push "$HERE/net-block-zjinnova" /data/local/tmp/netblock_zjinnova >/dev/null
    SU "rm -rf $MOD && mkdir -p $MOD && cp -r /data/local/tmp/netblock_zjinnova/* $MOD/ && \
        chmod -R 0755 $MOD && chown -R 0:0 $MOD && touch $MOD/update && rm -rf /data/local/tmp/netblock_zjinnova"
    echo "[*] installed. Rebooting to activate systemless hosts ..."
    "${ADB[@]}" reboot
    echo "[*] after boot: ./install-netblock.sh verify"
    ;;
  uninstall)
    SU "rm -rf $MOD"; echo "[*] module removed. Rebooting ..."; "${ADB[@]}" reboot ;;
  verify)
    echo "[*] hosts in effect:"; SU 'grep -iE "zjinnova|bugly" /system/etc/hosts' || echo "  (not applied yet?)"
    echo "[*] resolution test:"; SU 'for h in www.zjinnova.com bugly.qq.com; do printf "  %-22s " "$h"; getent hosts "$h" 2>/dev/null || ping -c1 -W1 "$h" 2>/dev/null | head -1; done' ;;
  *) echo "usage: $0 [install|uninstall|verify]"; exit 2 ;;
esac
