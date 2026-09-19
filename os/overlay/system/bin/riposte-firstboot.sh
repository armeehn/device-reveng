#!/system/bin/sh
# One-shot per /data: make CarLauncher the HOME role holder so the first boot
# opens on it instead of the OEM chooser. The marker lives in /data so a
# factory reset re-runs it and a re-flash of /system does not.
MARK=/data/local/tmp/riposte-firstboot.done
LAUNCHER=com.ripostelabs.carlauncher

[ -f "$MARK" ] && exit 0

cmd role add-role-holder --user 0 android.app.role.HOME "$LAUNCHER"
# A head unit never blanks its own screen; AOSP's default would after 30 s to 2 min.
settings put system screen_off_timeout 2147483647
# ... and has no lock screen: AOSP booted 0.2 into a keyguard with "No SIM" over the launcher.
locksettings set-disabled true
settings put secure lockscreen.disabled 1
# Gesture navigation: swipe up is HOME, an edge swipe is BACK, only a pill on screen. The
# fascia keys cannot leave a foreign app on the owner path, so this is the way out of Settings.
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
touch "$MARK"
