#!/usr/bin/env bash
# =====================================================================================
# Run the instrumentation suite, then print the accessibility audit's report.
#
# The audit (androidTest .../a11y/AccessibilityAuditTest) walks 35 screens and writes one
# line per finding to logcat and nowhere else. The emulator is killed the moment this step
# ends, so a later workflow step would have nothing left to read: the dump has to happen
# here, after Gradle, whatever Gradle's verdict was.
#
# One file rather than three lines in the workflow because the emulator action runs every
# line of a `script:` block in its own shell and stops at the first failure — `rc=$?` in a
# separate shell reads nothing, and a red test run would skip the dump entirely.
# =====================================================================================
set -uo pipefail

# The default ring buffer is 2 MB and the audit's report lands at the very end of a
# ~6 minute run; widened, nothing rotates out before the dump.
adb logcat -G 16M || true
adb logcat -c || true

cd "$(dirname "$0")/../../launcher"
./gradlew --no-daemon connectedDebugAndroidTest
rc=$?

echo "--- A11yAudit report ---"
adb logcat -d -s A11yAudit:I

exit $rc
