# ZLink (com.zjinnova.zlink) Native Library Analysis

Static analysis of 53 `armeabi-v7a` (ARM 32-bit, some ARM64) `.so` files from the ZLink
car-head-unit mirroring receiver. Tools used: `strings`, `nm -D`, `readelf`, `file`,
`objdump`, `grep`. No code was executed. (No ghidra/radare2/rizin present; not installed.)

Authorized interoperability/security research on the device owner's own head unit.

---

## TL;DR — most important findings

- **Apple CarPlay is real, not emulated auth.** `libCoreUtils.so` is a port of Apple's
  AirPlay/CarPlay "Communication Plug-in" (CoreUtils) reference source. It contains the full
  **MFi-SAP + SRP Pair-Setup / Pair-Verify** state machine (`MFiSAP_Exchange`,
  `MFiPlatform_CopyCertificate`, `MFiPlatform_CreateSignature`, `Pair-setup client M1..M6`,
  `ed25519`, `curve25519`, `_mfi-config._tcp`). Actual MFi certificate/signature operations
  are delegated to an MFi coprocessor via `MFiPlatform_*` — i.e. it expects a **real MFi auth
  chip** on the head unit; there is no bundled private key that would let us fake it.
- **Wired CarPlay over USB uses a Carbit-branded usbmuxd fork** (`com.carbit.usbmuxd`,
  `carbitusbmuxd`) speaking Apple lockdown (`com.apple.mobile.lockdown`,
  `request_pair`, `request_host_buid`, PairRecord). Writes `/data/local/tmp/usbmuxd.pid`.
- **Huawei HiCar confirmed** — this is the official Huawei **HiCar SDK 2.0.0.310** /
  **DMSDP ("Distributed Mobile Sensing Data Platform") protocol DMSDP/1.0 v1.0.0.302**.
  Build path leaks a Huawei dev tree: `.../hicarsdk_2_0_0_310_release/dvkit/...`. Uses
  BoringSSL-FIPS-20191020 and "Huawei Secure C V100R001C01SPC010B002".
- **Baidu CarLife confirmed** — `libzjcarlife.so` embeds the full Baidu CarLife protobuf
  schema (`com.baidu.carlife.protobuf.*`).
- **Android Auto / AOA mirroring** also present in the master engine (`libzjL10001.so`:
  `Android Auto rfcomm`, `aoa_mirror_*`, AOA = Android Open Accessory).
- **Telemetry / phone-home:** the only real external endpoint is Tencent **Bugly** crash
  reporting → **`bugly.qq.com`** (`libBugly.so`, `libBugly-ext.so`) plus Tencent **MTA**
  native crash (`libMtaNativeCrash_v2.so`). Vendor app-update URL
  `http://url.zjinnova.com/download_zlink5_android_app`. **No hidden tracking domains** —
  every other URL is a toolchain/spec/test string (httpbin.org, apple.com examples, RFC IPs).
  NOTE: `libapm.so` is **not** telemetry; it is a WebRTC Audio Processing Module (AEC).
- **Packer:** Bangcle/SecNeo **SecShell**, loader `libshella-4.6.2.2.so` (v4.6.2.2) +
  `libshell-super.com.zjinnova.zlink.so` (build tag `libshell-super.2019.so`; clang fork from
  Tencent's `git.code.oa.com/SecurityResearchProject/ANTI-Reverse.git`). The Java/DEX is packed.
- **JNI surface** (the Java↔native API, gold because DEX is packed) is fully recovered below —
  81 `Java_*` exports across 7 libs, all under `com.zjinnova.*`.
- **Root required on the head unit.** `libzjL10001.so` shell-copies libs into `/dev/`
  (`/dev/z-usbmuxd`, `/dev/z-dhcpc`, `/dev/zjinnova_iap`, `/dev/zjinnova_iap2`) and
  broadcasts `zjinnova.*.START_ZLINK_SERVICE` intents — it runs privileged.

---

## 1. Apple CarPlay / AirPlay group

Derived from **Apple's AirPlay/CarPlay reference source (CoreUtils / AirPlay Communication
Plug-in)**. Evidence is overwhelming and specific.

| Lib | Size | Role |
|---|---|---|
| `libCoreUtils.so` | 1.76 MB | Apple CoreUtils port: MFi-SAP, SRP pairing, mDNS, HTTP/RTSP utils |
| `libAirPlay.so` | 273 KB | CarPlay/AirPlay session + ScreenStream setup |
| `libAirPlaySupport.so` | 13 KB | ScreenStream/audio helper shim |
| `libzjAirPlay.so` | 2.89 MB | Vendor CarPlay engine (bundles FDK-AAC, ed25519, usbmux, RTSP) |
| `libAudioStream.so` | 17 KB | CarPlay audio stream |
| `libAudioConverter.so` | 13 KB | AAC/Opus converter shim |
| `libScreenStream.so` | 9 KB | H264 screen stream |
| `libusbmuxd.so` | 2.15 MB | **Carbit** usbmuxd fork — wired CarPlay over USB |
| `libdns_sd.so` | 33 KB | Bonjour/DNS-SD client shim (`_dns-sd._udp`) |
| `libzmdnsd.so` | 427 KB | mDNS responder daemon (`_dns-sd._udp`, `_dns-update._udp`) |

**MFi / authentication reality** (all from `libCoreUtils.so` unless noted):
- `MFiSAP_Create/Exchange/Encrypt/Decrypt/CopyCertificate/Delete`, `_MFiSAP_Exchange_ServerM1`
- `MFiPlatform_Initialize / CopyCertificate / CreateSignature / Finalize` — the platform hook
  that talks to a **hardware MFi coprocessor** ("cp protocal: MFiPlatform_CopyCertificate",
  "MFi auth copy certificate failed").
- SRP Pair-Setup/Pair-Verify 6-message state machine: `Pair-setup client M1 -- start request`
  … `M6`, `Pair-Setup-Accessory-Sign-Info/Salt`, `MFi-Pair-Setup-Info/Salt`.
- Crypto primitives: `ed25519`, `curve25519`, `SRP` (51 hits), `MFiSAPVersion1`, `kMFiSAP_ECDHKeyLen`.
- `libzjAirPlay.so`: `ed25519` (15), `fairplay` (10), `SRP`, `pair-verify`, `_raop`, `RTSP`, `ANNOUNCE`.
- **Interpretation:** authentication is genuine Apple MFi-SAP. The device must have (or emulate)
  an MFi authentication IC — the code fetches the cert/signature from `MFiPlatform_*`, it is not
  hardcoded. A replacement receiver would need the same MFi chip or Apple's keys.

**Bonjour / mDNS service types found:** `_airplay._tcp`, `_raop._tcp`, `_carplay-ctrl._tcp`,
`_carplay`, `_mfi-config._tcp`, `_hap._tcp`, `_airport._tcp`, `_http._tcp` (CarPlay + HomeKit/HAP + AirPlay v2/RAOP).

**USB (wired CarPlay) — `libusbmuxd.so`:** Apple lockdown protocol confirmed:
`com.apple.mobile.lockdown`, `...lockdown.request_pair`, `...request_host_buid`,
`com.apple.mobile.notification_proxy`, `com.apple.mobile.iTunes`, `DeletePairRecord`,
`could not connect to lockdownd`, `/data/local/tmp/usbmuxd.pid`. Branding
`com.carbit.usbmuxd` / `carbitusbmuxd` → it's **Carbit's** usbmuxd, not upstream libimobiledevice.
(No literal `62078` string, but the lockdownd pairing flow is fully present.)

**Codecs / DSP:**
- Audio: **AAC via FDK-AAC** — two copies: `libfdk-aac.so` (1.12 MB, the codec) and
  `libfdk_aac.so` (719 KB, JNI wrapper `com_zjinnova_jni_FdkAacDecoder`); `libzjAirPlay.so`
  has 315 "FDK" hits. **Opus** and **ALAC/AAC** referenced in CoreUtils/AudioConverter
  (`opus` 50 hits, `AAC`). AirPlay audio = ALAC/AAC/Opus per Apple stack.
- Video: **H.264** (`H264`/`H.264` in AirPlay, ScreenStream, CoreUtils).
- Echo cancellation (multiple engines): `libwebrtc_apm.so` (1.55 MB, WebRTC **AEC3**),
  `libblinkAEC.so` (1.01 MB, "blinkAec"), `libspeexdsp.so` (67 KB, Speex), and
  `libapm.so` (1.08 MB, WebRTC APM tunables `aec_delay/aec_enable/aec_highpass`).
  Fed by `libzjaudio_jni.so` (`com_zjinnova_jni_Zaec_*` — Init/Proc/Denit AEC/AECM/AGC/NS/RESAM).

---

## 2. Huawei HiCar / DMSDP group — CONFIRMED

Official **Huawei HiCar SDK, version 2.0.0.310** (`hicarsdk_2_0_0_310_release`).
Underlying transport is Huawei **DMSDP** (Distributed Mobile Sensing Data Platform),
protocol banner **`DMSDP/1.0`**, lib version **1.0.0.302**.

| Lib | Size | Role |
|---|---|---|
| `libhicar.so` | 54 KB | HiCar service entry (`DMSDPCreateServiceHandle`, `DMSDPEventRegister`) |
| `libdmsdp.so` | 288 KB | DMSDP core (RTP, AAC packetization, GPS, nearby channel, cJSON) |
| `libdmsdpdvaudio.so` | 17 KB | DMSDP distributed-virtual **audio** device |
| `libdmsdpdvcamera.so` | 54 KB | DV **camera** |
| `libdmsdpdvgps.so` | 17 KB | DV **GPS** |
| `libdmsdpdvdevice.so` | 13 KB | DV device descriptor |
| `libdmsdpdvinterface.so` | 21 KB | DV interface registry |
| `libdmsdphisight.so` | 21 KB | HiSight (screen projection) provider |
| `libdmsdpaudiohandler.so` | 17 KB | Audio handler |
| `libdmsdpplatform.so` | 58 KB | Platform threading/utils |
| `libdmsdpcrypto.so` | 136 KB | BoringSSL-FIPS-20191020 crypto |
| `libdmsdpsec.so` | 46 KB | "Huawei Secure C V100R001C01SPC010B002" |
| `libHisightSink.so` | 784 KB | HiSight **sink** (receives phone screen): `HiSightManager::ConnectDevice/Play/Pause` |
| `libHwDeviceAuthSDK.so` | 178 KB | Huawei device auth: SPEKE/PAKE handshake (`is_peer_support_speke_version`, Pake Request/Response) |
| `libHwKeystoreSDK.so` | 354 KB | HUKS keystore (`hks_get_sdk_version`) |
| `libauthagent.so` | 51 KB | Auth agent bridging nearby ↔ DeviceAuth ↔ keystore |
| `libnearby.so` | 2.14 MB | Huawei "Nearby" discovery/transport (softbus-style session API) |
| `libsecurec.so` | 46 KB | Huawei Secure C runtime |
| `libmanagement.so` | 38 KB | Device management helper |

**Auth mechanism:** Huawei DeviceAuth SDK using **PAKE/SPEKE** key agreement
(`Parse Pake Request/Response`, `is_peer_support_speke_version`, `minVersion/currentVersion`
negotiation), backed by HUKS (`libHwKeystoreSDK`) and BoringSSL FIPS. Build path leak:
`/home/y00499941/ywx1099599/hicarsdk_2_0_0_310_release/dvkit/dvkit/third_party/boringssl/boringssl-fips-20191020/...`

---

## 3. Baidu CarLife — CONFIRMED

| Lib | Size | Role |
|---|---|---|
| `libzjcarlife.so` | 2.87 MB | Baidu CarLife engine + full protobuf schema |
| `libzbt_core.so` | 157 KB | Bluetooth/BLE + HiCar RFCOMM core (JNI `com_zjinnova_jni_Zbt_*`) v9.0.8 |
| `libzbt-main.so` | 112 KB | `(carlife)` BT main (32-bit) |
| `libzbt-main-64.so` | 130 KB | `(carlife)` BT main (64-bit) |

Embeds Baidu CarLife protocol buffers: `com.baidu.carlife.protobuf.CarlifeFeatureConfig`,
`CarlifeTouchEventDevice`, `CarlifeCallRecords`, `CarlifeContacts`, `CarlifeMediaInfo`,
`CarlifeModuleStatus`, `CarlifeBTHfpStatusRequestProto`, etc. URL `http://carlife.baidu.com/`.
`libzbt_core.so` reports version **9.0.8**. Note `libzbt-main*` are dropped to
`/data/local/tmp/` at runtime (see §7 root behavior).

---

## 4. DLNA — `libzj_dlna.so` (225 KB)

Standard **UPnP/DLNA MediaRenderer** stack (looks Platinum/GUPnP-derived). Evidence:
`urn:schemas-upnp-org:metadata-1-0/AVT`, `AVTransportURI`, `<DIDL-Lite>`, SOAP
envelope/encoding, SSDP sockets (`gSsdpReqSocket4/6`, `get_ssdp_sockets`), `UPnPError`.
Vendor friendly-name domain `http://www.zjinnova.com`. This is the "cast a video to the
head unit" path, independent of the phone-mirroring protocols.

---

## 5. Telemetry / phone-home

| Lib | Size | What it is | Endpoint |
|---|---|---|---|
| `libBugly.so` | 166 KB | Tencent **Bugly** crash/NDK reporting | **bugly.qq.com** |
| `libBugly-ext.so` | 170 KB | Bugly extended (signal handler) | **bugly.qq.com** |
| `libMtaNativeCrash_v2.so` | 26 KB | Tencent **MTA** native crash (`com/tencent/stat/StatNativeCrashReport`, `mta.so`) | (via MTA SDK) |
| `libmmkv.so` | 326 KB | Tencent **MMKV** key-value store (local, v1.2.7 / core 5.0.300080) — not network | local only |
| `libapm.so` | 1.08 MB | **NOT telemetry** — WebRTC Audio Processing Module (AEC/AGC/NS) | n/a |

**Complete list of every real http(s) URL / external domain across all 53 libs**
(toolchain, W3C/XML-spec, and in-code test URLs excluded as noise where noted):

- `bugly.qq.com` — Tencent Bugly crash reporting (`libBugly.so`, `libBugly-ext.so`) **[telemetry]**
- `http://url.zjinnova.com/download_zlink5_android_app` — vendor app self-update (`libzjL10001.so`)
- `http://www.zjinnova.com` — vendor / DLNA friendly name (`libzj_dlna.so`, `libzjL10001.so`)
- `http://carlife.baidu.com/` — Baidu CarLife (`libzjL10001.so`)
- Toolchain/spec noise: `android.googlesource.com/...` (clang/LLVM banners), `libusb.info`,
  `www.openssl.org/docs/faq.html`, `w3.org`, `xmlsoap.org`, `purl.org`, `apple.com/DTDs`,
  `dns-sd.org/ServiceTypes.html`, `ffmpeg.org/incoming` (FFmpeg build banner).
- In-code **test** strings (CoreUtils URL-parser unit tests, not live traffic):
  `httpbin.org/...`, `www.apple.com/...`, `bj.apple.com`, `joe:secret@www.host.com`,
  `wwdcdemo.example.com`, `abc.com/test?x#y`, `10.0.20.1`.
- `git.code.oa.com/SecurityResearchProject/ANTI-Reverse.git` — compiler-fork banner in the
  packer (build metadata, not a runtime callback).

The "…boot.com" hits an earlier regex flagged were **false positives** — they are
`sys.boot_completed` / `chekexun.boot.completed` property strings, not domains.
IPs seen (`17.205.x`, `17.251.x` = Apple ranges; `129.144.52.38`, `192.0.2.62`, `999.2.3.4`,
`114.114.114.114`) are Apple sample addresses / RFC-5737 examples / the CoreUtils test harness —
not hardcoded C2. **No covert tracking infrastructure found.**

---

## 6. The packer — Bangcle/SecNeo SecShell

- `libshella-4.6.2.2.so` (6 KB) — SecShell **loader/stub, version 4.6.2.2** (classic
  Bangcle/SecNeo "libshella-x.y.z" naming). `file`: "missing section headers".
- `libshell-super.com.zjinnova.zlink.so` (258 KB) — the per-app **SecShell unpacker**
  (SecNeo "shell-super"). Build tag string `libshell-super.2019.so`; reads
  `ro.build.version.release/sdk`, `tosversion`. Compiled with a customized clang from
  Tencent's `git.code.oa.com/SecurityResearchProject/ANTI-Reverse.git` (anti-reverse
  toolchain). Confirms the app's **DEX/Java is encrypted & runtime-unpacked** — hence the
  native JNI symbol table below is the best available view of the app's internal API.

---

## 7. zj* vendor core libs + full JNI surface

### Master engine
- **`libzjL10001.so`** (2.97 MB) — the central multi-protocol mirroring engine. Orchestrates
  **CarPlay, Baidu CarLife, HiCar, Android Auto, and AOA mirroring** in one binary. Contains
  `Android Auto rfcomm`, `AndroidAutoStart` (`libusb_open_device_with_vid_pid`),
  `aoa_mirror_start/stop/loop_start/touch_event`, `bluetooth/proto/zbt_hicar_ble_start.pb-c.c`.
  "L10001" is a **product/license code** (cf. `getOfflineProductCode`).
  **Runs as root:** shell snippets copy libs to `/dev/` and `/data/local/tmp/` and broadcast
  service intents:
  - `cp .../libusbmuxd.so /dev/z-usbmuxd`, `.../libdhcpc.so /dev/z-dhcpc`,
    `.../libznetshare.so /dev/z-netshare`, char devices `/dev/zjinnova_iap`, `/dev/zjinnova_iap2`
  - `am broadcast -a "zjinnova.intent.action.START_ZLINK_SERVICE" ... com.zjinnova.zlink`
  - Companion package `com.zjinnova.netshare` (tethering/DHCP for wireless CarPlay/AA).
- **`libzjdhcpc.so`** (47 KB) — DHCP client for the Wi-Fi-Direct/AP link used by wireless mirroring.

### Full JNI export table (`nm -D | grep Java_`) — 81 symbols, all `com.zjinnova.*`

**`libzlink_core.so`** (124 KB) — `com.zjinnova.android.zlink.core.utils.ZlinkCore` (43):
```
getAADensity getAecDelay getAecNoise getAecType getBtMac getBtName getBtType
getChipActivationInfo getChnId getChnName getCmdAction getCmdMessage getCmdMessageWithArgs
getDarkMode getDefaultConnMode getDisabledFeatures getDriverPos getHeadDelay getHwId
getMicType getOfflineProductCode getPersistentStatesResetCmdMsg getPhoneOsVersion getPipStatus
getPlatformLicenceDir getPlatformName getSupportWirelessLinkTypes isAccOn isAllowBgConn
isAllowHiCarUsbConnect isAudioPlayedFromPhone isFitAndroidMirrorNewVersion isHuAec
isPersistentStatesReset setActivationKey setActivationResult setBtName setHwId setMfiId
updateDriverPos updateLinkType verifyHiCarFreeEnv verifyMirrorFreeEnv
```
(Note `setMfiId`, `setActivationKey/Result`, `getChipActivationInfo`, `verifyHiCarFreeEnv`,
`verifyMirrorFreeEnv` → licensing/activation gate the mirror & HiCar features.)

**`libzlink.so`** (104 KB) — `com.zjinnova.jni.AudioEngine` (5):
`nativeInit nativeStart nativeStop nativeWrite nativeDestroy`

**`libzbt_core.so`** (157 KB) — `com.zjinnova.jni.Zbt` (12) — Bluetooth/BLE + HiCar RFCOMM:
```
initZbt deinit btInfo phoneLinkState
hiCarSendHuData hiCarSendPhoneData initHiCarServiceCallBack requestInitHiCarBtStatues
sendHiCarRfcommState requestInitBleStart requestInitBleStop requestInitBtEnableCallBack
```

**`libzjaudio_jni.so`** (424 KB) — `com.zjinnova.jni.Zaec` (15) — echo cancel/AGC/NS/resample:
`InitAEC ProcAEC DenitAEC` + AECM + AGC + NS + RESAM variants.

**`libaudio_core.so`** (6 KB) — `com.zjinnova.android.zlink.core.utils.AudioEngine` (3):
`init denoise destroy`

**`libfdk_aac.so`** (719 KB) — `com.zjinnova.jni.FdkAacDecoder` (3):
`createFdkAacDecoder fdkAacDecode releaseFdkAacDecoder`

**`libMtaNativeCrash_v2.so`** (26 KB) — `com.tencent.stat.StatNativeCrashReport` (4):
`initJNICrash enableNativeCrash enableNativeCrashDebug makeJniCrash`

---

## 8. Version strings summary

| Lib | Version evidence |
|---|---|
| Packer loader | `libshella-4.6.2.2.so` → SecShell v4.6.2.2 |
| HiCar SDK | `hicarsdk_2_0_0_310_release` → 2.0.0.310 |
| DMSDP | `DMSDP/1.0`, lib 1.0.0.302 |
| HiCar crypto | BoringSSL-FIPS-20191020; Huawei Secure C V100R001C01SPC010B002 |
| Baidu CarLife BT | `libzbt_core.so` → 9.0.8 |
| MMKV | v1.2.7 (core 5.0.300080) |
| libnearby | 1.0.2.001 |
| MFi-SAP | kMFiSAPVersion1 |
| Compiler banners | clang 7.0.2 (Android r328903); packer clang 3.8 |

---

## 9. Implications for building a replacement receiver

1. **CarPlay:** you need the MFi-SAP + SRP flow and an MFi auth IC (or Apple keys). The
   `MFiPlatform_*` seam shows exactly where the hardware cert/signature is injected — that is
   the single hard dependency, not the protocol logic (which is standard Apple CoreUtils).
2. **Wired CarPlay:** requires a usbmuxd/lockdownd host implementation (Carbit's here). Wireless
   CarPlay/AA additionally needs the Wi-Fi AP + DHCP (`libzjdhcpc`, `com.zjinnova.netshare`).
3. **HiCar:** gated by Huawei DeviceAuth (PAKE/SPEKE) + HUKS; you'd need Huawei's HiCar SDK/keys.
4. **CarLife:** open-ish protobuf protocol (`com.baidu.carlife.protobuf.*`) — most tractable to reimplement.
5. **Licensing:** `ZlinkCore.setActivationKey/getChipActivationInfo/verify*FreeEnv` indicate a
   per-chip activation/licensing scheme that also gates HiCar & mirroring.
6. **Root:** the current stack runs privileged (drops libs into `/dev/`, `/data/local/tmp/`,
   broadcasts system intents) — reflect this in any clean-room design.
7. **Privacy:** only outbound telemetry is Tencent Bugly (`bugly.qq.com`) + MTA crash stats,
   plus a vendor update check to `url.zjinnova.com`. No covert exfiltration domains observed.
