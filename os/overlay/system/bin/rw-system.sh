#!/system/bin/sh
# Riposte OS replacement for TrebleDroid's rw-system.sh. init runs it from
# /system/etc/init/vndk.rc with `exec - root --`; the phhsu_exec label (set by build.sh) is
# what moves it into the su domain, as upstream's copy is.
#
# Upstream probes some two hundred phones by fingerprint on every boot: 474 executed lines
# at 10 to 60 ms each, 3.3 s of a 26 s boot on this unit (boot-speed/BOOT_SPEED.md). Traced
# on the GT6-EAU, its net effect is the list below and nothing else, so this file does only
# that. Left out on purpose, with the reason:
#   - resize2fs of the system fs: the image is sized at build time and never written to
#   - keylayout copy to /mnt/phh: upstream changes nothing on this device
#   - binds over qti-logkit, SysuiDarkTheme, libpdx, install-recovery.sh, samsung uwb:
#     none of those paths exist on this vendor
#   - `find /sys -name fts_gesture_mode`: 0.85 s for a FocalTech gesture node this panel lacks
readonly PHH=/mnt/phh
readonly EMPTY_FILE=/system/phh/empty
readonly EMPTY_DIR=$PHH/empty_dir
readonly VENDOR_AUDIO_MAX_FILES=3   # upstream's "vendor ships no real audio config" threshold

# 1. adb over USB: synchronous ffs (the async path breaks adbd on this kernel).
setprop sys.usb.ffs.aio_compat true
setprop persist.adb.nonblocking_ffs false

# 2. the tmpfs every bind below is served from
mkdir -p $PHH
mount -t tmpfs -o rw,nodev,relatime,mode=755,gid=0 none $PHH || true
mkdir $EMPTY_DIR

# 3. keymaster: the vendor's 3.0 impl compares release and patch level with the boot image,
# so it gets a copy whose property names point at what the boot image really carries.
setprop ro.keymaster.mod 'AOSP on ARM64'
setprop ro.keymaster.brn Android
boot="$(find /dev/block -type l -iname boot"$(getprop ro.boot.slot_suffix)" | grep by-name | head -n 1)"
release="$(strings -n 2 /dev/block/by-name/vbmeta* | grep -A1 com.android.build.system.os_version | grep -E '^[0-9]+$' | sort -n | head -n1)"
[ -z "$release" ] && release="$(getSPL "$boot" android)"
spl="$(getSPL "$boot" spl)"
setprop ro.keymaster.xxx.release "$release"
setprop ro.keymaster.xxx.security_patch "$spl"
setprop ro.keymaster.xxx.vbmeta_state unlocked
setprop ro.keymaster.xxx.verifiedbootstate orange
for f in /vendor/lib64/hw/android.hardware.keymaster@3.0-impl-qti.so \
         /vendor/lib/hw/android.hardware.keymaster@3.0-impl-qti.so; do
  [ -f "$f" ] || continue
  ctxt="$(ls -lZ "$f" | grep -oE 'u:object_r:[^:]*:s0')"
  copy="$PHH/$(echo "$f" | tr / _)"
  cp -a "$f" "$copy"
  sed -i \
    -e 's/ro.build.version.release/ro.keymaster.xxx.release/g' \
    -e 's/ro.build.version.security_patch/ro.keymaster.xxx.security_patch/g' \
    -e 's/ro.product.model/ro.keymaster.mod/g' \
    -e 's/ro.product.brand/ro.keymaster.brn/g' \
    "$copy"
  chcon "$ctxt" "$copy"
  mount -o bind "$copy" "$f"
done

# 4. no fingerprint HAL in the vendor manifest: hide the feature flag the GSI ships
mount -o bind $EMPTY_FILE /system/etc/permissions/android.hardware.fingerprint.xml

# 5. the vendor's /vendor/etc/audio is one file; the GSI's own policy takes over
if [ "$(find /vendor/etc/audio -type f | wc -l)" -le $VENDOR_AUDIO_MAX_FILES ]; then
  mount -o bind $EMPTY_DIR /vendor/etc/audio || true
fi

setprop ctl.stop console

# 6. /system/xbin becomes a writable stub so phh-su can bind a real su over it
mkdir $PHH/xbin
chmod 0755 $PHH/xbin
chcon u:object_r:system_file:s0 $PHH/xbin
touch $PHH/xbin/su
chcon u:object_r:system_file:s0 $PHH/xbin/su
mount -o bind $PHH/xbin /system/xbin

setprop ro.product.first_api_level "$(getprop ro.vndk.version)"
ln -s /dev/block/platform/"$(getprop ro.boot.boot_devices)" /dev/block/bootdevice

# 7. the rest of the property set upstream applies on every device
resetprop_phh service.adb.root 0
setprop sys.usb.all_controllers "$(ls /sys/class/udc | tr ' ' ,)"
resetprop_phh ro.bluetooth.library_name libbluetooth.so
setprop vendor.display.res_switch_en 1
resetprop_phh ro.control_privapp_permissions log
[ -d /mnt/vendor/persist ] && mount /mnt/vendor/persist /persist
setprop ro.surface_flinger.use_color_management false
setprop debug.phh.props.omposer-service vendor
resetprop_phh ro.config.media_vol_steps 25
resetprop_phh ro.config.media_vol_default 8
