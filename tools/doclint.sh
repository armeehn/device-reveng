#!/usr/bin/env bash
# Lint the repository's Markdown for what rots in a public repo: internal host names, share
# paths and tracker ids that belong to the estate, not the project; relative links to files
# that do not exist; a word the project does not use; counts that drifted. Exit 1 on any hit
# so CI keeps the fixed point.
set -uo pipefail
cd "$(dirname "$0")/.."

readonly SUITE_APPS=28
bad=0
files=$(git ls-files '*.md' | grep -v '^rav4-apps/')

hit() { echo "$1"; bad=$((bad + 1)); }

# 1. Estate internals. Host names, LXC numbers, the share, the tracker, home-network addresses.
pat='launcher\.hq|\.hq\.ripostelabs|\bLXC [0-9]+|\bLXC\b|share/carlauncher|/z1-pool|\bPlane\b|\bRAV4-[0-9]+\b|sasha@(x|zero|forge|100)|10\.0\.(1|10)\.[0-9]+|100\.[0-9]+\.[0-9]+\.[0-9]+|\bvile\b|\bforge\b|\(zero\)|\bml350p\b'
while IFS= read -r line; do hit "internal: $line"; done < <(grep -n -E "$pat" $files | cut -c1-160)

# 2. The word. Merged, arrives, is in, opens on: anything but this.
while IFS= read -r line; do hit "word: $line"; done < <(grep -n -w -i -E "land|lands|landed|landing" $files | grep -v 'zlink\.land' | cut -c1-160)

# 3. Relative links that point nowhere (anchors and URLs skipped).
for f in $files; do
  d=$(dirname "$f")
  grep -o '\]([^)]*)' "$f" | sed 's/^](//; s/)$//' | grep -v -E '^(https?:|mailto:|#)' | sed 's/#.*//' | sort -u | while read -r p; do
    [ -z "$p" ] && continue
    [ -e "$d/$p" ] || echo "link: $f -> $p"
  done
done > /tmp/doclint-links.$$
while IFS= read -r line; do hit "$line"; done < /tmp/doclint-links.$$
rm -f /tmp/doclint-links.$$

# 4. Counts that drift: the suite has SUITE_APPS apps.
while IFS= read -r line; do hit "count: $line"; done < <(grep -n -E '\b(2[0-7])[- ]app\b|\b(2[0-7]) (companion |suite )?apps\b' $files | grep -v "$SUITE_APPS" | cut -c1-160)

if [ "$bad" = 0 ]; then echo "DOCLINT PASS ($(echo $files | wc -w) files)"; else echo "DOCLINT: $bad finding(s)"; exit 1; fi
