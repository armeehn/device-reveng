# AOSP platform test key

`platform.pk8` and `platform.x509.pem` are AOSP's public test keys, copied unchanged from
`platform/build/target/product/security` on android.googlesource.com. They are not a secret.

The GSI that Riposte OS 0.2+ runs on is signed with this key, so an APK signed with it may
declare `android:sharedUserId="android.uid.system"` (proven on the unit). The certificate's SHA-256 must be

    C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8

Check it with `openssl x509 -in platform.x509.pem -noout -fingerprint -sha256`.
`carservice/build.gradle.kts` turns the pair into a PKCS12 store under `build/` at configure time.
