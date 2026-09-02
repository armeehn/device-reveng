#!/bin/zsh
# Real iOS compile, unsigned: proves the app builds against the iOS SDK without
# needing a team. Output lands in build/.
set -e
cd "$(dirname "$0")/.."
xcodebuild -project ClaudeCar.xcodeproj -scheme ClaudeCar \
  -destination "generic/platform=iOS" -configuration Release \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build 2>&1 | tee build/xcodebuild.log | grep -E "error:|warning:|BUILD (SUCCEEDED|FAILED)" | grep -v "^ *|" | sort -u
