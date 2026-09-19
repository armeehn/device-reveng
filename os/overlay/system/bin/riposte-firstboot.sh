#!/system/bin/sh
# One-shot per /data: make CarLauncher the HOME role holder so the first boot
# lands on it instead of the OEM chooser. The marker lives in /data so a
# factory reset re-runs it and a re-flash of /system does not.
MARK=/data/local/tmp/riposte-firstboot.done
LAUNCHER=com.ripostelabs.carlauncher

[ -f "$MARK" ] && exit 0

cmd role add-role-holder --user 0 android.app.role.HOME "$LAUNCHER"
# A head unit never blanks its own screen; AOSP's default would after 30 s to 2 min.
settings put system screen_off_timeout 2147483647
touch "$MARK"
