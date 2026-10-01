#!/system/bin/sh
# Suspend the way stock does: suspend-to-idle, never the kernel's deep "mem" state.
#
#   ACC off ─▶ MCU POWER press ─▶ screen off ─▶ system_suspend writes "mem" to /sys/power/state
#                                                  └─ mem_sleep picks what "mem" means:
#                                                       deep    PSCI cluster collapse (never resumed)
#                                                       s2idle  CPUs idle, every wake IRQ live
#
# Stock's own android.system.suspend@1.0-service writes "freeze" (s2idle), and only once
# eventcenter's standby sets sys.release_lock.state=1. The GSI's AOSP copy writes "mem", which
# this kernel takes to deep collapse through lpm_suspend_enter, a path the vendor never runs
# (lpm_levels.sleep_disabled=1 on the boot line). The first real "mem" suspend on the car never
# resumed at ACC on: the MCU's tuner played behind a black panel until RST (vc946, 2026-10-01).
# Pointing "mem" at s2idle gives the GSI stock's sleep. The node is vendor_sysfs_suspend, which
# init may not write, so init runs this as root (riposte.rc, on boot).
SYSFS=${RIPOSTE_SYSFS:-}   # test hook: a prefix in front of the node path
NODE=$SYSFS/sys/power/mem_sleep
MODE=s2idle
TAG=riposte-sleep

# A kernel without the node has one sleep state; nothing to choose.
if [ ! -e "$NODE" ]; then
    log -t "$TAG" "no $NODE: kernel offers one sleep state"
    exit 0
fi

# The kernel lists every state and brackets the current one: "s2idle [deep]".
before=$(cat "$NODE")
case "$before" in
    *"[$MODE]"*)
        log -t "$TAG" "suspend already $MODE ($before)"
        exit 0
        ;;
esac

if ! echo "$MODE" > "$NODE"; then
    log -p e -t "$TAG" "could not write $MODE to $NODE ($before)"
    exit 1
fi
log -t "$TAG" "suspend $before -> $(cat "$NODE")"
