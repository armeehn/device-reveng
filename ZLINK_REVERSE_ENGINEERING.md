# ZLink reverse-engineering — how it works, and how to build a better one

Target: **`com.zjinnova.zlink` v5.4.62** (versionCode 50462), the phone-mirroring
"receiver" app on the GT6-CAR head unit (Qualcomm QCM6125 "trinket", Android 13).
Vendor: **Zjinnova / 智简 ("Zhijian")**. Extracted offline from `super.bin`
(`/system/priv-app/zlink5/zlink5.apk`, 40.4 MB) — no device needed.

Companion doc: **`ZLINK_NATIVE_ANALYSIS.md`** (per-library protocol/JNI/telemetry breakdown).
This is authorized interoperability research on the owner's own hardware.

---

## TL;DR

- ZLink is a **multi-protocol receiver**: Apple **CarPlay**, **Android Auto**, Huawei
  **HiCar**, Baidu **CarLife**, plus generic **Mirror/DLNA** cast. Each mode is a separate
  launcher Activity backed by its own native stack.
- The Java is **packed with Bangcle/SecNeo SecShell 4.6.2.2**. Static decompile yields only the
  13-class unpacking stub. **The real protocol logic is in ~53 unpacked native `.so`
  libraries** — that's where the value is, and it's fully readable. We recovered **81 `Java_*`
  JNI exports** (the real Java↔native API map) despite the packer.
- CarPlay here is **genuine, not faked**: `libCoreUtils.so` is a port of **Apple's AirPlay/
  CarPlay CoreUtils** with a full **MFi-SAP + SRP Pair-Setup/Pair-Verify** state machine, and
  the board carries a real **Apple MFi authentication coprocessor on an I2C bus**
  (`persist.zj.checkmfi.exist`, `persist.sys.mfi.index`, `persist.zj.mfi.channel`).
- There's a **cloud-tied per-device activation/license gate** (`ActivationActivity`, QR /
  activation-code, "activation code has been used", requires Internet;
  `ZlinkCore.setActivationKey`/`getChipActivationInfo`). This is Zjinnova's DRM and the single
  best reason to build our own.
- Telemetry: **Tencent Bugly** (`bugly.qq.com`) + Tencent MTA native crash only. No covert C2.
  Self-update pulls `http://url.zjinnova.com/download_zlink5_android_app`. All droppable.

---

## App structure (from the decodable AndroidManifest)

- Package `com.zjinnova.zlink`; real code namespace `com.zjinnova.android.zlink.features.*`.
- `targetSdkVersion=23` (keeps legacy install-time permissions + pre-scoped-storage behavior),
  compiled against SDK 33.
- Ships as a **privileged system app** (`/system/priv-app`) — *required*, because it holds
  signature/privileged permissions a normal app cannot get:
  - Wi-Fi/AP: `OVERRIDE_WIFI_CONFIG`, `TETHER_PRIVILEGED`, `NETWORK_SETTINGS`, `CHANGE_WIFI_STATE`
  - Bluetooth: `BLUETOOTH_PRIVILEGED`, `BLUETOOTH_CONNECT/SCAN/ADVERTISE`
  - Telephony/system: `READ_PRIVILEGED_PHONE_STATE`, `CALL_PHONE`, `READ_LOGS`,
    `LOCAL_MAC_ADDRESS`, `SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`,
    `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`
  - Custom perm `zjinnova.android.permission.ZLINK_SERVICE` guards its exported IPC.
- A companion package **`com.zjinnova.netshare`** provides tethering/DHCP for the wireless links.

### Modes (each = a launcher Activity + an "enable" action)
| Mode | Activity | Enable action | Native stack |
|------|----------|---------------|--------------|
| Apple CarPlay | `CarPlayActivity`, `CarPlayAutoActivity` | `CarPlayEnable` | CoreUtils/AirPlay + MFi-SAP + usbmuxd + mDNS |
| Android Auto | `AutoActivity` (`AutoUnavailableActivity`) | — | AOA mirror in `libzjL10001.so` (BT-RFCOMM → TCP) |
| Huawei HiCar | `HiCarActivity` | `HiCarEnable` | HiCar SDK 2.0.0.310 / DMSDP 1.0 (`libdmsdp*`, `libhicar`) |
| Baidu CarLife | `CarLifeActivity` | `CarLifeEnable` | `libzjcarlife` (Baidu protobuf), `libzbt_core` v9.0.8 |
| Screen Mirror | `MirrorActivity` | `MirrorEnable` | AirPlay-mirroring / cast |
| DLNA | `DlnaActivity` | — | `libzj_dlna` (UPnP MediaRenderer) |

### Services / lifecycle
- `DaemonService` — keep-alive/watchdog (the "always restarts" behavior).
- `SecondaryScreenService` — the mirrored `Presentation` display surface.
- `BluetoothService`, `BluetoothHCTService`, `ZBTService` — BT bootstrap for wireless.
- `ActivationActivity` / `CheckPermissionsAndActivationActivity` — the license gate.
- `InstallUpgradeBroadcastReceiver`, `StartupBroadcastReceiver` — self-update + boot start.

---

## Integration contract with the head unit (what a drop-in replacement must honor)

The stock launcher/dashboard (`com.szchoiceway.customerui`) and `com.szchoiceway.eventcenter`
talk to ZLink two ways. To slot a custom app into the existing UI, reproduce **both**:

### 1. Broadcast/Intent API (control + status) — from `ZlinkManage.java`
- `com.zjinnova.zlink` — status. Extras: `command`, `status`, `phoneMode`, `phoneType`.
- `com.zjinnova.zlink.GET_DATA_REQ` → `...GET_DATA_RES`; `...WRITE_DATA` — key/value config
  read/write. Extras: `key`, `value`.
- `com.zjinnova.zlink.action.OUT_DARK_START` / `OUT_DARK_STOP` — screen blanking.
- `com.zjinnova.zlink.action.POWER_ON` / `POWER_OFF`.
- AIDL `ICallbackfn` with `onCarPlayMicStateChanged(int)` / `onHiCarMicStateChanged(int)` —
  mic routing for Siri/voice capture. Coordinates with `com.incarmedia.record`.
- Native side also fires `zjinnova.*.START_ZLINK_SERVICE` intents.

### 2. Localhost metadata socket (now-playing / nav) — from `ZLinkSocket.java`
- ZLink runs a **TCP server on `127.0.0.1:1555`**; the UI connects as client.
- Framing: **4-byte length header + protobuf payload**.
- Message type IDs: AndroidAuto — phone=1, next-turn=2, next-turn-distance=3, media-state=4,
  media-info=5; CarPlay — phone=6, comm-state=7, route-guidance=8, route-maneuver=9,
  media-info=10.
- Protobuf schemas in `zj.AA.*` (`MediaPlaybackMetadata`, `MediaPlaybackStatus`, `PhoneStatus`,
  `NavigationNextTurnEvent`, `NavigationNextTurnDistanceEvent`) — recoverable from the
  customerui dex; feeds the cluster/dashboard now-playing + turn-by-turn.

---

## How each protocol actually works here (confirmed from native libs)

- **Apple CarPlay/AirPlay** — `libCoreUtils.so` (1.76 MB) is Apple's AirPlay/CarPlay
  "Communication Plug-in" (CoreUtils). Full **MFi-SAP** + **SRP Pair-Setup/Pair-Verify M1..M6**,
  `ed25519`/`curve25519`. mDNS service types `_airplay._tcp`, `_raop._tcp`,
  `_carplay-ctrl._tcp`, `_mfi-config._tcp`, `_hap._tcp`. Audio: FDK-AAC, Opus, ALAC; video
  H.264. Echo cancel via `libwebrtc_apm.so` (the "APM" lib is WebRTC AudioProcessing, **not**
  telemetry) + Speex + `libblinkAEC`.
- **Wired CarPlay** — `libusbmuxd.so` is a **Carbit usbmuxd fork** (`com.carbit.usbmuxd`)
  speaking Apple lockdown (`request_pair`, `request_host_buid`, PairRecord) to the iPhone over
  USB. `libzjL10001.so` shell-copies helper nodes into `/dev/z-usbmuxd`, `/dev/zjinnova_iap`.
- **Android Auto** — the master engine `libzjL10001.so` has `aoa_mirror_*` + "Android Auto
  rfcomm": wireless AA = BT-RFCOMM handshake, then TCP over the unit's AP.
- **Huawei HiCar** — official **HiCar SDK 2.0.0.310** over **DMSDP/1.0** transport; auth via
  Huawei DeviceAuth PAKE/SPEKE + HUKS (`libHwDeviceAuthSDK`, `libHwKeystoreSDK`), BoringSSL-FIPS.
- **Baidu CarLife** — `libzjcarlife.so` embeds `com.baidu.carlife.protobuf.*`; BT core v9.0.8.
- **DLNA** — `libzj_dlna.so`, standard UPnP MediaRenderer.

### The MFi/CarPlay reality (revises earlier CARPLAY.md)
Apple's *licensing program* is closed to hobbyists — but this board **already has the MFi
coprocessor**, and `libCoreUtils` already implements the Apple-side handshake. The cert/signature
is fetched at runtime through the `MFiPlatform_CopyCertificate/CreateSignature` seam (no private
key is bundled — it comes from the chip over I2C). So genuine CarPlay on *this* hardware is a
matter of driving the on-board chip, not of obtaining Apple code. Redistributing a CarPlay
receiver remains legally gray; personal use on owned hardware is the realistic scope.

---

## The packer (why you can't just jadx it)
- **Bangcle/SecNeo SecShell 4.6.2.2**: loader `libshella-4.6.2.2.so` +
  `libshell-super.com.zjinnova.zlink.so` (Tencent ANTI-Reverse clang fork). Entry
  `MyWrapperProxyApplication` → `com.wrapper.proxyapplication.WrapperProxyApplication` decrypts
  the real DEX at runtime. `assets/o0oooOO0ooOo.dat` (168 B) is the key table.
- `classes.dex`/`classes2.dex` are stubs; no real `com.zjinnova.android.zlink.*` Java present
  statically. Native JNI surface (81 `Java_*` exports) recovered instead — see native doc.
- **To recover the real Java** (optional — logic is mostly native): runtime DEX dump on the
  rooted unit (`frida-dexdump`, or dump decrypted regions from `/proc/<pid>/maps`). Needs the
  device online (currently offline — car). Device `GT6-CAR`; Wi-Fi ADB, re-enable wireless
  debugging each boot (port rotates).

---

## What "better" concretely means here
Our own receiver wins by *removing* ZLink's liabilities, not by out-engineering Apple:
1. **No activation/license gate** — works forever, survives OTA, no Internet check.
2. **No Tencent Bugly/MTA telemetry** — nothing phones home.
3. **No Bangcle packer** — smaller, faster cold start, maintainable.
4. **Better wireless bootstrap** — the flakiness is RF/pairing (see CARPLAY.md): pin 5 GHz,
   deterministic hotspot channel, faster BT reconnect, stronger reconnect loop.
5. **Modern UI** integrated with our Compose launcher (`./launcher`), honoring the `:1555`
   protobuf + `com.zjinnova.zlink*` broadcast contract so the cluster/dashboard keep working.
6. **Lower latency** — H.264 → MediaCodec surface; tune AEC via `persist.blinkbt.carplay.aecdelay`.

## Recommended path — **iPhone owner** (CarPlay is the only mode that matters)

Android Auto / HiCar / CarLife are irrelevant. CarPlay is also the *hardest* mode to build,
because ZLink already does it **genuinely** through Apple's CoreUtils + the on-board MFi chip.
So "better" is not "reimplement the receiver" — from-scratch would be strictly worse for a long
time. Three honest tiers:

### Tier 1 — "ZLink-minus" (recommended; days, achievable with root)
Keep ZLink's proven licensed CarPlay engine; strip its liabilities and fix the real pain.
- **Kill the phone-home / activation friction.** CarPlay is the activation-gated mode
  (`verifyMirrorFreeEnv`/`verifyHiCarFreeEnv` exist but there is **no CarPlay "free" path** —
  Mirror/HiCar run unlicensed, CarPlay requires activation tied to the MFi chip serial). On the
  already-activated OEM unit: block `www.zjinnova.com` / `com.zjinnova.net` / `bugly.qq.com`
  (hosts/iptables) so it can't de-activate, nag, or re-gate after an OTA; if a re-activation is
  ever forced, Frida-hook `ZlinkCore.getChipActivationInfo` / `setActivationResult` to
  short-circuit. Legitimate on your own OEM-installed app.
- **Strip telemetry** (Tencent Bugly / MTA) and stop the `DaemonService` respawn.
- **Fix the flakiness** (the actual complaint, per CARPLAY.md — RF/pairing, not protocol): pin
  the AP to a clean 5 GHz channel, set `persist.zj.need.reconnect`, and tune call/Siri mic via
  `persist.zj.aecdelay` / `aecheaddelay` / `aectype` / `cp.use.gocaec`.
- **Replace the UI**: swap ZLink's clunky connection/step activities for a clean Compose
  front-end that keeps the native CarPlay service, wired into our launcher (`./launcher`) via
  the `:1555` protobuf + `com.zjinnova.zlink*` broadcast contract.
→ Net result: a "better ZLink" for iPhone without touching Apple's protocol.

### Tier 2 — Carlinkit 5.0 / U2W dongle (most reliable, least effort, not "ours")
Bypasses the built-in receiver entirely. If the goal is rock-solid wireless CarPlay with zero
dev, this beats everything.

### Tier 3 — full DIY CarPlay receiver (months; legally gray; personal-use only)
We now have the **complete map** to attempt it: drive the on-board MFi chip with the exact I2C
functions in `libzlink*`/`libzjL10001` (`mfi_detect_i2c`, `MFi_Read_Certificate_i2c`,
`MFi_Write_ChallengeDataLen_i2c`, `MFi_Write_ChallengeData_i2c`, `MFiGetSignatureLen_i2c`,
`MUCMFi_CreateSignature`, `MUCMFi_CopyCertificate`; chip location via `persist.zj.mfi.channel` /
`persist.sys.mfi.index`), then AirPlay screen/audio streaming referencing OSS stacks
(UxPlay / openairplay / pyatv) against observed `libCoreUtils` behavior. A genuine research
project; recommended only as a hobby, not the primary path.

## Extracted artifacts (local, not in git — `*.apk` is gitignored)
- `mcu-analysis/apks/com.zjinnova.zlink.apk` — the 40 MB APK.
- `mcu-analysis/zlink-natives/` — 53 extracted `.so` libraries.
- `mcu-analysis/zlink-src/` — jadx output (stub only, confirms the packer).
