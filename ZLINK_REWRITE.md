# Replacing `com.zjinnova.zlink` — specification from static analysis

_Unit: Choiceway GT6-EAU, Qualcomm QCM6125 (`ro.board.platform=trinket`), Android 13, rooted.
Subject: ZLink 5.4.62 (`versionCode 50462`), `/system/priv-app/zlink5/zlink5.apk`. Read-only inputs: the APK (manifest, resources, assets, 53 armeabi-v7a `.so`), the jadx
decompile (13 stub classes; the DEX is SecShell-packed), the vendor decompiles that talk to it,
and one `getprop`/logcat capture from the unit._ _Builds on `ZLINK_NATIVE_ANALYSIS.md` (library
inventory, JNI table), `OEM_SYSTEM.md` §com.zjinnova.zlink (the gateway bridge) and
`CARPLAY.md`. Not repeated here._

**Legend:** `[confirmed]` string/manifest/decompile/getprop evidence cited inline. `[inferred]` reasoned
from that evidence. `[unknown]` needs the memory-dumped DEX or the kernel tree. Evidence is `lib:line` into
`strings -n 5` output, `file:line` into the decompiles, or a manifest/asset path.

---

## 0. Shape of the thing (changes what "replacement" means)

ZLink is not one app. It is three processes plus a kernel driver `[confirmed]`:

```
 init (root)                         zygote (uid 1000, android.uid.system)
 ├─ service zlink5  ──────┐          com.zjinnova.zlink  (packed Java: UI, WifiAp, AAudio, JNI shims)
 │  = libzjL10001.so      │            │  libzlink_core.so   (props, licence)   libzlink.so (AAudio out)
 │    exports `main`,     │  local TCP │  libzbt_core.so     (BT bridge JNI)    libzjaudio_jni.so (AEC)
 │    no JNI_OnLoad       │◄──"Fox"───►│  libfdk_aac.so      (AAC → PCM)
 │    log tag `btopt`     │  protobuf  └─ com.zjinnova.zlink.action.ENABLE_AP → eventcenter → WifiManager
 │    links 30 .so:       │  zj.control.*
 │    Apple R16A8 CarPlay │  zj.AA.*       helper daemons it spawns via popen (root):
 │    GAL Android Auto    │              /dev/z-usbmuxd (libusbmuxd.so, Carbit)   ← wired iPhone as USB device
 │    HiCar, CarLife,     │              /dev/z-dhcpc   (libzjdhcpc.so, udhcpc)   ← DHCP client on NCM link
 │    UxPlay, DLNA        │              z-mdnsd        (libzmdnsd.so, mDNSResponder Aug 2024)
 └─ blink_cq (BT module daemon, tag `blink`)   /dev/BT_serial ← external "blueware" BT module
 kernel: /dev/zjinnova_iap2 (iAP2 gadget function), /dev/i2c-N (MFi IC), /sys/bus/platform/drivers/mtc-car/mfi
```

- `libzjL10001.so` has 0 `Java_*` exports, no `JNI_OnLoad`, exports `main` (`nm -D`), and no other library
  NEEDs it `[confirmed]`. It is a PIE executable shipped as a `.so`. `init.svc.zlink5=running` and the
  gateway does `SystemProperties.set("ctl.start","zlink5")` (`EC/EventService.java:7025-7044`) `[confirmed]`.
  That is how it runs as root: an init service, not `su` (no `su` string in any lib) `[confirmed]`.
- APK↔daemon RPC: "Fox" local TCP servers/clients (`libzjL10001:9643-9653`, `127.0.0.1`, `port = %d`),
  guarded with `iptables -I OUTPUT -p tcp --dport %d -d 127.0.0.1 -j DROP` (`:8227`) and protobuf-c messages
  `zj.control.{InitInfo,MfiInfo,platform_info,AudioState,resize,zlink_version,TrustMap,hicar_*,dlna_*}`,
  `zj.audiofocus.AudioFocus`, `zj.videofocus.VideoFocus`, `zj.touch.TouchEvent`, `zj.key.KeyEvent`,
  `zj.mic.{MicStart,MIC_DATA}`, `zj.bt.{bt_ap_info,bt_phone_info}`, `zj.SessionState.SessionState`
  `[confirmed]`. A metadata TCP port 1555 exists on the APK side (`gps/.../zlink/ZLinkSocket.java:17-51`,
  8-byte LE header, ids 1-5 AA / 6-10 CarPlay) `[confirmed]`, caller unknown.
- Link-mode table (`libzjL10001:8203-8216`): wired/wireless CarPlay, wired/wireless AA, wired/wireless HiCar,
  QuickTime, AirPlay, AOA link, IP Link, wired/wireless CarLife, USB NetShare, wireless DLNA `[confirmed]`.
  On this unit only CarPlay, Android Auto and HiCar are enabled (`rw.zlink.disable.features=ldyzqrhice`,
  manifest `meta-data` `CarPlayEnable/AutoEnable/HiCarEnable=true`, others false) `[confirmed]`.
- The APK is armeabi-v7a only (53 libs, no arm64 dir) → the Java process runs 32-bit on an arm64 unit
  `[confirmed]`. `assets/t86*` are x86 ELFs (packer companions), `assets/0OO00l111l1l` is the 4.3 MB
  encrypted DEX `[confirmed]`.

---

## 1. Manifest contract (`resources/AndroidManifest.xml`) `[confirmed]`

| Item | Value |
|---|---|
| package / uid | `com.zjinnova.zlink`, `sharedUserId="android.uid.system"`, min 21, target 23, compileSdk 33 |
| own permissions | `zjinnova.android.permission.ZLINK_SERVICE` (no protectionLevel → normal); `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature) |
| privileged/platform perms | `BLUETOOTH_PRIVILEGED`, `NETWORK_SETTINGS`, `TETHER_PRIVILEGED`, `OVERRIDE_WIFI_CONFIG`, `READ_PRIVILEGED_PHONE_STATE`, `READ_LOGS`, `LOCAL_MAC_ADDRESS`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `ACCESS_ALL_DOWNLOADS`, `REQUEST_INSTALL_PACKAGES` |
| normal/dangerous perms | BT (`BLUETOOTH`, `_ADMIN`, `_CONNECT`, `_SCAN`, `_ADVERTISE`), Wi-Fi (`ACCESS/CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`), location (coarse+fine), `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `CALL_PHONE`, `READ_PHONE_STATE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `BROADCAST_STICKY`, `GET_TASKS`, `VIBRATE`, `INTERNET`, storage |
| exported activities | `features.main.MainActivity` (`enabled=false`; MAIN/LAUNCHER, `zjinnova.android.intent.action.ZLINK_MAIN`, `…MAIN_PAGES`), per-protocol `features.launcher.{CarPlay,Auto,HiCar,Mirror,CarLife,CarPlayAuto}Activity` + `dlna.DlnaActivity` (singleInstance, own taskAffinity, only CarPlay/Auto/HiCar `enabled=true`), `*UnavailableActivity`, `ActivationActivity` |
| exported receivers | `StartupBroadcastReceiver` (`zjinnova.android.intent.action.START_DAEMON_SERVICE`, `zjinnova.intent.action.START_ZLINK_SERVICE`, `com.zjinnova.zlink`, `LOCKED_BOOT_COMPLETED`; directBootAware), `MediaButtonReceiver` (`MEDIA_BUTTON`), `PhoneStateReceiver` (`PHONE_STATE`), `InstallUpgradeBroadcastReceiver` (`DOWNLOAD_COMPLETE`) |
| services | `DaemonService` exported, permission `ZLINK_SERVICE`, action `zjinnova.android.intent.action.ZLINK_SERVICE`, `foregroundServiceType="mediaProjection|location|phoneCall|mediaPlayback"`, `stopWithTask=false`; unexported `SecondaryScreenService`, `wireless.BluetoothService`, `wireless.BluetoothHCTService`, `wireless.ZBTService` |
| providers | `FileProvider` (`…fileProvider`), `init.InitContentProvider` (directBootAware, multiprocess), Tencent `MidProvider` (exported, analytics) |
| USB filters | **none**: no `USB_ACCESSORY_ATTACHED`/`USB_DEVICE_ATTACHED` filter, no `res/xml` device filter. USB is handled by the root daemon with libusb (`libzjL10001:12751 libusb v%u…`, `:206 libusb_hotplug_register_callback`) |
| network | `network_security_config`: cleartext permitted; `usesCleartextTraffic` for the self-update URL |
| assets | `cnf_vendor_zhuoxw.yaml`: channel `zhuoxw`; per-SoC `otg_switch_to_host_cmd`/`…device_cmd`; **`trinket`** (this SoC) = `echo host|peripheral > /sys/devices/platform/soc/4e00000.ssusb/mode`, `mfi_bus_num1: 0` |

Intents **in** (from other apps, all unpermissioned): `com.zjinnova.zlink` with `command=` `SILENT|SILENT_BREAK|
ACTION_ENTER|ACTION_EXIT|REQ_SPEC_FUNC_CMD(+int specFuncCode)`; `com.zjinnova.zlink.action.{OUT_DARK_START,
OUT_DARK_STOP,POWER_ON,POWER_OFF,BACKCAR_START(+bool audio_break),BACKCAR_STOP}`; `…GET_DATA_RES`
(`key`,`value`); `zjinnova.android.intent.action.ZLINK_MAIN` (`page`,`feature`) (`EC/manager/ZlinkManage.java`,
`EC/EventService.java:662-712`). Intents **out**: `com.zjinnova.zlink` with `status=` `CONNECTED|DISCONNECT|EXIT|
MAIN_PAGE_SHOW|MAIN_PAGE_HIDDEN|MAIN_AUDIO_START|MAIN_AUDIO_STOP|ALT_AUDIO_START|ALT_AUDIO_STOP|PHONE_CALL_ON|
PHONE_CALL_OFF|PHONE_RING_ON|PHONE_RING_OFF|SIRI_ON|SIRI_OFF|CMD_MIC_START|CMD_MIC_STOP|ACTION_ZJ_PHONEFOUND`,
`phoneMode=` `{carplay,auto,hicar,airplay,android_mirror}_{wired,wireless}`, `phoneType`
(`settings/.../ICustomBroadcast.java:301-341`); `command=REQ_OS_AUDIO_FOCUS|ACTION_ZJ_IPODFOUND|
EVENT_DEVICE_MIC_REQUEST|EVENT_DEVICE_MIC_RELEASE`; `com.zjinnova.zlink.action.{ENABLE_AP,DISABLE_AP}`
(`EC/EvtModel.java:455-463` → `Utils.setWifiApEnable`); `…GET_DATA_REQ`, `…WRITE_DATA`.

---

## 2. The MFi chip path

Call chain, as far as symbols and strings show `[confirmed]`:

```
libAirPlay.so   AirPlayReceiverSession (pair-setup / auth-setup)
  └─ MFiSAP_Exchange              (libCoreUtils.so, Support/MFiSAP.c, kMFiSAPVersion1)
      └─ MFiPlatform_CopyCertificate / MFiPlatform_CreateSignature
           (libCoreUtils.so, Support/MFiServerPlatformLinux.c — "cp protocal: …", gMFiCertificateLen)
           └─ CarPlayGetCertificate_CB / CarPlayGetSignatureData_CB   (function-pointer hooks, set by
              CarPlayGetCertificateInit / CarPlayGetSignatureDataInit; "carplay protocal: …_CB fail..")
                └─ libzjL10001.so  zj_mfi_init  → channel select, saved in `persist.zj.mfi.channel`
                     ├─ MCU channel: MCUMFi_Init opens /sys/bus/platform/drivers/mtc-car/mfi,
                     │    MUCMFi_CopyCertificate / MUCMFi_CreateSignature, text commands ("W20=00",
                     │    "Data not ready(for W20 command only)", "Err: Mfi chip not available !")
                     └─ i2c channel: mfi_detect_i2c → mfi_probe("/dev/i2c-%d") ×2 buses →
                          mfi_device_id_probe: ioctl I2C_SLAVE_FORCE, "ioctl 0x10" then "ioctl 0x11",
                          byte mode 'one byte'/'more byte', I2C_RDWR or write/read syscalls →
                          MFi_Read_Certificate_Length_i2c / MFi_Read_Certificate_i2c /
                          MFi_Write_ChallengeDataLen_i2c / MFi_Write_ChallengeData_i2c /
                          MFiGetSignatureLen_i2c / MFi_Get_Challenge_Response_data_i2c / MFi_read_serialnum_i2c
```
Evidence: `libzjL10001:1851-1873, 12239-12312`; `libCoreUtils:884-898, 3543-3562`; `nm -D`: `MFiPlatform_*`
defined `T` in libCoreUtils, `U` in libzjL10001; `CarPlayGet*_CB` `T` in libCoreUtils.

- The same chain serves iAP2 identification over USB and BT: `RequestAuthenticationCertificate:
  MFiPlatform_CopyCertificate fail..`, `wifi iap:MFiPlatform_CreateSignature fail..` (`:12097-12201`).
- Device node: `/dev/i2c-N` via the Linux `i2c-dev` interface, N from the vendor yaml (`mfi_bus_num1: 0`
  for `trinket`, a second bus optional) → **`/dev/i2c-0`** on this unit `[inferred]`. Slave address
  `0x10` first, `0x11` second: exactly the two 7-bit addresses an Apple MFi 2.0C/3.0 authentication
  coprocessor answers on `[inferred]`; the probe reads the chip's device-id/version register `[inferred]`.
  The "W20" MCU command = write register `0x20` (challenge data length) `[inferred]`.
- Which channel this unit uses: `persist.zj.mfi.channel` is absent from the captured `getprop` (the
  daemon writes it after a successful probe, `:12245`), and `/sys/bus/platform/drivers/mtc-car/mfi` is an
  MTC-family MCU driver path (other vendor) `[confirmed]`. Given the yaml sets `mfi_bus_num1` for `trinket`,
  the i2c channel is the one in use `[inferred]`; `mfi_channel_save`/`read` (`:1858-1859`) suggests the
  choice may also be persisted to a file `[unknown]`.
- No kernel driver name for the MFi IC appears anywhere: it is reached through generic `i2c-dev`, so
  none is needed `[confirmed]`. Whether SELinux/DAC lets a non-root process open `/dev/i2c-0` `[unknown]`.
- **Unknowable statically**: the IC part (2.0C vs 3.0), whether it is the genuine Apple IC or a clone, the
  APK-side `setMfiId`/`rw.zlink.mfi.id`/`zj.control.MfiInfo` semantics (licence tie-in), and the bytes of
  the certificate. Only the DEX plus a live `i2cdetect`/read would settle it.

---

## 3. Wireless CarPlay bootstrap `[confirmed unless marked]`

1. **BT / iAP2.** RFCOMM service UUID `00000000-DECA-FADE-DECA-DEAFDECACAFE` (iAP2 over BT; stored as
   `00000000DECAFADEDECADEAFDECACAFE` and byte-reversed, `libzjL10001:8551-8552`). On this unit the BT
   radio is an external module (`rw.zj.bt.type=extra`, `sys.bluetooth.role=dual`,
   `vendor.blueware.dev.serial=/dev/BT_serial`, init service `blink_cq`) and the RFCOMM link is opened by
   the module firmware and relayed to the daemon over a serial/AT channel: `/dev/zj_bt_serial`,
   `/dev/rf_serial`, `AT#SH`, `AT#DR`, `AT#SG`, `AT#ZA`, `[SV]carplay rfcomm open success`,
   `[SI]rfcomm data in`, `send rfcomm start cmd` (`:8558-8654`); the BT daemon logs
   `send_zj50_carplay_connected ZLINK6` and skips its own CarPlay commands ("cur carplay switch(ZLINK6). so
   skip") (logcat). An alternative Android-BT path exists (`apk_BT_pthread`, `MESSAGE_BT_DATA`, `libzbt_core`
   JNI) `[confirmed]`; which one this unit takes at runtime `[inferred: module/serial]`.
   iAP2 messages implemented natively (`Pack_iAP2_Packet`, `iap2_wifi_loop_start`): `StartIdentification`,
   `IdentificationAccepted/Rejected`, `RequestAuthenticationCertificate`, `…ChallengeResponse`,
   `RequestAccessoryWifiConfigurationInformation`, `WirelessCarPlayUpdate`, `CarPlayStartSession`
   (`port`, `PublicKey`, `carplay_version`, `ip_addr`, link-local IPv6 built from the MAC, `:12073-12082`),
   `DeviceTransportIdentifierNotification`, `StartCallStateUpdates`, `StartNowPlayingUpdates`,
   `StartRouteGuidanceUpdates`, `StartLocationInformation`, `PowerSourceUpdate` (`:1796-1850, 12189-12215`).
   Identification values: accessory name `zlink` / `persist.zlink.bt.name` (`GT6-BT-4E1F` here), model
   `Device1,1`, manufacturer `Apple`, serial `0123456789A01234`, firmware `1.0.0`, components
   `zj-usb host`, `zj-bluetooth`, `zj-WirelessCarPlay`, `zj-VehicleInformation` (`:11942-11954`)
   `[confirmed strings; whether they are the values sent: inferred]`.
2. **Hotspot.** Not created natively. The daemon asks the APK (`request_AP_info`, `MESSAGE_AP_INFO`,
   protobuf `north_if/north_proto/bt_ap_info` with `ap_ssid, ap_passwd, ap_band, ap_NIC_name`, `:9043-9214`),
   and the APK asks the gateway with `com.zjinnova.zlink.action.ENABLE_AP` → `WifiManager` soft AP
   (`EC/EvtModel.java:455-463`) `[confirmed]`. SSID/passphrase generation lives in the packed DEX
   `[unknown]`. Channel is read back from `sys.wifiap.channel`, `sys.hostapd.channel` or
   `/data/vendor/wifi/hostapd/hostapd*.conf` (`:8003-8007`), with a "wait hostapd 4 s" special case for
   this SoC family (`xunzu 6125`, `:7977`). Interface `wlan0`/`wlan1`, bounced with `ifconfig` (`:7922-7926`).
   No 5 GHz literal for CarPlay; band comes from the APK `[confirmed]`.
3. **mDNS.** `z-mdnsd` = Apple mDNSResponder (engineering build Aug 2024), restarted per session
   (`killall -9 z-mdnsd`, `:7889-7892`). libAirPlay registers `_airplay._tcp` with TXT `deviceid`,
   `features=0x%X,0x%X`, `flags`, `model` (`AirPlayGeneric1,1`), `srcvers=450.8` (`libAirPlay:683-715`) and
   browses `_carplay-ctrl._tcp` for the phone's control endpoint (`/ctrl-int/1/%s`, User-Agent
   `AirPlay/450.8`, `AirPlay-Receiver-Device-ID`, `libAirPlay:1297-1317`). `_mfi-config._tcp` and `_hap._tcp`
   are present in CoreUtils but unused by the CarPlay path `[inferred]`.
4. **RTSP/HTTP.** Server banner `AirTunes/450.8`; methods `ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN,
   OPTIONS, POST, GET, PUT, GET_PARAMETER, SET_PARAMETER`; endpoints `/pair-setup`, `/pair-verify`,
   `/auth-setup`, `/info`, `/command`, `/feedback`, `/diag-info`, `/metrics`; headers `X-Apple-ProtocolVersion`,
   `X-Apple-Device-ID`, `X-Apple-Client-Name`, `X-Apple-HKP`, `X-Apple-PD`, `Control-Read/Write-Encryption-Key`,
   `DataStream-Output/Input-Encryption-Key`, `Events-Salt`; `application/x-apple-binary-plist` bodies;
   ports negotiated in plists (`dataPort`, `controlPort`, `timingPort`, `eventPort`, `keepAlivePort`)
   (`libAirPlay:667-993, 1513-1583`). Pairing = SRP-6a + ed25519/curve25519 + ChaCha20-Poly1305 screen keys
   (`AirPlayReceiverSessionScreen_SetChaChaSecurityInfo`, `AirPlay_DeriveAESKeySHA512ForScreen`).
5. **Streams.** Video: H.264 in AVCC, converted to Annex B (`libScreenStream: H264ConvertAVCCtoAnnexBHeader`),
   `MainScreen`/`AltScreen`, resolution/fps supplied by the APK (`wait_HU_screen_info: width, height, fps`,
   `:7967`), `ForceKeyFrame`, night mode, `requestUI` deep links (`maps:/car/instrumentcluster/map`).
   Audio formats offered: `PCM/8000…48000/16|24/1|2`, `AAC-LC/44100|48000/2`, `AAC-ELD/16000…48000`,
   `OPUS/16000|24000|48000/1` (`libAirPlay:884-912`); types `default, media, telephony, speechRecognition,
   alert`; `MainAudio`, `AltAudio`, `MainHighAudio`, `AuxIn/Out`; AAC can be passed to the APK
   (`CarPlaySetAAC_2_apk`, `libfdk_aac` JNI decoder) and mic forced to 8 kHz (`persist.zj.force_cp_mic_8k`).
   HID: `Touch Screen`, `Knob & Buttons`, `Telephony & Buttons`, `Media & Buttons`, `Proximity Sensor`
   descriptors from `CarPlay/cp_hid/*.c`, sent with `AirPlayReceiverSessionSendHIDReport` (`:11897-11902`).
6. **Provenance.** Every CarPlay lib carries the build path
   `Zlink5Libs/carplay-protocal-3.0/AppleCarPlay_CommunicationPlugin_R16A8/…` (`libCoreUtils:3543`,
   `libAirPlay:657`) — Apple's MFi-licensee CarPlay Communication Plug-in R16, `AirPlay/450.8`. This code
   is Apple-confidential; a replacement cannot ship or derive from it `[confirmed]`. `libzjAirPlay.so` is a
   separate UxPlay-derived AirPlay-mirroring receiver (`AppleTV3,2`, `_raop._tcp`, `/fp-setup`,
   `AirTunes/220.68`), used for the "AirPlay"/"QuickTime" link modes, not CarPlay `[confirmed]`.

**Wired CarPlay** differs: `wire_carplay_loop_start` → `zj_mfi_init` → `platform_switch_device` (the yaml
OTG command) → `platform_iap_ncm_init` (configfs `functions/iap2.gs0` + `ncm.gs0` linked into
`configs/b.1`, or legacy `android_usb` `iap,ncm`) → `tools_restart_mdnsd` → `CarPlay_Start` → `zj_iap_start`
on `/dev/zjinnova_iap2` (`:7916-7920, 8331-8386, 12160-12170`) `[confirmed]`. The unit therefore becomes a
USB *device* with a vendor iAP2 gadget function; the Carbit `z-usbmuxd` (host mode, lockdown) serves only
the QuickTime/AirPlay mirror modes `[inferred]`. The iAP2 gadget driver source `[unknown]`.

---

## 4. Android Auto `[confirmed unless marked]`

- **Own GAL implementation**, protobuf-c, under `zj.AA.*` (136 message types, `AA/AA-proto/*.pb-c.c`,
  `libzjL10001:9939-11522`). Not aasdk/openauto (no such strings).
- **Wired AOA**: manufacturer `Android`, model `Android Auto`, serial `0720SerialNo.` (`:8127-8136`);
  detection `is_going_aoa_device protocol = %d`, `AOA_Endpoint_Check`, hotplug via libusb; the AOA request
  ids and `0x18D1/0x2D00` are immediates, not strings `[inferred]`. USB host forced through the yaml OTG
  command (`zlink5: force usb host`, `platform_switch_host`, `:8099, 8469`).
- **Wireless AA**: RFCOMM UUID `4DE17A00-52CB-11E6-BDF4-0800200C9A66` (`:8626`); the head unit sends
  `WifiVersionRequest` (`supported_wifi_channel_type` `CHANNELS_5GHZ_ONLY|24GHZ_ONLY|DUAL_BAND`), receives
  `WifiVersionRespond`, sends `WifiInfoRespond` (`wifi_ssid, wifi_password, wifi_bssid, wifi_security_mode`
  WPA2_PERSONAL…), `WifiStartRequest(ip_address)`, gets `WifiStartRespond(ip_address, status)` and
  `WifiConnectStatus`; then waits for the phone on a TCP port (`AA_wait_port ok (port = %d)`, literal
  5277 absent → immediate `[inferred]`) (`:9804-9837, 11155-11311`). Same hotspot as CarPlay (§3.2).
- **TLS**: OpenSSL 1.1.0f static, `TLSv1_2_client_method`, `SSL_set_connect_state`; **one PEM certificate +
  PKCS#8 RSA-2048 key embedded** (`:19312-19359`), issuer `O=Google Automotive Link`, subject
  `O=Android-Auto-Internal, OU=01`, serial 0x111, valid 2014-07-04 → 2048-08-01 — the same well-known
  head-unit credential the open projects carry. Not reproduced here.
- **Service discovery**: head unit identity `Desktop Head Unit` / `zlink3` / `zj build` / `1.0.1`
  (`head_unit_make/model/software_build/software_version`, `:9737-9742, 10123-10127`), `driver_position`;
  services: media sink (video, `VIDEO_800x480|1280x720|1920x1080|…`, `VIDEO_FPS_30|60`, forced 1280x720 with
  density change, `:9703-9705, 10308-10332`; codecs `MEDIA_CODEC_VIDEO_H264_BP`, `AUDIO_AAC_LC[_ADTS]`), main /
  system / navigation audio sinks (PCM, rates at runtime), microphone source, input (touch `InputReport`,
  `KeyBindingRequest`), sensors (`DrivingStatus`, `NightMode`, speed), bluetooth (`BLUETOOTH_PAIRING_OOB|
  NUMERIC_COMPARISON|PASSKEY_ENTRY|PIN`), navigation status, phone status, media playback status,
  wifi projection, vendor extension, generic notification. Channel handlers `AA_video_cmd_handle`
  (`CHANNEL_OPEN`, `SINK_SETUP/START/STOP`, `VIDEO_FOCUS`), `AA_input_cmd_handle`, `AA_*_audio_cmd_handle`.
- **Vendor "AOA link" mirror** is a separate mode: AOA identity `Zlink` / `ZlinkDriverModel` /
  `http://url.zjinnova.com/download_zlink5_android_app`, private `mirror-proto` (`:8141-8143, 11579-11599`).
  Not Android Auto; out of scope.

---

## 5. Integration with the rest of the unit `[confirmed]`

- **Mode/audio handshake** (gateway `EC/manager/ZlinkManage.java`, already in `OEM_SYSTEM.md`): on
  `status=CONNECTED` the gateway does `setCurModeCallback(32)` + `sendMode(SRC_CARPLAY=32,true)`; on
  `DISCONNECT` `exitCurMode(32)`; `MAIN_AUDIO_START/STOP` drives the "now playing" title (protocol name only);
  `PHONE_CALL_ON/OFF` mutes streams 3/4 and sets `CarplayCallStatus`; the gateway also sets sysprop
  `CarplayConnectStatus` 1/0 (`EC/EventService.java:13972-13977`) which btsuite and canbus2 read.
- **Audio output**: the APK plays PCM through **AAudio** (`libzlink.so`: `AAudioStreamBuilder_setUsage/
  setContentType/setPerformanceMode`, JNI `AudioEngine.nativeWrite`) and holds focus as uid 1000 with
  `USAGE_MEDIA/CONTENT_TYPE_MUSIC` and `USAGE_VOICE_COMMUNICATION/CONTENT_TYPE_SPEECH` (audio dump), with a
  MediaSession `com.zjinnova.zlink/AudioFocusManager` and `MediaButtonReceiver` (media-session dump). No
  `AudioManager.setParameters` contract exists anywhere; the audio HAL is driven by sysprops only.
- **Mic/AEC**: `CMD_MIC_START/STOP` → gateway flips `vendor.audio.hu.aec` when `sys.bluetooth.role=device`
  (`EC/EventService.java:952-962`); the gateway polls `vendor.audio.hu.mic` every 300 ms to publish
  `ACTION_CARPLAY_TELEPHONE_STATUS_EVENT`. Tunables: `persist.zj.aectype|aecdelay|aecheaddelay|noise|
  micrecovery`, `persist.zj.apm.*` (WebRTC APM in `libapm`/`libwebrtc_apm`, `libblinkAEC`, `libspeexdsp`),
  `persist.zj.force_cp_mic_8k`, `persist.blinkbt.*` (`EC/utils/SystemUtils.java:289-326`). With
  `SYS_BT_LAUNCH_SOUND_KEY` set, calls go through BT SCO (`setBluetoothScoOn`, `EC:14807-14835`); on this
  unit `vendor.audio.hu.aec=false`, `persist.zj.force_cp_mic_8k=false`.
- **Reverse camera**: MCU `BACKCAR_START` → `com.zjinnova.zlink.action.BACKCAR_START` (`audio_break=true`)
  and `BACKCAR_STOP` (`EC/EventService.java:662-712`); the daemon answers with a CarPlay resource-mode
  change (`backupCamera`, `CarPlayScreenTake/Untake`, `AirPlayReceiverSessionChangeResourceMode`,
  `:11889-11895`) so the phone knows the screen is taken.
- **Day/night, ACC**: `OUT_DARK_START/STOP` + `rw.out.dark`; `POWER_ON/OFF`; on ACC sleep the gateway mutes,
  `sendAccStatus(false)` and disconnects BT; `rw.zlink.hu.acc`.
- **Keys**: `REQ_SPEC_FUNC_CMD` codes 1500 Siri, 1501/1502 turn, 1504 map, 1505 phone, 1506 music,
  1507 now-playing, 1508 home, plus raw 85/87/88 while mode 32; btsuite/canbus2/BBA panels start
  `CarPlayActivity` directly (`canbus2/.../CanDataParseBase.java:1444-1451`).
- **BT app**: btsuite only records `CONNECTED/DISCONNECT` to hide its call window and yields to
  `MainActivity` when CarPlay is up (`btsuite/BTService.java:1383-1401`, `BTMainActivity.java:155-168`); it
  writes the module's name into `rw.zlink.bt.name` (`parse/ParseFEasycom.java:296`). No RFCOMM hand-off in
  Java: the hand-off is module-firmware ↔ daemon over serial (§3.1).
- **Root actions and why**: `cp` of its own libs to `/dev/z-usbmuxd`, `/dev/z-dhcpc`, `/dev/z-netshare`
  (`/dev` is the one writable+exec tmpfs on every vendor kernel; `/data/local/tmp` is the fallback);
  OTG role switch and configfs gadget writes; `ifconfig`/`ip rule`/`iptables`; `setprop` of `sys.usb.*`,
  `persist.*`, `rw.*`; `killall z-mdnsd`; `am broadcast … --include-stopped-packages` to wake its own
  APK (`:8902-8918`). All of it runs inside the init service, not the APK.
- **Screen**: `persist.zlink.land.screen_type` (`00` here) / `persist.zlink.port.screen_type`, `rw.zlink.resize`,
  `design_width_in_dp=720`; the APK owns the SurfaceView and `SecondaryScreenService`.

---

## 6. Replacement architecture for `com.ripostelabs.projection`

### 6.1 What is buildable from open knowledge
| Stage | Open source basis | Depends on the unit |
|---|---|---|
| Android Auto, wired | GAL protocol as implemented by **aasdk** (f1xpl), **openauto** (f1xpl), **headunit** (gartnera/mikereidis), **aa-proxy-rs**, **WirelessAndroidAutoDongle** (nisargjhaveri); Android `UsbManager` accessory mode replaces libusb | USB host role switch (`4e00000.ssusb/mode`, root) — or leave the port in host mode permanently `[inferred]` |
| Android Auto, wireless | same projects (`WifiStartRequest` flow, RFCOMM UUID above); `BluetoothServerSocket` + `WifiManager.startLocalOnlyHotspot`/tethering | The head-unit TLS credential: the open projects ship the same one this daemon embeds; its licence status is the project's risk |
| CarPlay, any transport | Nothing lawful. The R16 plug-in is Apple-confidential; pair-setup/SRP/HAP is documented by open AirPlay work (UxPlay, openairplay), but **CarPlay** session semantics and MFi-SAP require the MFi programme (`CARPLAY.md`) | MFi IC on `/dev/i2c-0` (§2) — usable only inside a licensed stack |
| HiCar | Huawei SDK only | keys/HUKS |
| Hotspot, mDNS | Android `WifiManager`, `NsdManager` (or jmDNS) | `TETHER_PRIVILEGED` needs the system uid or root |

### 6.2 Stages
1. **Shim** (Java only, buildable today): a `Projection` app that owns the launcher-facing contract of §1
   (same broadcast action/extras so `Zlink.kt`/`CarPlayState.kt` and the gateway keep working), forwards
   `REQ_SPEC_FUNC_CMD`, and delegates to the stock zlink activities. Proves the contract, zero risk.
2. **Android Auto receiver** (Java + JNI or a pure-Java GAL port): AOA via `UsbManager` needs no root when the
   port is already host mode; wireless AA needs `BLUETOOTH_CONNECT` + a hotspot; video to a `SurfaceView`
   via `MediaCodec` (H.264 baseline), audio via `AudioTrack`, mic via `AudioRecord`; TLS via Java `SSLEngine`
   with the projects' credential. Implement `CONNECTED/DISCONNECT/MAIN_AUDIO_*` broadcasts, `sendMode(32)`
   through the existing `IEventService` path, and `BACKCAR_*` → `VideoFocus` loss.
3. **CarPlay**: keep zlink for CarPlay and take AA away from it. `rw.zlink.disable.features` is a letter
   set (`w l a b d y z q r h i c e`, `OEM_SYSTEM.md`) and `AutoActivity` can be disabled with
   `setComponentEnabledSetting`; which letter is AA `[unknown]`, so verify on the bench before relying on
   it. A clean-room CarPlay receiver is not on the table.
4. **Retire the daemon** only when CarPlay is no longer needed: stop `zlink5` (`ctl.stop`), remove the
   `/dev/z-*` copies; nothing else on the unit depends on the daemon (§5).

### 6.3 Risks
- **Legal**: CarPlay code provenance (Apple R16A8) and the AA head-unit credential. Do not vendor either.
- **Two BT stacks**: the external module (`blink_cq`) owns HFP/A2DP and the CarPlay RFCOMM; Android's BT
  (`sys.bluetooth.role=dual`) may or may not expose RFCOMM server sockets to apps on this unit `[unknown]`.
  Wireless AA over `BluetoothServerSocket` must be proven on the bench before anything else.
- **Role switch**: wired AA needs the port in host mode; today the daemon forces it. A Java-only app must
  either rely on the daemon's leftover state or write the sysfs node as root via a helper.
- **Native code**: `template/build.sh` is `aapt2 → javac → d8`; no NDK step. A `MediaCodec`/`SSLEngine`
  design keeps AA in Java. If a native helper is unavoidable (sysfs writes, libusb), it is a separate
  Magisk-side binary, not part of the APK build.
- **32-bit**: zlink is armeabi-v7a; a replacement should be arm64 or pure Java.
- **Focus**: the gateway's `sendMode(32)` kills other players (`kill3rdAPK`); the shim must not double-fire.

### 6.4 Proposed layout (`rav4-apps/apps/projection/`, gradle-free like the other apps)
```
AndroidManifest.xml            package com.ripostelabs.projection, sharedUserId android.uid.system
build.sh → ../../template/build.sh
src/com/ripostelabs/projection/
  contract/ZlinkBroadcast.java   §1 action/extras, status + phoneMode enums (shared with the launcher)
  contract/GatewayBridge.java    sendMode(32)/exitCurMode, CarplayConnectStatus, REQ_SPEC_FUNC_CMD in
  transport/UsbAoa.java          UsbManager accessory: identify, bulk in/out
  transport/BtRfcomm.java        BluetoothServerSocket on 4de17a00-…, Wifi* handshake
  transport/Hotspot.java         LocalOnlyHotspot / ENABLE_AP fallback
  gal/{Framing,Ssl,Channels}.java   GAL framing, TLS client, service discovery, per-channel handlers
  media/{VideoSink,AudioSink,MicSource}.java   MediaCodec / AudioTrack (USAGE_MEDIA, USAGE_VOICE_COMMUNICATION) / AudioRecord
  ui/ProjectionActivity.java     singleInstance, own taskAffinity, SurfaceView, touch → InputReport
  ui/ProjectionService.java      foreground (mediaPlayback|phoneCall), BACKCAR_*, OUT_DARK_*, POWER_*
res/…                          icons + strings, theme wired through <queries> like the other apps
```

---

## 7. What only the memory-dumped DEX (or the kernel tree) can answer
- Hotspot SSID/passphrase/band generation and whether it uses `WifiManager` directly or only `ENABLE_AP`.
- The Fox RPC port numbers and message framing between APK and daemon; the metadata port 1555 consumer.
- `setMfiId` / `rw.zlink.mfi.id` / `sys.zlink.regcode|barcode|chip` licensing: whether CarPlay is gated on a
  server-issued activation, and what `getChipActivationInfo` reads.
- Which BT path is live (module serial vs Android RFCOMM) and the AT dialogue with the module.
- Exact AOA/USB request immediates and the wireless-AA TCP port (assumed 5277).
- The iAP2 gadget driver (`/dev/zjinnova_iap2`) and the MFi IC identity on `/dev/i2c-0`.

Answered where the DEX could: §8.

---

## 8. Answered from the unpacked DEX (2026-09-14)

_Inputs: the two DEX images lifted from the running `com.zjinnova.zlink` process (`dex\n035`, 7.6 MiB with
7237 class_defs / 43,435 strings, 1.7 MiB with 2348 class_defs / 10,717 strings), decompiled with jadx 1.5.6.
**Every `code_item` in both images has its instructions zeroed** (52,967 + 9,035 methods, 0 with live
bytecode): SecShell keeps method bodies out of the DEX region and restores them per-call. What survives is
the skeleton: class/field/method names and types, JNI signatures, protobuf field tables, Kotlin metadata,
and the full string pool. Evidence is therefore `File.java:line` into `decompiled/com.zjinnova.zlink-unpacked/
dex0/sources/` (declarations) or `str#N` = string index in the 7.6 MiB DEX. 230 `com.zjinnova.*` classes
recovered; the rest is netty, okhttp, protobuf-lite, Bugly/Baidu stats, Kotlin. Nothing here changes §0-§5;
it fills the `[unknown]` slots._

**Hotspot** `[confirmed]` ZLink generates nothing. The APK reads the unit's own soft-AP settings with the hidden
`WifiManager.getWifiApConfiguration()` (`str#32445`), casts to `WifiConfiguration` (`str#36234`) and uses
`wifiCfg.SSID`, `preSharedKey`, `allowedKeyManagement`, `apBand` (`str#42919, 37374, 25851, 26254`), caches
them in prefs `apSsid_/apPasswd_/apBand_` (`str#26259, 26258, 26255`) and answers the daemon's
`BtApInfoReqMsg` with `BtApInfoMsg{message_id=1, ap_ssid=2, ap_passwd=3, ap_band=4, ap_interface_name=5}`
(`proto/BtApInfoProto$BtApInfoMsg.java:14-17`). The interface name comes from the
`android.net.wifi.WIFI_AP_STATE_CHANGED` extra `WIFI_AP_INTERFACE_NAME` (`str#25975, 25977`). Start is
`HotspotManager.kt` (`str#7287`) on a `hotspot-worker` thread via reflective `startTethering/stopTethering`
(`str#41334, 41387`, `START_TETHERING_FAILED`), guarded by `ssid & pwd both SHOULD-NOT-NULL, step1
STOP-AP-FIRST!` (`str#41253`) and `persist.zlink.ap.start_delay_ms` (`str#37185`); the daemon triggers it
with `WirelessCmdMsg{wireless_type=2, is_enable=3}` (`wirelessCmd startHotspot/stopHotspot`, `str#42960`).
The broadcast `com.zjinnova.zlink.action.ENABLE_AP` (`str#27951`) is the vendor branch gated by
`allowEnableApAction` (`str#41290`); the gateway's handler is just `ConnectivityManager.startTethering(0,…)`
(`eventcenter/AccEvent/Utils.java:356-368`), so on this unit too the SSID/passphrase are whatever Settings
→ Hotspot holds. Band: the phone is told `ap_band` as read; no 5 GHz forcing exists in Java `[confirmed]`.
A replacement can use `SoftApConfiguration` directly; no ZLink secret is involved.

**Fox RPC** `[confirmed]` On the APK side the **APK is the server**. Four Netty NIO servers,
`CtrlTransServers`, `AudioTransServers`, `VideoTransServers`, `BtTransServers` (`str#5040, 3645, 23880,
3997`; `Ooooo/W{30}.java:9` `ChannelInitializer<SocketChannel>`, `Ooooo/W{31}.java:25` `NioEventLoopGroup`; `W{n}` = a class named by n `w`s), and the
daemon connects (`CtrlTransServers onClientConnected`, `CTRL_SOCKET_CONNECT_STATUS`, `str#5039, 4348`).
Framing is a custom `ByteToMessageDecoder` (`MessageCodec.kt`, `Ooooo/W{34}.java:20`) carrying
protobuf-lite messages whose first field is always `message_id`/`id` (every `proto/*.java`), with
`ReqHandshakeMsg`/`RespHandshakeInfo{id, data}`, `HeartBeatMsg`, `SessionStateMsg{state, link_type}`.
Port numbers and header layout were immediates in the zeroed bodies `[unknown]`; `ss -ltnp` on the unit
while `zlink5` is up is the one-line answer. Port 1555 (`ZLinkSocket`) does not appear in the APK
`[confirmed absent]`. Message vocabulary the daemon expects: `MESSAGE_BT_INIT_INFO`, `RESP_MFI_INFO`,
`RESP_MIC_START/STOP`, `MESSAGE_PHONE_CALL_MODE`, `CMD_START_AP`, `CMD_MIC_START/STOP` (`str#5349, 5350,
4201-4203`). `InitInfoMsg` (31 fields, `proto/InitInfoProto$InitInfoMsg.java:17-42`) is the APK→daemon
bootstrap `[inferred: it carries the yaml values only the APK has]`: screen geometry, `activate_link_type=7`, `http_url=8`, `mfi_bus_num_1/2=14/15`,
`otg_switch_host/device=17/18`, `is_wireless_carplay_ipv4=19`, `is_force_usb_host=20`, `log_file_path=21`;
the values are the vendor yaml (`PlatformCnf.java`, `cnf_vendor_zhuoxw.yaml`, channel `zhuoxw`, `str#43293`).

**Licensing** `[confirmed]` Two gates, both in `ActivationManager.kt` (`str#3295`): (1) a local licence file
under `getPlatformLicenceDir()` (`ZlinkCore.java:50`, JNI ← `persist.sys.lic.dir`), prefs
`LicenceFilePath/LicenceKey/LicenceKeyVersion` (`str#14139-14141`), `check local license file` /
`local license file not found` (`str#27275, 34963`); (2) online `checkActivationInRemote` →
`activateLoopCheck` (`str#27281, 25505`) against `https://apis.zjinnova.com/` `zlink/v5/reqHwId`
(`str#33002, 43309`; also `act.zjinnova.com:9190`, `que.zjinnova.com` for QR activation,
`zupdate.zjinnova.com` `checkUpdate/zlink2`), request `HwIdReqInfo{hwModelId}` from `getHwId()`
(`ZlinkCore.java:40` ← `rw.zlink.hw.modelId`), reply `DeviceActivationInfo{activatedType,
activatedFeatures}`. The result goes back to native via `setActivationKey/setActivationResult`
(`ZlinkCore.java:68-70` → `rw.zlink.act.key/.ret`) and is broadcast as
`zjinnova.android.intent.action.ACTIVATION_STATE_CHANGE` (`str#43301`). MFi tie-in: the daemon answers
`ReqMfiInfoMsg` with `RespMfiInfoMsg{id, is_fake=2, mfi_uuid=3}` (`proto/RespMfiInfoProto…java:16-17`);
the APK stores it (`AppPrefs.setMfiId:436`, `isMfiGot:277`, `getCurrentMfiId:163`), runs
`setprop persist.zj.mfi.id ` (`str#40905`) and decides `exist mfi and activate type is MirroringFree so
remove activate info` / `has mfi to activate` / `device mfi got false or device activated false`
(`str#29668, 32621, 28875`). So a genuine MFi chip *is* the CarPlay licence: chip present → the paid
activation is dropped; chip absent (`hu_mfi_fake`, `str#33023`) → online activation required `[inferred]`.
`sys.zlink.regcode|barcode|chip` and `rw.zlink.mfi.id` live only in `libzlink_core.so`
(`getChipActivationInfo`, `setMfiId` JNI); the in-memory `ZlinkCore` declares neither native
(`ZlinkCore.java:1-77`), so that path is dead in 5.4.62 `[confirmed]`. Offline product codes exist
(`getOfflineProductCode`, `ZlinkCore.java:44`, prefs `isOfflineProductCode*`).

**BT path** `[confirmed]` Both exist in Java. Android-BT: `BluetoothService.java:126` holds a
`BluetoothServerSocket`, `ZBTService.java:66` a `rfcommChannelMap`; strings show the head unit is RFCOMM
**client** to an iPhone (`IPhone to connect Rfcomm socket`, `createInsecureRfcommSocketToServiceRecord`,
`uuid-carplay matched:` after `fetchUuidsWithSdp` on `ACL_CONNECTED`; `str#7753, 28401, 42459, 29861`) and
**server** for Android Auto (`listenUsingInsecureRfcommWithServiceRecord`, `Rfcomm server created or
accepting`, `uuid-auto matched:`; `str#34924, 20743, 42458`), UUIDs `00000000-deca-fade-deca-deafdecacafe`
and `4de17a00-52cb-11e6-bdf4-0800200c9a66` (`str#2420, 2498`). RFCOMM bytes are relayed to the daemon as
`BtDataMsg` over `BtTransServers` (`send2RfComm data-len:`, `str#38870`), pairing state as
`BtPhoneInfoMsg{local_mac, phone_uuid, is_paired, pair_mode, pair_code}` (`proto/BtPhoneInfoProto…java:16-21`).
Selection: `getBtType()` (`ZlinkCore.java:26` ← `rw.zj.bt.type`) and the per-channel
`androidBtSupportWirelessLinkType` (`VendorChildDynamicPropsCnf.java:302`,
`ANDROID_BT_SUPPORT_WIRELESS_LINK_TYPES`). `/dev/zj_bt_serial` and `/dev/rf_serial` are in the APK pool
(`str#2371-2372`) but no AT command is; with `rw.zj.bt.type=extra` here, the Java RFCOMM code is bypassed
and the module/serial path of §3.1 is live `[inferred]`. HiCar goes through `libzbt_core` (`Zbt.java:200-246`).

**Status broadcast the launcher consumes** `[confirmed]` Action `com.zjinnova.zlink` with string extras
`status`, `command`, `phoneMode`, `phoneType` (`str#41366, 27966, 37226, 37230`). `status` ∈ `CONNECTED`,
`DISCONNECT`, `MAIN_AUDIO_START/STOP`, `PHONE_CALL_ON/OFF` (`str#4243, 5297, 18379-18380, 19754-19755`);
`phoneMode` ∈ `carplay_wired|carplay_wireless|auto_wired|auto_wireless|hicar_*|airplay_*|android_mirror_*|
dlna_*` (`str#27178-27179, 26558-26559`); `phoneType` values are not literal in the pool `[unknown]`. Inbound `command` `REQ_SPEC_FUNC_CMD`
+ int `specFuncCode` (`str#20410, 41186`). The APK never asks eventcenter for the source mode:
`GET_DATA_REQ/RES` are absent from its pool; it only emits, and listens for `ENABLE_AP`-class replies and
`OUT_DARK_*`, `POWER_*`, `BACKCAR_*`, `USB_IDLE/OCCUPIED`, `AA_PHONECALL_ON/OFF`,
`action.bluetooth.PHONE_STATUS` (`str#27945-27958`). Own actions: `zjinnova.android.intent.action.ZLINK_MAIN`,
`…ACTIVATION`, `…secondary_screen.enter_background|enter_foreground|prohibit_minimize` (`str#27890-27892`).
Extra sysprops it reads: `sys.gen.zlink.wifiap`, `persist.sys.zlink.{mode.registed,disable.features,bgconn.disa}`,
`rw.zlink.audiostream.{media,others,phonecall,phonering,speech,tts}`, `persist.zj.cp_aa_fusion`
(`str#41587, 37176-37178, 38650-38655, 37181`).

**Still unknown after the dump** — the APK never touches these; they are daemon-side immediates:
- Fox port numbers and frame header; the wireless-AA TCP port (5277 assumed); AOA request immediates.
- `/dev/zjinnova_iap2` driver and the MFi IC identity. The daemon only reports `mfi_uuid`+`is_fake`.
- The AT dialogue with the BT module (`AT#SH/DR/SG/ZA`, §3.1) — daemon strings only.
Next step that answers the first two: `ss -ltnp`, `ls -l /dev/i2c-* /dev/zjinnova_iap2` and
`i2cdetect -y 0` on the live unit; the kernel tree for the gadget function.

---

## 9. Answered on the bench: the daemon's own protocol (2026-09-20)

_The daemon runs on Riposte OS 0.2 without its app. Everything below came from running it
against a listener, sweeping message ids with empty frames (it names every id it does not
handle), and reading its protobuf-c descriptors, which carry field names and numbers in data.
The app that answers it is `com.ripostelabs.projection` in rav4-apps (`zlink/` package)._

**Transport** `[confirmed]`. The daemon is the client. It dials four servers on 127.0.0.1:
1777 control, 1666 audio, 1888 video, 1999 Bluetooth relay. It listens on 1555 (metadata) and
7722 (dashboard). Every frame on every channel is `ff ff ff 10 | u32 length | u32 id |
payload`, big-endian; control payloads are protobuf-lite whose field 1 repeats the id.

**Control ids** `[confirmed]`. `0x101` SessionState (state 2, link type 3: states 1 WAIT_INIT,
2 WAITING_LINK, 3 SESSION_STARTING, 4 session, 6 stopped), `0x102` InitInfo (31 fields as
`zj.control.InitInfo`; field 7 is a bit set: 1 wired CarPlay, 2 wireless CarPlay, 4 wired AA,
8 wireless AA, 16 and 32 HiCar; changing it restarts the daemon), `0x111` multi-touch, `0x112`
touch (x, y, is_down), `0x113` key (Android key code, is_down), `0x114` MFi request → `0x115`
MFi info (is_fake, uuid; `is_fake=0` on this unit). `0x116` is a handshake request (empty; the
session does not wait for an answer) and `0x118` a heartbeat every 2 s (not to be echoed).
`0x503` is AP state, `0x607` the AP request every 5 s → `0x608` AP info (ssid, passphrase,
band, interface). The rest: `0x705`/`0x706` night, `0x710` call state, `0x712` stop, `0x801`
device time from the phone, `0xa02` daemon build hash, `0xb02` video resize.

**Bluetooth relay** `[confirmed]`. `0x602` request on connect, `0x603` BT_INFO (local MAC,
the matched service UUID as 32 uppercase hex digits, paired, pair mode, pair code), `0x604`
raw RFCOMM bytes both ways, `0x605` release (the session is on Wi-Fi), `0x606` closed. An
empty body on this channel closes it. BT_INFO naming `00000000DECAFADEDECADEAFDECACAFE` is
what moves the session to wireless CarPlay; the daemon then does the whole iAP2 exchange
(identification, `RequestAuthenticationCertificate`, challenge, Wi-Fi configuration) through
the relayed bytes.

**Media** `[confirmed]`. Video frames on 1888 are id `0x302`: a 20-byte header (width,
height, available width, available height, zero) then one Annex-B H.264 access unit, SPS and
PPS first. Audio frames on 1666 are id `0x202`: a 24-byte header (sample rate, channels,
bits, zero, audio type, zero) then PCM, 3840 bytes at 44.1 kHz stereo.

**What the daemon needs from the unit** `[confirmed]`. Its mDNS responder at
`/system/bin/z-mdnsd` (a fixed path list, never `PATH`). The AP interface name and channel
(`sys.wifiap.channel`, else the hostapd config file). A 5 GHz access point, since the phone
refuses 2.4 GHz. For the wired path, the iAP2 gadget: `mkdir functions/iap.gs0` creates
`/dev/zjinnova_iap2` on the stock kernel. The phone's session over that stack rendered on
the panel on 2026-09-20.
