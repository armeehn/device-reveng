#!/system/bin/sh
# Send the Goodix driver's config table to the touch chip.
#
# The GT9xx boots on the config in its own flash: 720 wide, 1920 tall, the panel's portrait
# native. The kernel driver advertises its landscape table (X 1920, Y 720, X2Y swap) and the
# framework scales raw values by that, so every touch lands in the left 37 % and Y clamps at
# the bottom row (bench, 2026-09-19). A write to /proc/gt9xx_config sends the driver's table
# to the chip; the vendor's TouchPaneCfg does the same with a file from a USB stick. Stock
# does it somewhere at boot that the GSI does not run; here it is explicit.
#
#     /proc/gt9xx_config ──read──▶ "0x5F,0x80,0x07,..." ──bytes──▶ /proc/gt9xx_config ──▶ chip
#
# The node can be overridden for the host test (test-gt9cfg.sh).
NODE=${1:-/proc/gt9xx_config}
[ -w "$NODE" ] || exit 0

# Read it all before opening the node for writing: a pipeline that reads and writes the same
# file truncates it first (test-gt9cfg.sh caught that).
HEX=$(tr -d ',\n' < "$NODE" | sed 's/0x//g')
[ -n "$HEX" ] || exit 0

printf '%s' "$HEX" | xxd -r -p > "$NODE"
