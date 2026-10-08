#!/system/bin/sh
# Mark this slot good only once it has proved itself, so a bad OTA rolls back by itself.
#
#   AOSP: zygote-start ─▶ update_verifier marks the slot good ─▶ update_engine merges the
#         snapshots ─▶ the old slot is gone. A framework that crash-loops (vc688) is kept.
#   Riposte: update_verifier.rc no longer marks. At boot_completed this script decides:
#
#     slot already good ──────────────────────────────▶ nothing (a normal boot)
#     no OTA marker, or it names the other slot ───────▶ mark now (as update_verifier did)
#     first boot of an OTA (marker = this slot) ─┬─ launcher up HEALTHY_S without a restart ─▶ mark
#                                                └─ not by DEADLINE_S ─▶ old slot active, reboot
#
# update_engine waits for the mark before it merges, so until then the old slot is intact and
# libsnapshot drops the update when the old slot boots. The launcher's OsUpdater writes the
# marker (the slot it reboots into) just before its reboot. See share carlauncher/os-ota.md.
DATA=${RIPOSTE_DATA:-/data}   # test hook: a prefix for /data
MARK=$DATA/riposte/ota-pending
LAUNCHER=com.ripostelabs.carlauncher
HEALTHY_S=120
DEADLINE_S=300
POLL_S=5
TAG=riposte-bootcheck

# bootctl's is-* queries on the unit PRINT 1 or 0 and exit 0 either way (boot HAL 1.1, seen on
# the bench 2026-10-07). An older bootctl answers by exit status and prints nothing. Both read here.
slot_is() { # slot_is is-slot-bootable|is-slot-marked-successful SLOT
    answer=$(bootctl "$1" "$2") || return 1
    [ "$answer" != 0 ]
}

cur=$(bootctl get-current-slot)
if slot_is is-slot-marked-successful "$cur"; then
    rm -f "$MARK"
    exit 0
fi

# Not the first boot of an OTA: a flash, or the bootloader already fell back. Today's rule.
target=$(cat "$MARK" 2>/dev/null)
if [ "$target" != "$cur" ]; then
    bootctl mark-boot-successful
    rm -f "$MARK"
    log -t "$TAG" "slot $cur marked (no OTA pending for it)"
    exit 0
fi

# The launcher must hold one pid for HEALTHY_S; a new pid is a restart and starts the count again.
waited=0
up=0
last=""
while [ "$waited" -lt "$DEADLINE_S" ]; do
    pid=$(pidof "$LAUNCHER")
    if [ -n "$pid" ] && [ "$pid" = "$last" ]; then
        up=$((up + POLL_S))
    else
        up=0
    fi
    last=$pid
    if [ "$up" -ge "$HEALTHY_S" ]; then
        bootctl mark-boot-successful
        rm -f "$MARK"
        log -t "$TAG" "slot $cur marked: launcher up ${up}s after the update"
        exit 0
    fi
    sleep "$POLL_S"
    waited=$((waited + POLL_S))
done

other=$((1 - cur))
if ! slot_is is-slot-bootable "$other"; then
    log -t "$TAG" "slot $cur unhealthy after ${DEADLINE_S}s, slot $other not bootable: staying, unmarked"
    exit 0
fi
rm -f "$MARK"
log -t "$TAG" "slot $cur unhealthy after ${DEADLINE_S}s: rolling back to slot $other"
bootctl set-active-boot-slot "$other"
reboot
