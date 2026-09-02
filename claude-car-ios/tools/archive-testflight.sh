#!/bin/zsh
# Signed archive + upload to App Store Connect (TestFlight). Needs:
#   - a paid Apple Developer Program team, signed in under Xcode > Settings > Accounts
#   - DEVELOPMENT_TEAM in Local.xcconfig
#   - the bundle id com.ripostelabs.claudecar registered as an app in App Store Connect
set -e
cd "$(dirname "$0")/.."
xcodebuild -project ClaudeCar.xcodeproj -scheme ClaudeCar -configuration Release \
  -destination "generic/platform=iOS" -archivePath build/ClaudeCar.xcarchive \
  -allowProvisioningUpdates archive
xcodebuild -exportArchive -archivePath build/ClaudeCar.xcarchive \
  -exportOptionsPlist tools/ExportOptions.plist -exportPath build/export \
  -allowProvisioningUpdates
echo "uploaded to App Store Connect; it appears in TestFlight after processing"
