#!/system/bin/sh
# Send the Goodix driver's config table to the touch chip, forced.
#
# The GT9xx boots on the config in its own flash and reports X and Y swapped for this panel
# (bench, 2026-09-19: a touch lands in the left third, Y clamps). The kernel driver holds the
# right table (X 1920, Y 720, X2Y swap) and exposes it at /proc/gt9xx_config; a write there
# sends the bytes to the chip (the vendor's TouchPaneCfg USB tool uses the same node). The
# chip ignores a config whose version byte is not newer than its own, so the table goes with
# version 0x00 (the Goodix "take it anyway" value), Config_Fresh 1 and a recomputed checksum.
#
#     /proc/gt9xx_config ─read─▶ "0x5F,0x80,..." ─▶ [00][bytes 1..183][ck][01] ─write─▶ chip
#
# 186-byte layout (GT911 family): [0] version, [1..183] config, [184] checksum, [185] fresh.
# The stored checksum must verify before anything is written, so a table this script does
# not understand is left alone. The node can be overridden for the host test (test-gt9cfg.sh).
NODE=${1:-/proc/gt9xx_config}
[ -w "$NODE" ] || exit 0

CFG_LEN=186
HEX=$(tr -d ',\n' < "$NODE" | sed 's/0x//g' | cut -c1-$((CFG_LEN * 2)))
[ ${#HEX} -eq $((CFG_LEN * 2)) ] || exit 0

# Checksum: two's complement of the byte sum over [0..183].
sum_hex() {
  s=0
  for b in $(printf '%s' "$1" | sed 's/../& /g'); do
    s=$((s + 0x$b))
  done
  printf '%02x' $(( (-s) & 0xff ))
}

BODY=$(printf '%s' "$HEX" | cut -c1-$(((CFG_LEN - 2) * 2)))
STORED=$(printf '%s' "$HEX" | cut -c$(((CFG_LEN - 2) * 2 + 1))-$(((CFG_LEN - 1) * 2)))
[ "$(sum_hex "$BODY")" = "$STORED" ] || exit 0

FORCED=00$(printf '%s' "$BODY" | cut -c3-)
printf '%s%s01' "$FORCED" "$(sum_hex "$FORCED")" | xxd -r -p > "$NODE"
