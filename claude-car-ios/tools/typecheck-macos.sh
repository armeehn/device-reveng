#!/bin/zsh
# Typecheck the app on a Mac that has only the Command Line Tools (no Xcode, so
# no iOS SDK). SwiftUI, AppIntents, Speech and Foundation all exist in the macOS
# SDK; ios-shim.swift stands in for the few iOS-only calls. This is how the
# "all files pass" claim in the README was produced — rerun it, don't trust it.
#
#   ssh srv-macos 'cd ~/claude-car-ios && tools/typecheck-macos.sh'
set -e
cd "$(dirname "$0")/../ClaudeCar"
swiftc -typecheck \
  -sdk "$(xcrun --show-sdk-path)" \
  -target x86_64-apple-macos14.0 \
  -swift-version 5 \
  -module-name ClaudeCar \
  ClientConfig.swift ChatModel.swift Backend/*.swift Net/*.swift Intents/*.swift UI/*.swift \
  ../tools/ios-shim.swift
echo "typecheck OK"
