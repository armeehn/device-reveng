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

echo "== manual takes are filed into the contract layout, once, originals kept"
FILE=$HERE/car-update/road-noise-file
S=$T/road
mkdir -p "$S/2026-10-01"
flat() {  # flat NAME TAG STARTED SECONDS BYTES: a Recorder take as zero hands it over
    head -c "$5" /dev/urandom > "$S/$1.wav"
    printf '{"file":"%s.wav","tag":"%s","source":"VOICE_COMMUNICATION","processing":"none","rate_hz":48000,"channels":1,"bits":16,"started":"%s","seconds":%s,"speed_kmh_per_second":null}\n' \
        "$1" "$2" "$3" "$4" > "$S/$1.json"
    touch -d "2026-10-01T22:24:00Z" "$S/$1.wav" "$S/$1.json"
}
flat 20261001-151300-city city 2026-10-01T22:13:00Z 116.44 3000
flat 20261001-151517-fan-high fan-high 2026-10-01T22:15:17Z 120.34 3100
flat 20261001-181000-highway highway 2026-10-02T01:10:00Z 30 3200
# The same audio already came in over the uplink: its row must not be doubled.
flat 20261001-151724-city city 2026-10-01T22:17:24Z 239 3300
up=$(sha256sum "$S/20261001-151724-city.wav" | cut -c1-64)
cp "$S/20261001-151724-city.wav" "$S/2026-10-01/${up:0:16}.wav"
echo "{\"id\":\"${up:0:16}\",\"path\":\"2026-10-01/${up:0:16}.wav\",\"sha256\":\"$up\",\"device\":\"rav4\",\"source\":\"auto\"}" > "$S/index.jsonl"
echo "{\"started_at\":\"2026-10-01T22:17:24Z\",\"device\":\"rav4\",\"band\":\"city\"}" > "$S/2026-10-01/${up:0:16}.json"

python3 "$FILE" "$S" > "$T/out" 2>&1 || fail "file exited $?: $(cat "$T/out")"
grep -q "road noise: filed 3, already 1" "$T/out" || fail "file summary: $(cat "$T/out")"
[ "$(wc -l < "$S/index.jsonl")" -eq 4 ] || fail "index rows: $(cat "$S/index.jsonl")"
city=$(sha256sum "$S/inbox-done/20261001-151300-city.wav" | cut -c1-64)
[ -f "$S/2026-10-01/${city:0:16}.wav" ] || fail "city wav not at <day>/<sha16>.wav"
cmp -s "$S/inbox-done/20261001-151300-city.wav" "$S/2026-10-01/${city:0:16}.wav" || fail "city copy differs"
ls "$S"/*.wav >/dev/null 2>&1 && fail "flat takes left in the root: $(ls "$S")"
[ "$(ls "$S/inbox-done" | wc -l)" -eq 8 ] || fail "inbox-done: $(ls "$S/inbox-done")"

# Sidecar and row: contract shape, owner's unit, manual source, bands, one drive per 30 min gap.
row() { python3 -c 'import json,sys; [print(json.loads(l)[sys.argv[2]]) for l in open(sys.argv[1]) if sys.argv[3] in l]' "$S/index.jsonl" "$1" "$2"; }
side() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"; }
fan=$(sha256sum "$S/inbox-done/20261001-151517-fan-high.wav" | cut -c1-64)
hwy=$(sha256sum "$S/inbox-done/20261001-181000-highway.wav" | cut -c1-64)
[ "$(row source "$city")" = recorder-manual ] || fail "city source: $(row source "$city")"
[ "$(row device "$city")" = rav4 ] || fail "city device: $(row device "$city")"
[ "$(row band "$city")" = city ] || fail "city band"
[ "$(row band "$fan")" = city-fan ] || fail "fan-high band: $(row band "$fan")"
[ "$(row band "$hwy")" = highway ] || fail "highway band"
[ "$(row tags "$fan")" = "['City', 'Fan']" ] || fail "fan-high tags: $(row tags "$fan")"
[ "$(row tags "$city")" = "['City']" ] || fail "city tags: $(row tags "$city")"
[ "$(row duration_s "$fan")" = 120.34 ] || fail "fan duration: $(row duration_s "$fan")"
[ "$(row drive "$city")" = "$(row drive "$fan")" ] || fail "city and fan-high are one drive"
[ "$(row drive "$city")" = rav4-2026-10-01T2213Z ] || fail "drive id: $(row drive "$city")"
[ "$(row drive "$hwy")" = rav4-2026-10-02T0110Z ] || fail "a 3 h gap is a new drive: $(row drive "$hwy")"
j=$S/2026-10-01/${fan:0:16}.json
[ "$(side "$j" schema)" = road-noise/1 ] || fail "schema"
[ "$(side "$j" started_at)" = 2026-10-01T22:15:17Z ] || fail "started_at"
[ "$(side "$j" sample_rate)" = 48000 ] || fail "sample_rate"
[ "$(side "$j" mic_source)" = VOICE_COMMUNICATION ] || fail "mic_source"
[ "$(side "$j" sha256)" = "$fan" ] || fail "sidecar sha"
[ "$(side "$S/2026-10-01/${hwy:0:16}.json" received_at)" = 2026-10-01T22:24:00Z ] || fail "received_at is not the share arrival"

echo "== a second run, and the same take handed over again, file nothing twice"
cp "$S/inbox-done/20261001-151300-city.wav" "$S/inbox-done/20261001-151300-city.json" "$S/"
python3 "$FILE" "$S" > "$T/out" 2>&1 || fail "re-file exited $?: $(cat "$T/out")"
grep -q "road noise: filed 0, already 1" "$T/out" || fail "re-file summary: $(cat "$T/out")"
[ "$(wc -l < "$S/index.jsonl")" -eq 4 ] || fail "re-file doubled rows: $(cat "$S/index.jsonl")"
[ ! -e "$S/20261001-151300-city.wav" ] || fail "re-handed take left in the root"

echo "ROAD-NOISE PASS"
