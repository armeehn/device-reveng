#!/system/bin/sh
# The Qualcomm AIS camera server on car-owner builds, from /system/riposte/ais (lifted from the
# owner's stock image by build.sh step 3d). A wrapper, not `setenv` in riposte.rc: init's switch
# into u:r:su:s0 is a secure exec, and the linker drops LD_LIBRARY_PATH on those, so ais_server
# never found its libais.so (2026-09-24). This sh exec stays in su, so the path holds.
#
#   ais_server ──LD_LIBRARY_PATH──▶ /system/riposte/ais/lib/libais*.so + libmmosal.so
#              └─ qcarcam socket ◀── the launcher's libais_camera.so (reverse + 360 cameras)
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
AIS=${RIPOSTE_AIS:-/system/riposte/ais}   # test hook: test-root-camera.sh points it at a stub
[ -x "$AIS/bin/ais_server" ] || exit 0

# The i18n APEX too: libais_config.so pulls the GSI's libxml2, which needs libandroidicu.so, and
# this unlisted path gets no APEX link (2026-09-24: "AIS SERVER EXIT -2" every 5 s without it).
# Its sleep gate serves no client while sys.acc.state is unset (libais_core.so); stock's eventcenter
# set it, 0.2 has no eventcenter. The server only runs with the car on, so on is the truth here.
[ -n "$(getprop sys.acc.state)" ] || setprop sys.acc.state 1

# The XS9922B (qcarcam inputs 0-3, the 360 cameras) in its four-channel mode. libais_xs9922b.so
# reads two props (CAMERA_360.md):
#   sensorcfg.resolution  TYP0_* = all four channels stream; TYP1_CID0_VCH<n>_RES<m> = channel n
#                         alone (stock's XS9922B-reverse boards, BackcarEvent.setXS9922BCameraType).
#                         Unset reads as TYP0_CID0_VCH1_RES0, the value pinned here, so a TYP1 left
#                         in /data by stock cannot leave three tiles dark.
#   sensor360.resolution  quad format: 0 AHD 1080p25 (the bench camera), 1 AHD 720p (25 Hz by its
#                         name, not bench-tested), 2 AHD 720p30.
#                         Unset or junk reads as 0; kept when valid so a 720p kit is one setprop.
[ "$(getprop persist.camera.sensorcfg.resolution)" = TYP0_CID0_VCH1_RES0 ] ||
  setprop persist.camera.sensorcfg.resolution TYP0_CID0_VCH1_RES0
case "$(getprop persist.camera.sensor360.resolution)" in
  0|1|2) ;;
  *) setprop persist.camera.sensor360.resolution 0 ;;
esac

export LD_LIBRARY_PATH=$AIS/lib:/apex/com.android.i18n/lib64
exec "$AIS/bin/ais_server"
