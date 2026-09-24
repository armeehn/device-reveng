#!/system/bin/sh
# The Qualcomm AIS camera server on car-owner builds, from /system/riposte/ais (lifted from the
# owner's stock image by build.sh step 3d). A wrapper, not `setenv` in riposte.rc: init's switch
# into u:r:su:s0 is a secure exec, and the linker drops LD_LIBRARY_PATH on those, so ais_server
# never found its libais.so (2026-09-24). This sh exec stays in su, so the path holds.
#
#   ais_server ──LD_LIBRARY_PATH──▶ /system/riposte/ais/lib/libais*.so + libmmosal.so
#              └─ qcarcam socket ◀── the launcher's libais_camera.so (reverse camera)
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
AIS=/system/riposte/ais
[ -x "$AIS/bin/ais_server" ] || exit 0

export LD_LIBRARY_PATH=$AIS/lib
exec "$AIS/bin/ais_server"
