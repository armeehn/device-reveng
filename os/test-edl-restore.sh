#!/usr/bin/env bash
# End-to-end test of edl-restore.sh against a stubbed `edl` and `lpdump`: checks the call
# order, that nothing is written before the GPT is read, and the converted ws sectors.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT
mkdir -p $T/backup $T/bin $T/edl

# Backup fixture: tiny images with a sums file.
for p in boot dtbo vbmeta vbmeta_system system_ext product vendor; do head -c 4096 /dev/zero > $T/backup/$p.img; done
head -c $((2048 * 512 + 512)) /dev/zero > $T/backup/system.img
(cd $T/backup && sha256sum *.img > SHA256SUMS)

# Stub edl: logs every call; printgpt prints a table with super at 16 MiB.
cat > $T/bin/edl-stub <<'STUB'
#!/bin/bash
echo "$*" >> "$STUB_LOG"
case "$1" in --memory=*) shift ;; esac      # global option before the command, as the real tool takes it
case "$1" in
    printgpt)
        printf 'GPT Table:\n'
        for p in boot_b dtbo_b vbmeta_b vbmeta_system_b; do
            printf '%-20s Offset 0x0000000000100000, Length 0x0000000000100000, Flags 0x0, UUID x, Type y, Active True\n' "$p:"
        done
        printf '%-20s Offset 0x0000000001000000, Length 0x0000000180000000, Flags 0x0, UUID x, Type y, Active True\n' "super:"
        ;;
    rs) : > "$4" ;;
    reset) case "$*" in *--memory*) echo "usage: edl.py reset [--loader=filename] ..." >&2; exit 1;; esac ;;
esac
STUB
chmod +x $T/bin/edl-stub

# Stub lpdump: the same table test-edl-extents.sh uses.
cat > $T/bin/lpdump <<'STUB'
#!/bin/bash
cat <<LP
  Name: system_b
  Extents:
    0 .. 2047 linear super 2048
    2048 .. 4095 linear super 8192
  Name: vendor_b
  Extents:
    0 .. 4095 linear super 16384
LP
STUB
chmod +x $T/bin/lpdump

export STUB_LOG=$T/calls.txt
PATH=$T/bin:$PATH EDL_BIN=$T/bin/edl-stub EDL_CMD="$T/bin/edl-stub --memory=ufs" ./edl-restore.sh --backup $T/backup --edl-dir $T/edl --work $T/work --yes

calls=$(sed 's/^--memory=ufs //' $T/calls.txt | cut -d' ' -f1-2 | tr '\n' ';')
want="printgpt;w boot_b;w dtbo_b;w vbmeta_b;w vbmeta_system_b;rs 4096;ws 4352;ws 5120;ws 6144;reset --loader=$T/edl/prog_firehose_qcm6125.bin;"
[ "$calls" = "$want" ] || { echo "FAIL calls: [$calls]"; echo "want:  [$want]"; exit 1; }

# A bad checksum must stop the run before printgpt.
: > $T/calls.txt
echo x >> $T/backup/vendor.img
if PATH=$T/bin:$PATH EDL_BIN=$T/bin/edl-stub EDL_CMD="$T/bin/edl-stub --memory=ufs" ./edl-restore.sh --backup $T/backup --edl-dir $T/edl --work $T/work2 --yes >/dev/null 2>&1; then
    echo "FAIL: corrupt backup accepted"; exit 1
fi
[ ! -s $T/calls.txt ] || { echo "FAIL: edl called with a corrupt backup"; exit 1; }
echo "EDL-RESTORE PASS"
