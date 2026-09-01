#!/usr/bin/env bash
# Downloads the GT6-EAU / QCM6125 root files from the XDA mega-thread (mega.nz).
# You run this yourself so it can install a mega client and (if needed) use sudo.
set -uo pipefail

DEST="${RAV4_HOME:-$HOME/rav4-headunit}/thread-dl"
mkdir -p "$DEST"
cd "$DEST"

# --- the three mega.nz files (label -> url) ---
URLS=(
  "FireHose-loader+QPST-bundle|https://mega.nz/file/995z2T5D#P6AZf_MPU4B_o_1pxrajX5oyiz5j07RxiL91tWARzko"
  "TWRP-variant-A|https://mega.nz/file/B44RhARA#E4xKM-fwaS_AnL1AyB3e4mRJee0xMnpP_6nwrJYEFN4"
  "TWRP-variant-B|https://mega.nz/file/0gZhEISL#bklYb1mh0wmK0aZ0tdPCiW-jCypaj7kx0ASeHvOhAGc"
)

echo ">> Destination: $DEST"

# --- ensure a mega client (megatools) is available ---
DL=""
if command -v megatools >/dev/null 2>&1; then DL="megatools dl"
elif command -v megadl   >/dev/null 2>&1; then DL="megadl"
else
  echo ">> megatools not found. Installing (needs sudo)..."
  if   command -v pacman >/dev/null 2>&1; then sudo pacman -S --needed --noconfirm megatools
  elif command -v apt    >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y megatools
  elif command -v dnf    >/dev/null 2>&1; then sudo dnf install -y megatools
  else echo "!! No known package manager. Install 'megatools' manually, then re-run."; exit 1
  fi
  if   command -v megatools >/dev/null 2>&1; then DL="megatools dl"
  elif command -v megadl   >/dev/null 2>&1; then DL="megadl"
  else echo "!! megatools still not available after install."; exit 1
  fi
fi
echo ">> Using downloader: $DL"

# --- download each file ---
fail=0
for entry in "${URLS[@]}"; do
  label="${entry%%|*}"; url="${entry#*|}"
  echo; echo "==== $label ===="
  # megatools keeps the file's real name; --path . puts it here. Retry once.
  if $DL --path "$DEST" "$url" || $DL "$url"; then
    echo ">> OK: $label"
  else
    echo "!! FAILED: $label ($url)"; fail=1
  fi
done

echo; echo "==== downloaded files ===="
ls -la "$DEST"
echo
if [ "$fail" = 0 ]; then
  echo ">> All done. Files are in $DEST; extract the loader and identify the TWRP variants next."
else
  echo ">> Some downloads failed — you can also just grab those links in a browser into $DEST."
fi
