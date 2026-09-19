#!/usr/bin/env bash
# Build a full `super` image for the GT6-EAU from an image set, with the unit's own geometry
# (lpdump of the live super, 2026-09-18): 6 GiB, 3 metadata slots, virtual A/B, one group.
# Needed whenever an image is larger than the extents the last flash left (edl-extents.py
# refuses those): the whole super goes over EDL instead. Runs on the laptop (lpmake from
# android-tools).
#
# Usage: mksuper.sh IMAGE_DIR OUT.img       (IMAGE_DIR: system system_ext product vendor .img)
set -euo pipefail
D=$1; OUT=$2
readonly SUPER_BYTES=6442450944
readonly GROUP=qti_dynamic_partitions_b
readonly GROUP_BYTES=6438256640
readonly METADATA_BYTES=65536
readonly METADATA_SLOTS=3
readonly SLOT=b
cd "$D"
P=()
for p in system system_ext product vendor; do
  sz=$(stat -c %s "$p.img")
  P+=(--partition "${p}_$SLOT:readonly:$sz:$GROUP" --image "${p}_$SLOT=$p.img")
done
rm -f "$OUT"
lpmake --metadata-size $METADATA_BYTES --metadata-slots $METADATA_SLOTS --super-name super \
  --device super:$SUPER_BYTES --virtual-ab --group $GROUP:$GROUP_BYTES "${P[@]}" --output "$OUT"
stat -c %s "$OUT"
lpdump "$OUT" | grep -E "Name:|Header flags|slot count"
