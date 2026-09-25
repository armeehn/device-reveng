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

# The i18n APEX too: libais_config.so pulls the GSI's libxml2, which needs libandroidicu.so, and
# this unlisted path gets no APEX link (2026-09-24: "AIS SERVER EXIT -2" every 5 s without it).
# Its sleep gate serves no client while sys.acc.state is unset (libais_core.so); stock's eventcenter
# set it, 0.2 has no eventcenter. The server only runs with the car on, so on is the truth here.
[ -n "$(getprop sys.acc.state)" ] || setprop sys.acc.state 1

export LD_LIBRARY_PATH=$AIS/lib:/apex/com.android.i18n/lib64
exec "$AIS/bin/ais_server"
