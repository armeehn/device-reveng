#!/usr/bin/env bash
# riposte-usb-role.sh against stubs: getprop, setprop, log and a sysfs prefix. Runs anywhere
# with bash. What it cannot prove: the dwc3 driver's reaction and the vendor init's timing,
# both bench items (`adb logcat -s riposte-usb`; a pigtail that answers or not).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=$HERE/overlay/system/bin

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/props"

fail() { echo "FAIL: $1"; exit 1; }

# One file per property, so getprop/setprop round-trip and the test can inspect writes.
cat > "$T/stub/getprop" <<STUB
#!/usr/bin/env bash
cat "$T/props/\$1" 2>/dev/null || true
STUB
cat > "$T/stub/setprop" <<STUB
#!/usr/bin/env bash
printf %s "\$2" > "$T/props/\$1"
STUB
cat > "$T/stub/log" <<STUB
#!/usr/bin/env bash
shift 2; echo "\$*" >> "$T/logcat"
STUB
chmod +x "$T/stub/"*
export PATH="$T/stub:$PATH"
export RIPOSTE_SYSFS="$T/sysfs" RIPOSTE_USB_BOOT_WAIT_S=0
NODE="$T/sysfs/sys/devices/platform/soc/4e00000.ssusb/mode"
mkdir -p "$(dirname "$NODE")"

run() { : > "$T/logcat"; rm -f "$T/props/sys.usb.config"; bash "$BIN/riposte-usb-role.sh" "$@" > "$T/out" 2>&1; }

echo "== unset prop on a car image: host"
printf host > "$NODE"; rm -f "$T/props/persist.riposte.usb.role" "$T/props/ro.riposte.os.bench"
run || fail "exited $?: $(cat "$T/out")"
grep -q "image default host" "$T/logcat" || fail "default not logged: $(cat "$T/logcat")"
grep -q "port already host" "$T/logcat" || fail "matching node rewritten"
[ ! -e "$T/props/sys.usb.config" ] || fail "adb gadget asked for in host mode"

echo "== unset prop on a bench image: peripheral, adb gadget first"
setprop ro.riposte.os.bench 1
run || fail "exited $?"
[ "$(cat "$NODE")" = peripheral ] || fail "node got '$(cat "$NODE")', want peripheral"
[ "$(cat "$T/props/sys.usb.config")" = adb ] || fail "sys.usb.config not adb"
grep -q "port host -> peripheral" "$T/logcat" || fail "switch not logged: $(cat "$T/logcat")"

echo "== stored choice wins over the image default"
setprop persist.riposte.usb.role host
run boot || fail "exited $?"
[ "$(cat "$NODE")" = host ] || fail "stored host not applied on a bench image"
grep -q "port peripheral -> host" "$T/logcat" || fail "switch not logged"

echo "== junk values are refused before any write"
for bad in none HOST otg 1; do
  setprop persist.riposte.usb.role "$bad"
  run && fail "accepted '$bad'"
  [ "$(cat "$NODE")" = host ] || fail "node changed on '$bad'"
done

echo "== no node on this board: says so, exits 1"
setprop persist.riposte.usb.role peripheral
rm -f "$NODE"
run && fail "exit 0 without the node"
grep -q "no /sys/devices/platform/soc/4e00000.ssusb/mode" "$T/logcat" || fail "missing node not logged"

echo "USB-ROLE PASS"
