#!/system/bin/sh
# One-shot per /data: make CarLauncher the HOME role holder so the first boot
# opens on it instead of the OEM chooser. The marker lives in /data so a
# factory reset re-runs it and a re-flash of /system does not. The doze block
# above the marker is the one exception and says why.
MARK=/data/local/tmp/riposte-firstboot.done
LAUNCHER=com.ripostelabs.carlauncher

# Doze and dreams, every boot: the GSI ships both on and stock had neither. Car, 2026-09-23:
# 2 s after the launcher started, DreamManagerService entered dreamland with SystemUI's
# DozeService and PowerManagerService logged "Dozing..." with no "Going to sleep" line; the
# panel stayed black, backlit and deaf to taps until a key woke it. Not under the marker: a unit
# past its first boot must still get these, and `settings put` is idempotent.
settings put secure doze_enabled 0                # DreamManager's doze dream (DozeService) is off
settings put secure doze_always_on 0              # no always-on display
settings put secure doze_pulse_on_pick_up 0       # no pulse from the pick-up sensor
settings put secure doze_pulse_on_double_tap 0    # no pulse from a double tap on a dark panel
settings put secure screensaver_enabled 0         # no screen saver (dream) at all
settings put secure screensaver_activate_on_dock 0   # ... not when "docked" (the GSI default is on)
settings put secure screensaver_activate_on_sleep 0  # ... not when the screen would sleep

[ -f "$MARK" ] && exit 0

cmd role add-role-holder --user 0 android.app.role.HOME "$LAUNCHER"
# A head unit never blanks its own screen; AOSP's default would after 30 s to 2 min.
settings put system screen_off_timeout 2147483647
# ... and has no lock screen: AOSP booted 0.2 into a keyguard with "No SIM" over the launcher.
locksettings set-disabled true
settings put secure lockscreen.disabled 1
# What Setup Doctor would otherwise ask the owner to grant over adb: the runtime permissions
# and notification listeners a reinstall drops. Root here, so the first boot reads 7 of 7.
pm grant "$LAUNCHER" android.permission.ACCESS_FINE_LOCATION
# Location itself: a GSI boots with the master switch off, and every provider (the GPS clock
# source, the suite's GPS and Weather apps) reads nothing until it is on. The vendor image
# shipped it on.
cmd location set-location-enabled true
pm grant "$LAUNCHER" android.permission.BLUETOOTH_CONNECT
appops set "$LAUNCHER" WRITE_SETTINGS allow
for listener in media.MediaListenerService nav.NavListenerService notif.ShelfListenerService; do
  cmd notification allow_listener "$LAUNCHER/$LAUNCHER.$listener"
done
# Gesture navigation: swipe up is HOME, an edge swipe is BACK, only a pill on screen. The
# fascia keys cannot leave a foreign app on the owner path, so this is the way out of Settings.
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
# Root for the launcher is not a first-boot item: riposte-root.sh (every boot, from init)
# seeds the sudaemon grant when it is missing, so a /data wipe cannot lose it.
touch "$MARK"
