# Release signing

Release builds fall back to the **debug** key until the owner supplies a real
keystore. The debug keystore differs per machine and per CI runner, so two
builds of one commit can carry different signatures; installing one over the
other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and on a unit where the
launcher is HOME that leaves the car with no launcher until a manual uninstall.

## What the owner must do (once)

1. Create a keystore, kept off-repo forever:

   ```sh
   keytool -genkeypair -v -keystore carlauncher-release.jks \
     -alias carlauncher -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four GitHub Actions repository secrets
   (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_B64` | `base64 -w0 carlauncher-release.jks` output |
   | `RELEASE_KEYSTORE_PASS` | keystore password |
   | `RELEASE_KEY_ALIAS` | `carlauncher` (or your alias) |
   | `RELEASE_KEY_PASS` | key password |

3. For local release builds, export the same four variables in the shell.

With any of the four missing, everything behaves exactly as before (debug-key
fallback, honest warning in the release notes). With all four present,
`assembleRelease` signs with the real key — see the `signingConfigs` block in
`app/build.gradle.kts` and the `Build release and debug APKs` step in
`.gitea/workflows/launcher-ci.yml`.

## Switching an installed unit to the new key

The first real-key APK will not install over a debug-signed one. Uninstall the
launcher (pick another HOME first), then install the release APK. From then on
updates install normally on every machine and runner.

Never commit the keystore, its base64, or any of the passwords.
