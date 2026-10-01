#!/usr/bin/env bash
# car-update/road-noise-pull against a fake adb whose "unit" is a directory. Proves: only takes
# with a sidecar move, a re-plug pulls nothing twice, a corrupted pull never takes its final
# name, and nothing on the unit is deleted. What it cannot prove: that adb can read the app's
# Android/data folder on the real unit (checked on the farm: `adb shell ls` there works).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PULL=$HERE/car-update/road-noise-pull
COLLECT=$HERE/car-update/road-noise-collect

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/unit/rn" "$T/dest"
fail() { echo "FAIL: $1"; exit 1; }

# adb -s SERIAL shell CMD ARG | adb -s SERIAL pull REMOTE LOCAL, with remote paths under $T/unit.
# FAKE_CORRUPT=<name> flips the pulled copy of that file. Every call is logged.
cat > "$T/stub/adb" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$T/adb.log"
shift 2
case "\$1" in
    shell)
        case "\$2" in
            ls) ls -1 "$T/unit\$3" ;;
            sha256sum) sha256sum "$T/unit\$3" | sed "s#$T/unit##" ;;
            *) exit 1 ;;
        esac ;;
    pull)
        cp "$T/unit\$2" "\$3" || exit 1
        if [ "\$(basename "\$2")" = "\${FAKE_CORRUPT:-}" ]; then printf x >> "\$3"; fi ;;
    *) exit 1 ;;
esac
STUB
chmod +x "$T/stub/adb"
export PATH="$T/stub:$PATH" ROAD_NOISE_SRC=/rn

take() { head -c "$2" /dev/urandom > "$T/unit/rn/$1.wav"; [ "${3:-}" = open ] || echo '{}' > "$T/unit/rn/$1.json"; }
run() { : > "$T/adb.log"; bash "$PULL" SERIAL "$T/dest" > "$T/out" 2>&1; }

echo "== finished takes move, a take still recording does not"
take 20261001-162005-highway 4000
take 20261001-163000-fan-high 3000
take 20261001-164500-city 2000 open
echo junk > "$T/unit/rn/notes.txt"
run || fail "exited $?: $(cat "$T/out")"
grep -q "road noise: 2 new, 0 failed, 2 takes" "$T/out" || fail "summary: $(cat "$T/out")"
cmp -s "$T/unit/rn/20261001-162005-highway.wav" "$T/dest/20261001-162005-highway.wav" || fail "highway wav differs"
[ -f "$T/dest/20261001-163000-fan-high.json" ] || fail "fan-high sidecar missing"
[ ! -e "$T/dest/20261001-164500-city.wav" ] || fail "pulled a take with no sidecar"
[ ! -e "$T/dest/notes.txt" ] || fail "pulled a file that is not a take"

echo "== a re-plug pulls nothing twice"
run || fail "re-run exited $?: $(cat "$T/out")"
grep -q "road noise: 0 new" "$T/out" || fail "re-run summary: $(cat "$T/out")"
! grep -q " pull " "$T/adb.log" || fail "re-run pulled: $(cat "$T/adb.log")"

echo "== a corrupted pull never takes its final name, and is retried next time"
take 20261001-170000-rain 5000
: > "$T/adb.log"
if FAKE_CORRUPT=20261001-170000-rain.wav bash "$PULL" SERIAL "$T/dest" > "$T/out" 2>&1; then
    fail "corrupt pull reported success"
fi
grep -q "1 failed" "$T/out" || fail "corrupt summary: $(cat "$T/out")"
[ ! -e "$T/dest/20261001-170000-rain.wav" ] || fail "corrupt file kept"
[ ! -e "$T/dest/20261001-170000-rain.wav.part" ] || fail "corrupt .part left behind"
[ ! -e "$T/dest/20261001-170000-rain.json" ] || fail "sidecar pulled without its wav"
run || fail "retry exited $?: $(cat "$T/out")"
cmp -s "$T/unit/rn/20261001-170000-rain.wav" "$T/dest/20261001-170000-rain.wav" || fail "retry did not pull rain"

echo "== nothing on the unit is deleted"
[ "$(ls "$T/unit/rn" | wc -l)" -eq 8 ] || fail "unit lost files: $(ls "$T/unit/rn")"
! grep -qE "shell (rm|mv)" "$T/adb.log" || fail "touched unit files: $(cat "$T/adb.log")"

echo "== the share copy on x does nothing while zero is offline"
cat > "$T/stub/ssh" <<'STUB'
#!/usr/bin/env bash
exit 255
STUB
chmod +x "$T/stub/ssh"
DEST="$T/share" bash "$COLLECT" > "$T/out" 2>&1 || fail "collect exited $?: $(cat "$T/out")"
grep -q "zero offline" "$T/out" || fail "collect summary: $(cat "$T/out")"
[ ! -e "$T/share" ] || fail "collect made the share folder with zero offline"

echo "ROAD-NOISE PASS"
