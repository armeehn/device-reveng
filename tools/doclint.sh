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

# 5. Pages no other page links to. A page nobody can reach is a page nobody maintains.
for f in $files; do
  b=$(basename "$f")
  [ "$b" = README.md ] && continue
  n=$(grep -l -F "$b" $files 2>/dev/null | grep -v "^$f$" | wc -l)
  [ "$n" = 0 ] && hit "orphan: $f"
done

# 6. Prose sentences past 40 words. Lists of codes and numbers (key tables, mode lists) are
#    exempt: a third or more of the tokens carrying a digit or a backtick is a list, not prose.
while IFS= read -r line; do hit "long: $line"; done < <(python3 - $files <<'PY'
import re, sys
for p in sys.argv[1:]:
    paras, cur, incode, start = [], [], False, 0
    for i, l in enumerate(open(p, encoding="utf-8", errors="replace"), 1):
        t = l.rstrip("\n")
        if t.strip().startswith("```"):
            incode = not incode; continue
        if incode or not t.strip() or t.startswith("    ") or t.lstrip().startswith(("|", "#", "- ", "* ", "> ")) or t.strip().count("`") > 4:
            if cur: paras.append((start, " ".join(cur))); cur = []
            continue
        if not cur: start = i
        cur.append(t.strip())
    if cur: paras.append((start, " ".join(cur)))
    for start, para in paras:
        for s in re.split(r"(?<=[.!?])\s+", para):
            toks = s.split()
            codey = sum(1 for t in toks if any(c.isdigit() for c in t) or "`" in t) / max(1, len(toks))
            if len(toks) > 40 and codey < 0.35:
                print(f"{p}:{start}: {len(toks)} words: {s[:70]}...")
PY
)

if [ "$bad" = 0 ]; then echo "DOCLINT PASS ($(echo $files | wc -w) files)"; else echo "DOCLINT: $bad finding(s)"; exit 1; fi
