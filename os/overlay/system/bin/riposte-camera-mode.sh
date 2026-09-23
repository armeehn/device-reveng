#!/system/bin/sh
# Reverse-camera decoder mode from one property, so the launcher sets
# persist.riposte.camera.mode=<n> and nothing else (init runs this on every change and again
# at boot, when persisted props replay):
#
#   setprop persist.riposte.camera.mode 3
#     └─ init: on property:persist.riposte.camera.mode=* ──► this script (root)
#          ├─ setprop sys.pr2000.writable 1            (BackcarEvent.java:1371)
#          └─ printf v3 > each decoder node that exists (CamerasSignalDetection.java:202-219)
#
# Values are the vendor picker's row (BackcarSignalTypeSet.java:61-93), the SIGNAL_* constants
# of CamerasSignalDetection.java:35-42:
#   0 auto   1 CVBS NTSC   2 CVBS PAL   3 AHD 720p25   4 AHD 1080p25
#   5 AHD 720p60   6 AHD 1080p30   7 AHD 720p30   8 CVBS PAL60
#
# Stock writes "v<n>" to PATH_PR2000 (CamerasSignalDetection.java:33; BackcarEvent.java
# 1392-1408) and the landscape settings view to the same decoder under the CCI camera
# (backcarset_land/SignalView.java:91). RN6752_PATH (CamerasSignalDetection.java:34) is
# declared and never written on stock; it is in the list for a board that has it, guarded
# like the others on the node existing. persist.camera.sensorcfg.signal is only read
# (CamerasSignalDetection.java:575) and .resolution is the XS9922B path this unit never
# takes (BackcarEvent.java:2232-2238, Sys_XS992B_Reverse_Type_Key defaults to 0,
# EventService.java:6700), so neither is written here.
PROP=persist.riposte.camera.mode
WRITABLE_PROP=sys.pr2000.writable
SYSFS=${RIPOSTE_SYSFS:-}   # test hook: a prefix in front of every node path
NODES="/sys/pr2000/pr2000
/sys/devices/platform/soc/5c0c000.qcom,cci/5c0c000.qcom,cci:qcom,camera@0/pr2000
/sys/devices/platform/vehicle/rn6752_mode"
MODE_MAX=8
TAG=riposte-camera

say() {
  log -t "$TAG" "$1"
  echo "$TAG: $1"
}

mode=$(getprop "$PROP")
case "$mode" in
  ''|*[!0-9]*) say "ignored $PROP='$mode': want 0..$MODE_MAX"; exit 1 ;;
esac
if [ "$mode" -gt "$MODE_MAX" ]; then
  say "ignored $PROP=$mode: want 0..$MODE_MAX"
  exit 1
fi

# The vendor unlocks the node before every write; the driver refuses it otherwise.
setprop "$WRITABLE_PROP" 1

written=0
for node in $NODES; do
  [ -e "$SYSFS$node" ] || continue
  if printf 'v%s' "$mode" > "$SYSFS$node"; then
    written=$((written + 1))
  else
    say "write v$mode to $node failed"
  fi
done

say "mode $mode -> v$mode on $written node(s)"
