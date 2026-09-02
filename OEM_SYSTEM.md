# The OEM system, app by app

What every Choiceway app on the GT6 actually does, which gateway calls, broadcasts and
SysVar keys it uses, and what a replacement has to reproduce. Everything here comes from
jadx decompiles of the unit's own APKs (2026-09-01/02 firmware, `com.szchoiceway.*`
`v2024-12`), cited as `path:line` inside the decompile tree. Nothing in this file was
verified on the car unless it says so; the car was offline while it was written.

Companion documents: `CAR_API.md` (the gateway interface, being corrected from this file),
`AIDL_ORDINALS.md` (the 144-method table), `CUSTOMERUI_NOTES.md` (the stock home),
`debloat-plan.md` (what may be uninstalled).

## 0. The shape of the system

```
   MCU (serial, /dev/ttyHS1)           CAN box (serial)          BT module (/dev/ttyS3)
          │                                  │                          │
          ▼                                  ▼                          ▼
  com.szchoiceway.eventcenter  ◄──────  com.szchoiceway.canbus2     com.szchoiceway.btsuite
  "the gateway": IEventService (144      decodes CAN frames, sends    HBCP protocol, phone +
  AIDL methods), SysVar provider,        broadcasts, HVAC console     A2DP, its own broadcasts
  mode (audio source) owner, key
  router, kill3rdAPK, day/night
          │ AIDL + broadcasts
          ▼
  every other vendor app (radio, music, video, camera viewer, settings, zlink…)
  and ours (CarLauncher, the com.ripostelabs.* suite)
```

Three facts decide what "replace" can mean here:

1. **Every vendor app runs as `android.uid.system`** (`sharedUserId`), signed with a
   platform key we do not have. No in-place replacement, ever. A replacement is a new
   package that reproduces the app's *contract* with the gateway, plus hiding or
   `pm uninstall --user 0` of the original.
2. **The gateway is the only path to the hardware.** Tuner, amplifier source, backlight,
   ACC, keys, CAN data: all of it is an `IEventService` call or a broadcast the gateway (or
   canbus2) sends. Any app may bind it; most broadcasts are unprotected. So a replacement
   never needs system uid for the *car* side.
3. **Audio follows the MCU's "mode".** Android audio (Spotify, our suite) plays with no mode
   of its own; a hardware source (radio 1, BT 6, AUX 40…) is claimed with `sendMode` and must
   be released with `exitCurMode` for Android audio to come back. The vendor radio does that
   on `AUDIOFOCUS_LOSS`, and so does CarLauncher since #31. `kill3rdAPK` — the gateway
   force-stopping third-party tasks on a mode change — is **off by default**
   (`Sys_SoundManager_Type` defaults to "1").

## 1. Replacement matrix

| OEM package | Label | What it really is | Replace? | Contract a replacement must keep | Where we are |
|---|---|---|---|---|---|
| `com.szchoiceway.eventcenter` | — | MCU gateway, SysVar host, mode owner | **Never** | — | consumed by everything |
| `com.szchoiceway.canbus2` | Canbus | CAN decode + HVAC/door/radar UI, reflected into the reverse camera | **Never** (safety) | consume its broadcasts (§5, §6, §8) | radar/climate/steering readers in carlib |
| `com.szchoiceway.customerui` | — | stock home; a *window* the gateway inflates by name | Keep installed, replace as HOME | be HOME; answer the UI-mode echo (§gateway 1) | CarLauncher is HOME; APK not yet pulled |
| `com.szchoiceway.radio` | Radio | tuner UI over `sendRadioKey`/`sendUserFreq`/callbacks | **Yes** | claim SRC_RADIO, key table, PS name, TA/AF/PTY setup, zone via `changeSetup` | CarLauncher RadioScreen (#30, #31) + suite radio; keep the APK until both are proven on the car |
| `com.szchoiceway.musicplayer` | Music | USB/local player, `SRC_MUSIC` 11, `setValidModeAllInfor` | **Yes** | audio focus; optional `setValidModeAllInfor` so the vendor now-playing broadcast stays alive | suite `com.ripostelabs.music` (MediaSession); vendor metadata path unused |
| `com.szchoiceway.videoplayer` | HD movies | player, `SRC_MOVIE` 10, PIP overlay, **handbrake gating** via `Sys_CurBreakSate` | **Yes** | reproduce the brake gate (legal), PIP optional | suite `com.ripostelabs.video`; brake gate not implemented |
| `com.szchoiceway.zxwmedia` | zxwmedia | background media scanner + SQLite index, exported provider | Yes, by omission | nothing — use MediaStore | not needed |
| `com.szchoiceway.btsuite` | Bluetooth | HBCP protocol to the BT module: phone, contacts, A2DP | **Not yet** — it owns the module protocol | drive it: broadcasts §9, `zxw_bluetooth_contral_action`, `IBTService` | status chip fix in progress; suite `bluetooth` app is Android BT only |
| `com.szchoiceway.settings` | System | SysVar editor; every write goes through `changeSetup` | **Yes**, in slices | key domains §3-5, `changeSetup` (78), `sendBacklight` (60); factory menu stays | CarLauncher settings screens; keys being corrected |
| `com.szchoiceway.gps` | GPS | GNSS status page; writes nav package keys | **Yes** | `Set_NavPackageName/ClassName/LableName` if we offer a nav picker | suite `com.ripostelabs.gps` |
| `com.szchoiceway.navigation` | Navigation | **misnamed**: front/rear/blind camera + HDMI viewer | Later | `sendMode(50/51/52)` + `setCameraChannel` + `Camera.open`; brake gate; `force_camera_close` | nothing; UNVERIFIED whether a normal app may open the camera HAL |
| `com.szchoiceway.photoreader` | XDemonstrate | 16-image demo slideshow | Remove | — | suite `photos` is a real viewer |
| `com.szchoiceway.learn.key` | ZXWLib | SWC / panel / touch key learning | Keep (factory tool) | protocol §learn.key if we ever host a learner | launcher reads its JSON map (in progress) |
| `com.szchoiceway.apkinstall` | Apk Installer | silent `pm install -r` from USB | **Remove** (security) | — | suite `installer` |
| `com.szchoiceway.canbusdebug` | CanbusDebug | MCU/CAN frame injector overlay | Keep hidden (diagnostics) | — | — |
| `com.lfg.szchoiceway.canupgrade` | CanUpgrade | MCU/CAN/BT firmware flasher | Keep hidden (bricks the MCU if misused) | — | — |
| `com.choiceway.weather` | Weather | Seniverse (China) API client | Remove | — | suite `weather`, own source |
| `com.zjinnova.zlink` | ZLINK5 | CarPlay / AA / HiCar / mirror receiver, `SRC_CARPLAY` 32, packed DEX | **Never** (proprietary) | drive by intent: `ZLINK_MAIN`, `REQ_SPEC_FUNC_CMD`, status broadcasts | helper in progress; never put its class names in `SYS_LAUNCHER_APP_HIDE_KEY` |
| `com.mmbox.xbrowser` | XBrowser | Chinese browser, not system uid | Remove | — | suite `browser` |
| `com.android.atslcarconsole` | Console | unknown, 21 MB, gateway-bound | Investigate | — | decompile pending |

Not yet pulled from the unit (named in `debloat-plan.md`, absent from the local APK stock):
`customerui`, `auxcamera`, `com.choiceway.dsp`, `canoriginalcarmedia`, `ambient.light`,
`multicolor.light`, `gesture`, `providers.settings` (the likely SysVar provider host —
UNRESOLVED), `zxw_dashboard`, `instructions`, `testtools`, `com.ivicar.avm`. Pull and
decompile them before touching audio (dsp), lighting or the camera stack.

Order of work that follows from the matrix: (1) finish the radio and prove it on the car;
(2) settings keys and day/night echo, because they affect the launcher whether or not the OEM
apps stay; (3) climate + radar + doors from canbus2's broadcasts; (4) Bluetooth *driven*, not
replaced; (5) video brake gate, then hide the vendor players; (6) camera viewer last.

---

## 2. Radio (`com.szchoiceway.radio`) and the tuner side of the gateway

Paths abbreviated: `RADIO/` = `decompiled/com.szchoiceway.radio/sources/com/szchoiceway/`,
`EC/` = `decompiled/com.szchoiceway.eventcenter/sources/com/szchoiceway/eventcenter/`.

### 1. ZXW_RADIO_INFO_EVT and com.szchoiceway.radio.frequency

Two different broadcasts. The launcher conflates them.

**ZXW_RADIO_INFO_EVT** is sent by the gateway, not the radio app.

`EC/EventUtils.java:1978`
```java
public static final String ZXW_RADIO_INFO_EVT = "com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT";
```
`EC/EventService.java:7630-7636`
```java
public void sendRadioInfor() {
    Intent intent = new Intent(EventUtils.ZXW_RADIO_INFO_EVT);
    intent.putExtra("RadioBndNum", this.mRadioBndNum);
    intent.putExtra("RadioTuneNum", this.mRadioTuneNum);
    intent.putExtra("RadioCurFreq", this.mRadioCurFreq);
    sendBroadcastAsUser(intent, UserHandle.ALL);
```
Extras, all `int`, no permission:
| key | meaning |
|---|---|
| `RadioBndNum` | band index, same value as `getRadioBand()` (see 3) |
| `RadioTuneNum` | preset slot 0..5 within the band, same as `getRadioNum()` |
| `RadioCurFreq` | frequency, same units as `getRadioFreq()` (see 2) |

Sent on a band change (`EC/EventService.java:2790-2792`) and on a frequency change only while the valid mode is SRC_RADIO (`:2812-2816`):
```java
EventService.this.mRadioCurFreq = (bArr[3] & 0xFF) | ((bArr[2] << 8) & 0xFF00);
EventService.this.notifyRadioEvt(3, 0, EventService.this.mRadioCurFreq, null, null);
if (EventService.this.getValidMode() == EventUtils.eSrcMode.SRC_RADIO.getIntValue()) {
    EventService.this.sendRadioInfor();
```
No PS name or RDS flags in it. Nothing in the decompiled tree consumes these extras (grep for `"RadioCurFreq"` outside EventService: no hits).

**com.szchoiceway.radio.frequency** is *received* by the radio app, sent by Settings (voice/quick-tune helper). It is a tune request, not status.

`RADIO/radio/MainActivity.java:48-49`
```java
public static final String BROADCAST_RADIO_FREQUENCY_EVENT = "com.szchoiceway.radio.frequency";
public static final String BROADCAST_RADIO_FREQUENCY_EVENT_EXTRA = "com.szchoiceway.radio.frequency_extra";
```
`RADIO/radio/RadioService.java:259-266` (receiver): `intent.getFloatExtra(... EXTRA, 0.0f)` -> handler msg 10002.
Sender: `decompiled/com.szchoiceway.settings/sources/com/zxw/lib/ui/broadcast/SendBroadcastStationHelps.java:199-202`
```java
public static void sendBroadcastRadio(android.content.Context context, float f) {
    android.content.Intent intent = new android.content.Intent("com.szchoiceway.radio.frequency");
    intent.putExtra("com.szchoiceway.radio.frequency_extra", f);
```
One extra, `float`: MHz for FM (e.g. 96.3), kHz for AM (e.g. 1010). The radio app decides by magnitude, `MainActivity.java:240-245`: `> 108.0f` = AM.

The `sendBroadcastAsUser` at `MainActivity.java:681-686` is `sendRadioCmd`, a raw MCU frame `{2, cmd, arg}` on `ACTION_MCU_CMD_EVENT`, not the info broadcast.

**Station name (PS) exists.** The MCU sends it; the gateway stores it under a misnamed getter.

`EC/EventService.java:2836-2842`
```java
private void onRadioPSName(byte[] bArr) {
    ...
    EventService.this.mRadioPSName = new String(bArr, 2, bArr.length - 3);
    EventService.this.notifyRadioEvt(6, 0, 0, null, EventService.this.mRadioPSName);
```
`EC/EventService.java:7510-7512`
```java
public String getRadioPTYName() {
    return this.mRadioPSName;
}
```
So `getRadioPTYName()` returns the RDS **PS station name**, not the genre. The genre is `getRadioPTYNum()` indexed into a local table, `RADIO/uicontroller/UIControllerBase.java:16` (`PTYInfoList = {"none","news","affairs",...}`) and `RADIO/radio/MainActivity.java:631` assigns `mRadioPSName = mService.getRadioPTYName()`. Radio event 6 delivers the string in the `str` argument of `ICallbackfn.notifyEvt`. No radio text (RT) anywhere: grep `radioText|RT` gives nothing in either app.

### 2. Frequency units

`getRadioFreq()` returns the raw 16-bit MCU value (`EC/EventService.java:2812`, above). FM is in **10 kHz units**, AM in **kHz**.

Display, `RADIO/radio/MainActivity.java:654-660`:
```java
if (this.mUIControllerBase.mRadioBndNum <= 2) {
    strStringFormat = ... stringFormat("%d.%02d MHZ", Integer.valueOf(mRadioCurFreq / 100), Integer.valueOf(mRadioCurFreq % 100));
} else {
    strStringFormat = ... stringFormat("%d KHZ", Integer.valueOf(mRadioCurFreq));
```
So 9630 -> "96.30 MHZ", 1010 -> "1010 KHZ". Default at boot `EC/EventService.java:255`: `mRadioCurFreq = 8750`.

Voice/broadcast tune path, `MainActivity.java:240-261`: "96.3" -> `isValidUserFreq` pads to "96.30" (`UIControllerBase.java:82-83`), strips the dot -> 9630 -> `sendUserFreq(9630, true)`. Same units.

Gateway frame, `EC/EventService.java:4300-4302`:
```java
public void sendUserFreq(int i, boolean z) {
    byte[] bArr = {12, (byte) ((i >> 8) & 255), (byte) (i & 255), (byte) (!z ? 1 : 0)};
```
Byte 3: 0 = FM, 1 = AM.

Limits/step by zone (`KEY_RADIO_ZONE_SETTINGS`, `mRadioZoneType`), `RADIO/uicontroller/UIControllerBase.java:117-205`:
| zone | FM min/max/step (10 kHz) | AM min/max/step (kHz) |
|---|---|---|
| 0 | 8750 / 10800 / 5 | 522 / 1620 / 9 |
| 1 | 8750 / 10790 / 20 (landscape UI) else 10 | 530 / 1710 / 10 |
| 2 | 8750 / 10800 / 10 | 520/1620/10 (landscape) else 530/1720/10 |
| 3 | 6500 / 7400 / 3 | 522 / 1620 / 9 |
| 4 | 7600 / 9000 / 10 | 522 / 1629 / 9 |
| 5 | 8750 / 10800 / 10 | 530 / 1710 / 9 |

Zone 1 is North America. Snap rule `UIControllerBase.java:88-94`: clamp to [min,max], then `min + ((v-min)/step)*step`.

### 3. getRadioBand() values

Seven values 0..6 accepted by the gateway, `EC/EventService.java:2789-2790`:
```java
if (bArr[2] >= 0 && bArr[2] <= 6) {
    EventService.this.mRadioBndNum = bArr[2];
```
Radio app: `<= 2` is FM, `> 2` is AM. `RADIO/radio/MainActivity.java:645-650`:
```java
if (mRadioBndNum <= 2) {
    ... stringFormat("FM%d  %02d    ", mRadioBndNum + 1, mRadioTuneNum + 1);
} else {
    ... stringFormat("AM%d  %02d    ", mRadioBndNum - 2, mRadioTuneNum + 1);
```
So 0=FM1, 1=FM2, 2=FM3, 3=AM1, 4=AM2, (5=AM3, 6 accepted but never labelled). Preset list slice, `RADIO/uicontroller/RadioUIController.java:895-904`: FM uses `mRadioFreqList[0..17]` (index `band*6 + tune`), AM uses `[18..29]` (index `(band-3)*6 + tune`). The list has 30 entries (`UIControllerBase.java:44`), 6 per band.

### 4. Presets: Rdo_MyFavorite0..5 and MCU presets

Two independent preset systems.

**A. MCU presets** (6 per band, 30 total) live in the tuner. Recall/store by key, see 5. Read back via `getRadioFreqList()` + `getRadioNum()`.

**B. "My favourite" (Rdo_MyFavorite0..5)** are app-side, band-agnostic, stored in the SysVar provider as a decimal string of a packed int.

Keys, `RADIO/zxwlib/SysProviderOpt.java:35-41`:
```java
public static final String RDO_FullscreenMode_KEY = "Rdo_FullscreenMode";
public static final String RDO_MyFavorite0_KEY = "Rdo_MyFavorite0";
... RDO_MyFavorite5_KEY = "Rdo_MyFavorite5";
```
Write, `RADIO/uicontroller/RadioUIController.java:1193-1211`:
```java
if (this.mRadioBndNum > 2) {
    i = (this.mRadioCurFreq & 0xFFFF) | 65536;      // AM: bit16 set, kHz
} else {
    i = this.mRadioCurFreq & 0xFFFF;                // FM: 10 kHz units
}
this.mProvider.updateRecord(strArr[i2], "" + i);
```
Read, `RadioUIController.java:361-375`: `getRecordInteger(key, 0)`; 0 = empty ("not collected"); `(v & 0x10000) == 0` -> FM `"%d.%02d"` of `v & 0xFFFF`; else AM `v & 0xFFFF`.

Encoding: `value = freq | (isAm ? 0x10000 : 0)`. Examples: FM 96.3 -> `9630`; AM 1010 -> `66546`. Empty -> `0` or unset. Same code in `RadioUIControllerKldUi3.java:753-1000` and `RadioUIControllerRotate.java:309-1180`.

Recall, `RadioUIController.java:835-870`: switch band with key 30/31 if the current band class differs, then `sendUserFreq(freq, isFm)`. Store is a long-press on the slot (`:741`), select is a tap (`:709`).

Landscape/LandRover/Bentley UIs use a different store: MMKV file `FavoriteFreqFile`, key `FavoriteFreq`, string set `"FM_96.30"` / `"AM_1010"`, `RADIO/uicontroller/chwy/landrover/FavoriteFreqUtils.java:19-46`. Not the SysVar keys.

`Rdo_FullscreenMode` is declared but unused in the radio app.

**sendRadioKey 1..6 vs 7..12.** Only `OnKeyEvent` sends them, mapping MCU panel keys, `RADIO/radio/MainActivity.java:737-774`:
```java
case 37: sendRadioKey(1);  ...  case 42: sendRadioKey(6);
case 64: sendRadioKey(7);  ...  case 69: sendRadioKey(12);
```
`EC/EventUtils.java:1543-1555`: `MCU_KEY_NUM1 = 37 ... MCU_KEY_NUM6 = 42`, `MCU_KEY_NUM1_LONG = 64 ... MCU_KEY_NUM6_LONG = 69`. Short press NUM n -> key n (recall), long press -> key n+6 (store). The touch UI never sends 1..12; it recalls a list entry with a 3-byte frame instead, `RadioUIController.java:379-389`:
```java
if (this.mRadioBndNum > 2) { i += 18; }
sendRadioCmd(100, i);   // Intent ACTION_MCU_CMD_EVENT, MCU_CMD_DATA = {2, 100, index}
```
Index 0..29 into the full freq list (FM 0..17, AM 18..29). `EvtModel.java:364-373` relays `MCU_CMD_DATA` bytes to `sendCmdData`. This is the direct preset recall across bands.

### 5. sendRadioKey opcode table

Gateway, `EC/EventService.java:3958-3959`: `byte[] bArr = {2, (byte)(i & 255)}` untouched.

| key | sender (UI or MCU panel key) | meaning |
|---|---|---|
| 1..6 | `MainActivity.java:738-753` on MCU_KEY_NUM1..6 (37..42) | recall preset n |
| 7..12 | `MainActivity.java:758-773` on NUM1..6_LONG (64..69) | store preset n |
| 13 | btnScan `RadioUIController.java:684`; "sousou" `RadioUIControllerLandscape.java:572`; `RadioSetBase.java:220` (stop scan on zone change); MCU_KEY_APS=13 `MainActivity.java:730` | preset scan (APS) |
| 14 | btnPrev `RadioUIController.java:671`; MCU_KEY_BND=14 `MainActivity.java:733` sends 24 not 14 | step down |
| 15 | btnNext `RadioUIController.java:662`; MCU_KEY_RADIO_PREV=71 `MainActivity.java:779` | step up |
| 16 | long btnPrev `:728`; MCU_KEY_RF=8 `MainActivity.java:709` | seek down |
| 17 | long btnNext `:725`; MCU_KEY_FF=7 `MainActivity.java:705` | seek up |
| 18 | long btnScan `:731`; long "sousou" `Landscape.java:621`; MCU_KEY_AMS=12 `MainActivity.java:727` | auto store (AMS) |
| 19 | btnSTMono `:681`; "litiyin" `Landscape.java:591`; MCU_KEY_STMONO=49 `MainActivity.java:717` | stereo/mono toggle |
| 20 | btnDXLOC `:653`; "yuancheng" `Landscape.java:594`; MCU_KEY_LOCDX=48 `MainActivity.java:713` | DX/LOC toggle |
| 21 | btnAF `:647`; "af" `Landscape.java:603` | AF toggle |
| 22 | `PTYView.java:110-116` after `sendSetup(3, ptyIndex)` | PTY seek |
| 23 | btnTA `:690`; "ta" `Landscape.java:600` | TA toggle |
| 24 | MCU_KEY_PLAYPAUSE=6 `MainActivity.java:700-702` and MCU_KEY_BND=14 `:732-734` | band cycle (FM1->FM2->FM3->AM1->AM2...) |
| 25 | MCU_KEY_NEXT=2 `MainActivity.java:692-694`; voice "下一频道" `RadioService.java:302` | next station (seek up / next preset) |
| 26 | MCU_KEY_PREV=3 `MainActivity.java:696-698`; voice "上一频道" `RadioService.java:293` | previous station |
| 30 | btnFM `:659`; key 208 `MainActivity.java:720`; before any FM tune from AM | select FM |
| 31 | btnAM `:650`; key 209 `MainActivity.java:788`; before any AM tune from FM | select AM |

Names for 24/25/26 come from the panel-key semantics (BAND / NEXT / PREV); the MCU side is not in the decompile, so "seek vs preset" for 25/26 is UNRESOLVED. `EventUtils.java:1477,1539,1579,1582`:
```java
public static final byte MCU_KEY_BND = 14;
public static final byte MCU_KEY_NEXT = 2;
public static final byte MCU_KEY_PLAYPAUSE = 6;
public static final byte MCU_KEY_PREV = 3;
```
Keys 27..29 never sent. `sendRadioKey` is suppressed while a TA announcement is playing on landscape UI 0, `MainActivity.java:556-560`.

### 6. Mode and audio lifecycle

`RadioService.sendRadioMode()`, `RADIO/radio/RadioService.java:188-201`:
```java
this.mService.setCurModeCallback(SRC_RADIO, this.mModeCallback);
this.mService.setRadioCallback(this.mRadioCallback);
this.mService.sendMode(SRC_RADIO, false);
this.mService.sendMode(SRC_RADIO, false);
this.mhandler.sendEmptyMessage(255);
```
Skipped when `mStartExit` is set (`:190`). Called from `MainActivity.sendRadioModeToEventCenter(z)` (`MainActivity.java:563-572`) only when `getValidMode() != SRC_RADIO || !z`, i.e. `z=true` means "only if not already radio". Triggers: msg 254 (event service bound, `:111-113`), audio focus regained (`:418-419`), `radioPlayAudio` (`:479-485`), which is every button tap (`RadioUIController.java:644`) and every panel key (`MainActivity.java:691`).

`radioPlayAudio(z)`, `MainActivity.java:479-485`: `sendRadioModeToEventCenter(z)` then `AudioManagerUtils.requestAudioFocus()`. Focus request, `RADIO/utils/AudioManagerUtils.java:44`: `AUDIOFOCUS_GAIN`, stream MUSIC, usage MEDIA. Loss (-1) -> listener state 4 -> `exitCurMode()` unless `SYS_CARAUTO_RADIO_RUNNING` (`MainActivity.java:428-443`). Transient loss (-2) -> state 3 -> `sendVoiceState(true)` (MCU frame `{66, 1}`, `:900`, a duck flag).

`exitCurMode()`, `MainActivity.java:813-829`: `sendVoiceState(false)`, clear handler, `RadioService.exitRadioMode()` -> `mService.exitCurMode(SRC_RADIO)` (`RadioService.java:211-221`, sets `mStartExit=true`), `UIController.onExitRadioMode()`.
Call sites: 4097 with another mode (`:120-137`), key 511 (`:141-145`), focus loss (`:433,437`), onLowMemory when not on top (`:966-967`), `disconectRadioService` on destroy (`:802`).

Gateway side, `EC/EventService.java:8917-8945`: `exitCurMode(i)` is a no-op unless `mValidMode == i`; then clears the callback, `sendMode(SRC_NULL=99, false)`, broadcasts exit + valid mode.

Event ids seen by the radio app handler (`MainActivity.java:103-265`):
| id | origin | meaning |
|---|---|---|
| 254 | `RadioService.java:78`, local | event service connected -> `sendRadioModeToEventCenter(false)` |
| 255 | `RadioService.java:201`, local | mode sent -> `refreshRadioInfor(); setRadioInfor()` |
| 4097 | gateway `EC/EventService.java:8847` `notifyValidModeEvt(4097, 0, newMode)` via mode callback | EVENT_MODE_CHANGE; if new valid mode is neither RADIO(1) nor CARPLAY(32): abandon focus + exitCurMode |
| 4098 | gateway `:2695` `notifyValidModeEvt(4098, 0, mcuKey)` | EVENT_KEY_EVENT; `arg2` = MCU key -> `OnKeyEvent` |
| 0..6 | gateway `notifyRadioEvt` (`:2782-2842`) via radio callback | 0 state bits, 1 band, 2 tune num, 3 freq, 4 list entry (arg1=index), 5 PTY num, 6 PS name (str) |
| 7 | radio app only; gateway never emits 7/8 | re-read `SYS_RDS_ONOFF_KEY` |
| 10002 | broadcast `radio.frequency` | tune to float |
| 10008 | `ACTION_ACC_SLEEP_STATUS_EVT` arg 1 | ACC back: `radioPlayAudio(false)` |

Key 511: `EventUtils.MCU_KEY_EXIT = 511` (`EC/EventUtils.java:1509`). The gateway never sends it on this path (arg1 is always 0 in `notifyValidModeEvt(4098, 0, ...)`). Only `RadioService.java:281-287` sends `what=4098, arg1=511` for the voice phrase "关闭收音" (close radio). Handler `MainActivity.java:141-145`:
```java
if (message.arg1 == 511) {
    MainActivity.this.mStartExit = true;
    MainActivity.this.exitCurMode();
    MainActivity.this.finish();
```
`setCurModeCallback(SRC_RADIO)` on the gateway also persists `SYS_LAST_MODE_KEY` and runs `kill3rdAPK()` (`EC/EventService.java:8871-8873, 8886`).

### 7. The "NET" tab

UNRESOLVED, and probably not in this app. Looked at: all `RADIO/uicontroller/**` (bottom button list `RadioUIControllerLandscape.java:630-644` is sousou/jianpan/fm/am/litiyin/yuancheng/yinxiao/ta/af/pty/shezhi), `R.java` (no id/string containing "net"), grep `"NET"|SRC_NETWORK|netradio|internet` in radio, eventcenter, musicplayer sources: only the `SRC_NETWORK(16)` enum constant at `EC/EventUtils.java:2022`, never referenced by EventService. The `resources/` directory of the radio decompile is empty, so layouts could not be checked. `com.szchoiceway.zxwmedia` had no matches. The launcher's "NET was an internet-radio source" claim has no evidence here; whatever showed it was a different UI variant or app.

### 8. TA/AF/PTY/stereo read and set

Read: all from `getRadioValue()`, `MainActivity.java:614-631`, sourced from one MCU status frame, `EC/EventService.java:2768-2781`:
```java
mRadioRDSState = (bArr[3] & 1) > 0;  mRadioPTYState = (bArr[3] & 2) > 0;
mRadioAFState  = (bArr[3] & 4) > 0;  mRadioTAState  = (bArr[3] & 8) > 0;
mRadioSTState  = (bArr[3] & 16) > 0; mRadioLOCState = (bArr[3] & 32) > 0;
mRadioAMSState = (bArr[3] & 64) > 0; mRadioAPSState = (bArr[3] & 128) > 0;
mRadioStIconState = (bArr[2] & 1) > 0; mRadioTpIconState = (bArr[2] & 2) > 0;
mRadioTrafficState = (bArr[2] & 4) > 0; mRadioNoPTYState = (bArr[2] & 8) > 0;
```
Event 0 arg2 carries the packed 16 bits (`:2782`).

Setters that exist:
- TA: `sendRadioKey(23)`. AF: `sendRadioKey(21)`. Stereo/mono: 19. DX/LOC: 20.
- PTY: `sendSetup((byte)3, ptyIndex)` then `sendRadioKey(22)` to seek that type; `sendSetup(3, 0)` clears. `RADIO/uicontroller/landscape/view/PTYView.java:104-117`. Gateway frame `{5, 3, idx}`, `EC/EventService.java:6389-6399`.
- RDS on/off: `changeSetup("Sys_RDS_OnOff"...)` via `SYS_RDS_ONOFF_KEY`, `RADIO/uicontroller/RadioSetBase.java:201-203`. Radio zone: `changeSetup(KEY_RADIO_ZONE_SETTINGS, "0".."4")`, `:211`.

No AIDL method sets TA/AF directly; the launcher is right that they are keys, wrong about which keys.

### Corrections to the launcher

Files under `launcher/`.

1. `carlib/.../CarService.kt:261-266`, `ui/RadioScreen.kt:67-70`, `README.md` TODO "ZXW_RADIO_INFO_EVT": "no PS getter" is wrong. `getRadioPTYName()` returns the RDS PS station name (gateway field `mRadioPSName`, MCU frame 0x73/6). Rename `getRadioPtyName` to `getRadioStationName`; genre is `getRadioPTYNum()` + the 32-entry table in `UIControllerBase.java:16`.
2. `README.md` TODO and `ui/settings/RadioInfoCaptureScreen.kt:17-20`: `ZXW_RADIO_INFO_EVT` sender is `EventService.sendRadioInfor()`; extras are int `RadioBndNum`, `RadioTuneNum`, `RadioCurFreq`. It fires on band change and on freq change while in SRC_RADIO. `com.szchoiceway.radio.frequency` is an inbound tune request (float extra `com.szchoiceway.radio.frequency_extra`, MHz or kHz), not status; drop it from the capture.
3. `ui/RadioTuning.kt` (unit heuristics `RAW_HZ_THRESHOLD`, `RAW_TEN_KHZ_THRESHOLD`), `ui/RadioCard.kt:336-350`: units are fixed. FM = 10 kHz units (8750..10800), AM = kHz (530..1710). Delete the magnitude guessing.
4. `ui/RadioTuning.kt` limits: North America is zone 1: FM 8750..10790 step 10 (20 on the landscape UI), AM 530..1710 step 10. Read `KEY_RADIO_ZONE_SETTINGS` and use the zone table if the zone is not 1.
5. `CarService.kt:75` `isAmBand(band) = band >= 3`: correct, no longer a guess. Bands are 0=FM1, 1=FM2, 2=FM3, 3=AM1, 4=AM2 (5, 6 accepted by the gateway).
6. `CarService.kt:45` comment and `CAR_API.md` §3.2: keys 1..12 are only ever sent from panel NUM keys. The touch UI recalls by list index with a 3-byte frame `{2, 100, index}` on `ACTION_MCU_CMD_EVENT` (index = band*6+slot for FM, 18+(band-3)*6+slot for AM). Add that if preset recall is wanted without a band switch.
7. `CAR_API.md` §3.2 and `CarService.kt:47-56` opcode table is incomplete: add 21 AF, 22 PTY seek (after `sendSetup(3, idx)`), 23 TA, 24 band cycle, 25 next, 26 prev. `RadioScreen.kt:370` "no setter beyond seek/band/tune" is wrong for TA/AF/PTY.
8. `ui/RadioScreen.kt:576-579` and `README.md` TODO "Rdo_MyFavorite0..5 encoding": known. Decimal string of `freq | (am ? 0x10000 : 0)`, freq in the units above, 0 = empty, 6 slots band-agnostic. Two-way sync is safe. Note the landscape/LandRover/Bentley UIs ignore these keys (MMKV `FavoriteFreqFile`), so check which UI the car runs (`SYS_UI_NUMBER_KEY`).
9. `ui/RadioScreen.kt:72-74`, `ui/RadioTuning.kt:24-27` "NET tab was an internet-radio source": no evidence in the decompile. Mark UNRESOLVED rather than asserting.
10. `CarService.kt:237-241` `claimRadio` matches `RadioService.sendRadioMode` exactly. Missing pieces the vendor also does: request `AUDIOFOCUS_GAIN` on MUSIC, call `exitCurMode(1)` on 4097-with-other-mode, on focus loss, and on leaving the screen; send `{66, 0/1}` duck flag on transient loss. Event 6 on the radio callback delivers the PS name in `str`, event 3 the freq in `arg2`; the 3 s poll can go.
11. `CarService.kt:245-250` radio callback: `EVENT_KEY_EVENT` (4098) arrives on the *mode* callback with the MCU key in `arg2`; 511 is never sent by the gateway, it is the vendor's internal voice-exit marker.

---

## 3. The gateway (`com.szchoiceway.eventcenter`)

Paths are relative to `decompiled/`.
`EC/` = `com.szchoiceway.eventcenter/sources/com/szchoiceway/eventcenter/`,
`CB/` = `com.szchoiceway.canbus2/sources/com/szchoiceway/canbus2/`.
The active CAN parser for the RAV4 is `CB/model/vios/toyota/HiworldCanParseToyota.java` (launcher's own `HiworldCanDecoder.kt:8` says so; confirmed by opcode 0x11 = `OnHandleCanBasicStatusCmd`).

### 1. UI-mode "handshake"

Not a handshake. The gateway delegates its day/night decision to the launcher and expects the same value echoed back.

Receiver (launcher -> gateway), one extra, **int**:
```
EC/EvtModel.java:1076
if (action.equals(EventUtils.ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT)) {
    int intExtra17 = intent.getIntExtra(EventUtils.EXTRA_DAY_NIGHT_UIMODE, 0);
    mContext.setDayNightMode(intExtra17);
```
```
EC/EventUtils.java:1235
public static final String EXTRA_DAY_NIGHT_UIMODE = "Extra_Day_Night_UiMode";
```
Value = `Sys_Day_Night_Mode` (`EC/SysProviderOpt.java:288`): 1 = day, 2 = night, 3 = auto by sunrise/sunset, anything else (0) = follow headlamps.
```
EC/EventService.java:14043-14087  setDayNightMode(int i)
if (i == 1) { sendSysUiModeNight(false); return; }
if (i == 2) { sendSysUiModeNight(true);  return; }
if (i == 3) { ...sunrise/sunset -> uiModeManager.setNightMode(1|2)... }
if (!this.mLAMPConnected) { setNightMode(1) } else { setNightMode(2) }
```
```
EC/EventService.java:14089-14093
Intent intent = new Intent("com.szchoiceway.uiModeNightChanged");
intent.putExtra("mode", z);               // boolean, true = night
sendBroadcastAsUser(intent, UserHandle.ALL);
```
Sender (gateway -> launcher), same int extra, then a 2 s fallback (msg 317 -> `setDayNightMode` itself):
```
EC/EventService.java:14856-14862
public void sendDayNightUiModeToLauncher(int i) {
    Intent intent = new Intent(EventUtils.ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT);
    intent.putExtra(EventUtils.EXTRA_DAY_NIGHT_UIMODE, i);
    sendBroadcast(intent);
    this.mEventHandler.removeMessages(317);
    this.mEventHandler.sendEmptyMessageDelayed(317, 2000L);
```
Sent only when `Sys_Day_Night_Mode` changes to 1/2 on a portrait build (`EC/EventService.java:4936-4941`) or on a headlamp change in mode 0 on a landscape build (`:805-814`). Not a liveness signal.

Consequence for the launcher: `announceUiMode(night: Boolean)` puts booleans under four wrong keys, so `getIntExtra` returns 0 and the gateway runs `setDayNightMode(0)` = "follow headlamps", overriding the user's setting. Each call also cancels the gateway's own pending day/night timers (`:14046-14047`).

### 2. Key events

#### 2a. Steering-wheel resistive keys (MCU cmd 0x74)
```
EC/EventService.java:2847-2858
private void onCmdWheelEvent(byte[] bArr) {
    if (bArr.length >= 3 && bArr[1] <= 9) {
        Intent intent = new Intent(EventUtils.STEER_WHEEL_INFOR);
        intent.putExtra(EventUtils.STEER_WHEEL_INFOR_LPARAM, bArr[1] + 1);   // 1..10
        if (bArr[2] == 0) putExtra(WPARAM, 4) else putExtra(WPARAM, 3);       // 4 = up, 3 = down
        intent.putExtra(EventUtils.STEER_WHEEL_INFOR_VOLTAGE, bArr[4] & 0xFF);
        sendBroadcastAsUser(intent, UserHandle.ALL, PERMISSION_CHOICEWAY_BROADCAST);
```
Launcher's 3/4 convention is confirmed. LPARAM is a physical slot 1..10, not a function.

#### 2b. Function keys: MCU cmd 0x72 -> `MCU_KEY_INFOR` broadcast
Every key the MCU reports (panel, SWC after study, CAN-box keys) arrives as an `MCU_KEY_*` code in `bArr[1]` of MCU frame 0x72 (`EC/EventService.java:2401`), or from the CAN box as `ZXW_CAN_KEY_EVT` (`EC/EvtModel.java:492-510` -> `ProcessCanKey`, `EC/EventService.java:13021-13214`). Both end in:
```
EC/EventService.java:8948-8967  notifyValidModeEvt(int what, int arg1, int arg2, byte[], String)
mCarCamCallbackfn.notifyEvt(...); mValidModeCallbackfn.notifyEvt(...);
if (i == 4098) { EventUtils.sendKeyEventBroadcast(this, i3); }
```
```
EC/EventUtils.java:2147-2154
Intent intent = new Intent(MCU_KEY_INFOR_ACTION);      // "com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR"  (:1521)
intent.putExtra(MCU_KEY_VALUE, i);                     // "EventUtils.MCU_KEY_VALUE", int
context.sendBroadcastAsUser(intent, UserHandle.ALL);   // unprotected
```
Edge encoding: none. One broadcast per press. Long presses are distinct codes (`MCU_KEY_NUM1_LONG..6_LONG` 64..69, `MCU_KEY_TFT_LONG_*` 80/81). Codes the gateway consumes fully (54 radio, 55 nav, 130, 131 standby) `return` before the notify and are never broadcast (`:2419-2465, 2689-2697`).

Key-code table = `EC/EventUtils.java:1458-1656` (`MCU_KEY_*`, bytes; negative = +256). The radio app's codes: 2 NEXT, 3 PREV, 6 PLAYPAUSE, 7 FF, 8 RF, 12 AMS, 13 APS, 14 BND, 37-42 NUM1..6, 48 LOCDX, 49 STMONO, 64-69 NUM1_LONG..NUM6_LONG, 70 RADIO_NEXT, 71 RADIO_PREV, 208 RADIO_FM (-48), 209 RADIO_AM (-47), 511 MCU_KEY_EXIT. Others a launcher wants: 1 POWER, 9 MENU, 16 MODE, 17 MUTE, 18 VOL_ADD, 19 VOL_SUB, 22 HANGUP, 23 TALK, 85 RETURN, 116 SHENGKONG (voice), 113 TASK_LIST, 184/-72 APPLIST, 246 DIM, 247 STANDBY, 252 WAKE, 262/263 BACK_LIGHT_ADD/SUB, 141-155 WHEEL_INDEX1..15, 164-178 PANEL_INDEX1..15.

RAV4 CAN-box SWC map (Hiworld frame 0x11, byte[4] button, byte[5] pressed):
```
CB/model/vios/toyota/HiworldCanParseToyota.java:853-885
1->18 VOL+, 2->19 VOL-, 3->17 MUTE, 4->116 VOICE, 5->23 TALK (22 HANGUP if in call),
6->22, 8/13->3 PREV, 9/14->2 NEXT, 12->16 MODE, 15->6 PLAYPAUSE, 16->85 RETURN
```
Held VOL+/- auto-repeats after 5 frames (`:838-846`).

#### 2c. `ACTION_HOST_MCU_BUTTON_KEY`
Not a key path. It is the volume relay to an original-car amplifier, sent only when `mConfig.getCarSoundType()!=0 || mAjustCarVol!=0` or customer 58 (`EC/EventService.java:4224-4235`):
```
EC/EventService.java:4271-4289
if (i == 12) i2 = 4; else if (i == 0) i2 = 2; else i2 = i == 1 ? 3 : 0;   // mute->4, vol- ->2, vol+ ->3
sendHostCarKey(i2, (byte) 1);  sendHostCarKey(i2, (byte) 0);              // HostKeyStatus 1 = down, 0 = up
```
Extras: `"HostKeyWord"` int, `"HostKeyStatus"` byte (`EC/EventUtils.java:1238-1239`). Also `sendHostCarKey(1, 1/0)` at :2688, :9763, :13845 (standby/power path).

#### 2d. `sendWheelKey(int)`
```
EC/EventService.java:6369-6375
byte[] bArr = {7, (byte) (i & 255)};  mSendThread.notifyToSend(bArr);
```
ARM->MCU frame 0x07 `<MCU_KEY code>`. Pairs with SWC study: `STEER_WHEEL_STATUS` (`EC/EventUtils.java:1773`, extra `"EventUtils.STEER_WHEEL_STUDY_STATUS"` int, from MCU frame 0x88 at `EC/EventService.java:3060-3068`). No caller inside eventcenter or canbus2 (`EventServiceProxy.java:832` is a stub). Exact MCU-side semantics UNRESOLVED; treat as "assign function `i` to the SWC key currently being learned".

### 3. Outbound broadcast table (gateway + canbus2)

Prefix legend: `SZ` = `com.szchoiceway.eventcenter.EventUtils.`, `CW` = `com.choiceway.eventcenter.EventUtils.`. P = requires `com.szchoiceway.permission.broadcast`.

| Action | Extras | Source |
|---|---|---|
| `CW`STEER_WHEEL_INFOR (P) | `EventUtils.STEER_WHEEL_INFOR_LPARAM` int 1..10, `..._WPARAM` int 3/4, `..._VOLTAGE` int | EC/EventService.java:2849 |
| `SZ`MCU_KEY_INFOR | `EventUtils.MCU_KEY_VALUE` int | EC/EventUtils.java:2151 |
| `CW`ACTION_HOST_MCU_BUTTON_KEY | `HostKeyWord` int, `HostKeyStatus` byte | :4285 |
| `CW`STEER_WHEEL_STATUS (P) | `EventUtils.STEER_WHEEL_STUDY_STATUS` int | :3065 |
| `CW`CMD_PANEL_STUDY_INFOR (P) | `EventUtils.CMD_PANEL_STUDY_INFOR_DATA` byte[] | :3001 |
| `SZ`ACTION_ACC_OPEN_CLOSE_EVT | `ACC_Status` int 1=on 0=off | :3404-3408 (MCU sys frame 0x71 bit0, handler 305) |
| `SZ`ACTION_ACC_SLEEP_STATUS_EVT | `ACC_Status` int 1=wake (:492, :2274) 0=going to sleep (:3535) | :3392-3401 |
| `com.choiceway.eventcenter.ACTION_BACKCAR_START/END` (P) | none | :8981 |
| `CW`MCU_MSG_BACKCAR_START / `CW`MCU_MSG_BACKCAR_END | none | :809 (handler 250/251) |
| `CW`MCU_MSG_BRAKE_EVT | none (state in SysVar `Sys_Cur_Break_State`) | :547 |
| `SZ`SHOW_CAR_SPEED_EVENT | none | :5214 |
| `com.szchoiceway.ACTION_DAY/NIGHT_BACKLIGHT_CHAGNED` (P) | none. Fired when SysVar `Set_Day_Light` / `Set_Night_Light` (brightness targets) change, NOT on illumination | :4847-4853 |
| `com.szchoiceway.eventcenter.LAMP_STATUS` | none; headlamp state also in SysVar `Sys_LAMP_STAUS_CHECK` "1"/"0" | :802, :2340 |
| `com.szchoiceway.uiModeNightChanged` | `mode` boolean (true = night) | :14089 |
| `SZ`ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT | `Extra_Day_Night_UiMode` int | :14857 |
| `CW`RefreshBacklight | none (after every sendBacklight) | :9658 |
| `CW`MCU_MSG_MAIL_VOL | `CW`MCU_MSG_MAIL_VOL_VAL int = (mute?0x80:0)\|vol, `CW`MCU_MSG_SHOW_VOL_WND bool | :3105, :3120 |
| `com.szchoiceway.SEND_APP_ACTION_EVT` | `VoiceKeyDIM` int, `VoiceKeyMAINVOL` int, `VoiceKeyMUTE` int, `USBState` bool, `SDCardState` bool, `CarOutsideTemp` String, `GpsFixedState`, `AirBagLight` int | :14121-14136 |
| `com.szchoiceway.eventcenter.ZXW_MESSAGE_TO_ICCOMMUNICATION` | `zxw_MessageToListener` String (text protocol, section 6) | :12920 |
| `SZ`MCU_EQ_INFOR (P) | `EventUtils.MCU_EQ_TYPE` int 1 BMT, 2 EQ mode, 3 bal/fad, 4 loudness, 5 | :2904-3031 |
| `CW`ZXW_RADIO_INFO_EVT | `RadioBndNum` int, `RadioTuneNum` int, `RadioCurFreq` int (10 kHz units) | :7630-7636 |
| `SZ`VALID_MODE_INFOR_CHANGE | `Title` `Ablum` `Artist` String; `CurTrack` `TotTrack` `CurFolder` `TotFolder` `CurTime` `TotTime` `LoopMode` `RepeatMode` `PlayStatus` int | :9188-9202 |
| `SZ`BROADCAST_VALID_MODE_EVT | `MODE` int (eSrcMode) | :8822 |
| `SZ`BROADCAST_EXIT_VALID_MODE_EVT | none | :8828 |
| `CAN_CAR_CONSOLE_EVT` (bare string) | `MODE` int | :8042 |
| `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT` (bare string) | `zxw_Launcher` = "AppList" | :8225-8227 |
| `SZ`ACTION_SHOW_CAR_AIR_WND_EVENT | `EventUtils.ACTION_SHOW_WND_DATA` int | :8971 |
| `SZ`ACTION_SHOW_TOOL_NAVI_BAR_WND_EVENT | `EventUtils.ACTION_SHOW_TOOL_WND_DATA` int | :14430 |
| `SZ`MCU_CAR_DOOR_INFO (P) | `EventUtils.CAR_DOOR_DATA` byte; gateway only ever sends 0 (door tips off) | :4747 |
| `CW`MCU_MSG_CAN_ALL_INFO | `EventUtils.CAR_AIR_DATA` byte[] raw MCU frame 0xA5 | :2060-2067 |
| `CW`onCmdMcuATAData (const MCU_MSG_CAN_ATADATA) | `EventUtils.CAR_AIR_DATA` byte[] | :2055 |
| `SZ`ACTION_MCU_8836_VALUE_EVENT | `EventUtils.MCU_8836_VALUE_DATA` byte[] | :1975 |
| `SZ`MCU_CAR_RIGHT_SIGH_EVT | `SZ`CAR_RIGHT_SIGH_EVT_TRA byte | :823 |
| `SZ`ACTION_UPGRADE_DEVANDWIFI_EVT | UPGRADE_DEVSTATE_EVT_TRA byte, UPGRADE_WIFI_EVT_TRA int | :10883 |
| `android.intent.action.I_ACTION_CUSTOM_TYPE_CHANGED` | `CUSTOM_TYPE_CHANGED` | :2539 |
| `CAR_AIR_KEY_KEY` | `car_key_value` int | :1656 |

From canbus2 (all unprotected, `sendBroadcastAsUser(UserHandle.ALL)`, `CB/model/CanDataParseBase.java:1100-1106`):

| Action | Extras | Source |
|---|---|---|
| `SZ`MCU_CAR_CAN_INFO | `EventUtils.CAR_CAN_DATA` byte[3] = [speed km/h, rpmH, rpmL] | CB/model/CanDataParseBase.java:1205-1208; speed from Hiworld frame 0x32 `computeValue(bArr[7],bArr[6])`, rpm bArr[4..5] (HiworldCanParseToyota.java:411-416) |
| `SZ`MCU_CAR_CAN_RADAR_INFO | `EventUtils.CAR_CAN_DATA` byte[9] (section 5) | :1221-1231 |
| `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT` | `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR` String e.g. "23C"/"73F" with degree glyph; the int `..._EXTRA` is never put | :1554, :2355; CB/CanUtils.java:14-15 |
| `CW`ZXW_CAN_WHEEL_TRACK_EVT | `CW`ZXW_CAN_WHEEL_TRACK_EVT_EXTRA int 0..255 | :1317, :1325 |
| `CW`ZXW_CAN_KEY_EVT | `CW`ZXW_CAN_KEY_EVT_EXTRA int (MCU_KEY code) | :1544 |
| `SZ`ACCORD_DOOR_INFO | `EventUtils.CAR_DOOR_DATA` byte: 0x80 FL, 0x40 FR, 0x20/0x10 rear pair (swapped by `Sys_Rear_Door_Tip_Set`), 0x08 tailgate, 0x04 hood | :453-460; CB/ui/door/DoorInfoWindow.java:211-294; Hiworld :923-927 (frame 0x11 byte[6] bits 7..2) |
| `com.choiceway.canbus.carairstruct` | `com.choiceway.canbus.carairstruct.airstate` Parcelable `com.szchoiceway.canbus.CarAirState` | :473-486 |
| `Action_DashboardInfo` | `Extra_DashboardInfo` Parcelable DashboardInfo; Hiworld Toyota never calls `sendDashboard` | :1607-1615 |

Steering angle (`ZXW_CAN_WHEEL_TRACK_EVT`):
```
CB/model/vios/toyota/HiworldCanParseToyota.java:818-829
int i = (bArr[9] & 0xFF) | ((bArr[8] << 8) & 0xFF00);     // frame 0x11 bytes 8..9, signed 16-bit
if ((32768 & i) > 0) iBIT_ON = BIT_ON((0xFFFF - i) / 14, 7);   // negative -> magnitude/14 with bit7 set
else                 iBIT_ON = i2 / 14;
sendWheeltrackInfo(iBIT_ON);                                  // & 0xFF on send
```
So: bit7 = raw was negative, bits0-6 = |raw|/14 (0..127). Gateway divides by 2 and caps at 19 before drawing (`EC/BackcarEvent.java:938-955`); which physical side bit7 means is UNRESOLVED (only `ReverseCarTrackView.setCarAngle(+/-index)` consumes it). Units of `raw` are the launcher's existing `raw/14` OEM scale.

Never sent by anyone: `CAN_BASIC_EVT` (receiver at `EC/EvtModel.java:522` is an empty `return`), `CAN_TPMS_DATA_EVT`, `CAN_SEAT_DATA_EVT`, `CAN_SLS_DATA_EVT`, `CAN_FUEL_CONSUMPTION_INFOR`, `CAN_CENTER_CONSOLE_INFOR` (consts only, `EC/EventUtils.java:186-201`), `CAN_CAR_TIRP_INFO` (const in `EC/Camera360Receiver.java:13`, nothing references it). Trip computer (frame 0x13: fuel economy, range, elapsed, avg speed; `HiworldCanParseToyota.java:504-556`) and TPMS (frame 0x48: kPa = bArr[4..8] + bArr[9..13]; `:353-375`) go only to canbus2's ConsoleActivity through in-process EventBus (`CanDataParseBase.java:1024`). No broadcast. UNRESOLVED as an API; decode the raw CANBOX frames instead.

### 4. Climate

`getAirData` is a stub:
```
EC/EventService.java:1369-1371
public byte[] getAirData(int i, byte[] bArr) {
    return null;
}
```
The MCU air frame handler is also empty (`EC/EventService.java:1890-1891 onCmdCarAirEvent {}`). The only climate read path is the canbus2 Parcelable:
```
CB/model/CanDataParseBase.java:473-486
Intent intent = new Intent(CanUtils.CAN_NEW_CAR_AIR_DATA_INFO_EVT);          // "com.choiceway.canbus.carairstruct"
intent.putExtra(CanUtils.CAN_NEW_CAR_AIR_DATA_INFO, this.mCarAirState);     // "com.choiceway.canbus.carairstruct.airstate"
```
Fields the RAV4 parser fills (`HiworldCanParseToyota.java:929-983`, frame 0x82 offsets in the header-stripped frame): byte2 bit6 `bAirOn`, bit5 `bRearAutoOn`, bit4 `bRearAirOn`, bit3 `bSmallAutoOn`; byte3 bit7 `bRearLock`, bit6 `bAcOn`, bit5 `bRearOn`, bit4 `bMaxFrontOn`, bit3 `bOutCircleOn`, bit2 `bDualOn`, bit1 `bECOOn`, bit0 `bAQSInCircle`; byte4/5 left/right temp -> `m_byLeftTemp`/`m_byRighTemp` **String** ("22.5C"; raw 1 = LO, 255 = HI, else `raw*0.5-40` C, `:1003-1013`); byte6 bit4 `bFunDirectHead`, bit5 `bFunDirectLevel`, bit6 `bFunDirectFoot`, low nibble `byFunStrength`; byte8 low nibble `byRearFunStrength`; byte9 rear temp. Class: `com.szchoiceway.canbus2/sources/com/szchoiceway/canbus/CarAirState.java` (all public fields; copy it verbatim, parcel order = field order in `writeToParcel`).

HVAC **write** path (unprotected broadcast, any app):
```
CB/utils/SendUtil.java (sendDataToCanbus)
bArr2 = {0x0D, 0x08, ...frame};                                   // z=true -> "0D 08" prefix
Intent intent = new Intent(EventUtils.ACTION_MCU_CMD_EVENT);       // "com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT"
intent.putExtra(EventUtils.MCU_CMD_DATA, bArr2);                  // "EventUtils.MCU_CMD_DATA" byte[]
```
```
EC/EvtModel.java:364-373  -> mContext.sendCmdData(byteArrayExtra)   -> EC/EventService.java sendCmdData -> mSendThread (raw to MCU)
```
Toyota HVAC button frame (`HiworldCanParseToyota.java:1784-1941`, `SendCmdLstToCanbus5AA5Header` in `CB/utils/SendUtil.java`): payload `{0x02, 0x3D, code, 0x00}` framed as `5A A5 02 3D code 00 CHK`, `CHK = (sum(02,3D,code,00) - 1) & 0xFF`, then prefixed `0D 08`. `code`: POWER 1, AC 2, SYNC 3, AUTO 4, INNER_LOOP 7, FAN+ 11, FAN- 12, LTEMP+ 13, LTEMP- 14, RTEMP+ 15, RTEMP- 16, LSEAT_HOT 17, RSEAT_HOT 18, MODE 21, LSEAT_COLD 23, RSEAT_COLD 24, DUAL 41, ECO 35, REAR_LOCK 34, FAN_MID 26, FAN_DOWN 29, FAN_UP_DOWN 28, FAN_MID_DOWN 27 (button ids from `CB/CanUtils.java:63-145 CAR_AIR_KEY_*`). Unverified on car; the same channel is what the vendor climate UI uses.

`sendCanbusData(byte[])` (AIDL 48) = raw MCU write, no framing (`EC/EventService.java:9389-9395`); `SendCmdLstToCanbusNor` adds `2E ...chk` then `0D 08` (`:8630-8650`).

### 5. Radar

Broadcast: `com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO`, extra `EventUtils.CAR_CAN_DATA`, 9 bytes, sent by canbus2 only when `Sys_Plugin_radar_Set == 0`:
```
CB/model/CanDataParseBase.java:1221-1229
byte[] bArr3 = {1, head[0], head[1], head[2], head[3], back[0], back[1], back[2], back[3]};
```
Values (Hiworld frame 0x41: rear = bArr[2..5], front = bArr[6..9], level 1..5):
```
CB/model/vios/toyota/HiworldCanParseToyota.java:903-921
if (i2 >= 1 && i2 <= 5) m_stBackRadarInfo[i] = (byte)(i2 * 30);   // 30 = closest ... 150
else                     m_stBackRadarInfo[i] = CMD_CAN_AIR;        // 0xA0 = 160 = clear
```
Consumer polarity: 0 or >160 -> nothing drawn; <=32 red, <=64 orange, <=160 yellow, 160 = idle line (`EC/BackcarEvent.java:960-975`, `com/szchoiceway/view/RadarViewUp.java getPaintColor`). Camera360 rescales x1.59375 to 0..255 (`EC/Camera360Receiver.java:96-104`).

Layout: byte0 = 1 (header), bytes1-4 FRONT, bytes5-8 REAR; sensor index order within a bank is the CAN box's (assume left->right; UNRESOLVED). Scale: distance-like 30..150 in steps of 30, 0xA0 = clear, 0 = no data. Smaller = closer. The MCU's own IR radar (frame 0x8D, `EC/EventService.java:2069-2168`) uses the same 20..160 scale but a different sensor order and only feeds plug-in radar.

Launcher `RadarState.kt` is wrong on: polarity (it treats larger = closer), `LEVEL_MAX = 8` (real values 30..160), `0xFF = clear` (real clear is 0xA0 = 160; 0 = absent), and the action prefix (`com.choiceway` -> `com.szchoiceway`, so it never receives the frame at all).

### 6. Volume push

Three pushes exist, all unprotected broadcasts:
```
EC/EventService.java:3112-3125  notifyMainVolChange(boolean showWnd)   (MCU frame 0x79; mute 0x78 at :3095)
Intent intent = new Intent(EventUtils.MCU_MSG_MAIL_VOL);   // "com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL"
intent.putExtra(EventUtils.MCU_MSG_MAIL_VOL_VAL, (this.mMuteState ? 128 : 0) | this.mCurVol);   // "...MCU_MSG_MAIL_VOL_VAL"
intent.putExtra(EventUtils.MCU_MSG_SHOW_VOL_WND, z);                                             // "...MCU_MSG_SHOW_VOL_WND"
```
plus `SEND_APP_ACTION_EVT` (`VoiceKeyMAINVOL`, `VoiceKeyMUTE`, section 3) and the text line:
```
EC/EventService.java:13899-13901  showVolState(boolean mute, int vol) -> sendMessageToListener("SYSTEM_VOLUME:true|false,<vol>")
EC/SocketUtils.java:78-84   String.format("SYSTEM_VOLUME:%s,%d", mute, vol)
EC/EventService.java:12917-12925  sendMessageToListener -> broadcast ZXW_MESSAGE_TO_ICCOMMUNICATION, extra "zxw_MessageToListener"
```
The "LocalSocket" is dead: `LocalSocketServer` (name `com.szchoiceway.LocalServerSocket`, `EC/LocalSocketServer.java:24`) is never instantiated, and `addMessageListener(ICommunication)` (`:12907-12914`) stores listeners that nothing calls (`notifyMessage` has no caller). Every `SocketUtils.MSG_*` line (`EC/SocketUtils.java:8-26`: `CAR_ACC_STATUS:`, `BACK_LIGHT_LEVEL:`, `CAR_LIGHT_LEVEL:`, `CURRENT_MODE_INFO:`, `START_MODE_ACTIVITY:`, `MCU_ENCODE_STATE:`, `BT_CONNECT_STATUS:`, `BT_PHONE_NUMBER:`, `START_STOP_STATE:`, ...) rides that broadcast as a String.

Note `sendVolState(boolean, int)` (AIDL 77) ignores the boolean and sends MCU setup 5 = volume (`:4384-4388`), which echoes back as frame 0x79 -> the push above.

### 7. `ICallbackfn` registrars

`ICallbackfn` = `{ void notifyEvt(int what, int arg1, int arg2, byte[] data, String str); boolean checkIsActive(); }` (`EC/ICallbackfn.java:28-30`). **`checkIsActive()` is never called by the service** (grep: only stubs in `EvtModel.java:114-155`, `ZlinkManage.java:58`). Return value is irrelevant.

| Registrar (AIDL) | Field | Events delivered |
|---|---|---|
| `setRadioCallback` (29), `:7554` | `mRadioCallbackfn` | `notifyRadioEvt` `:7639`: what 0 = state bits (arg2 = b3<<8\|b2: RDS/PTY/AF/TA/ST/LOC/AMS/APS flags, `:2775-2782`), 1 = band (arg2), 2 = preset number (arg2), 3 = current freq (arg2, 10 kHz), 4 = preset list entry (arg1 = index 0..41, arg2 = freq), 5 = PTY (arg2), 6 = PS name (str) |
| `setCurModeCallback(int mode, cb)` (30), `:8831-8900` | `mValidModeCallbackfn` (only if mode <= SRC_AUX=40) | `notifyValidModeEvt` `:8948`: 4096 EVENT_START, 4097 MODE_CHANGE (arg2 = new mode; arg1=1 for IDLE), 4098 KEY (arg2 = MCU_KEY code), 4099 BACKCAR_START, 4100 BACKCAR_END, 4101 BT (arg1 = >=4 ? 1:0, arg2 = state), 4102 WND_IN_TOP, 4104 BREAK_STATE (arg2 1/0), 4108 NAV_SOUND (arg2 1/0). Consts `EC/EventUtils.java:1208-1219` |
| `setCamAuxCallback(int, cb)` (66), `:9292-9310` | `mCarCamCallbackfn` | Same stream as above (called first in `notifyValidModeEvt`), plus 4097 with arg2 = 0 / SRC_NULL on every `sendMode` <= SRC_AUX (`:3954`, `:598`) |
| `setCanA5Callback` (`:7569`), `setCanA6Callback` (`:7584`) | `mCanA5Callbackfn`, `mCanA6Callbackfn` | Never fired: `notifyCanA5DataEvt` (`:7662`) and `notifyMcuATADataEvt` (`:7650`) have no callers |
| `addMessageListener(ICommunication)` (133) | `mCommunicationListener` | Never fired (section 6) |

Registering as `setCurModeCallback(mode)` changes `mValidMode`, broadcasts BROADCAST_VALID_MODE_EVT and runs `kill3rdAPK()` (`:8879-8881`), so it is a mode claim, not a listen.

### 8. Mode switching

```
EC/EventService.java:11802-11806 (Stub)   sendMode(int i, boolean z) -> sendMode(eSrcMode.valueOf(i), z)
EC/EventService.java:3933-3958
if (esrcmode.getIntValue() <= SRC_AUX && (mCustomerType != 53 || !top.startsWith("com.linkswell.sxmradio"))) { kill3rdAPK(); }
bArr[0] = 1; bArr[1] = esrcmode.getValue();          // MCU frame 01 <mode byte>
if (z) sendThread.sendDataWaitAck(bArr); else sendThread.notifyToSend(bArr);
if (mode <= SRC_AUX) notifyCamAuxEvent(4097, 0, 0, null, null);
```
Boolean = wait for the MCU's ACK (frame 0x70) before returning. `SRC_CARPLAY` is refused while `Sys_CarAuto_Radio_Running`.

`kill3rdAPK` (`:8289-8345`): force-stops every running task whose package is not in `sysApkLst`, not the nav package, and not a system app. Gated by
```
EC/EventService.java:8294   if (!this.mIsAndroidAudioMngStandard || z2) { ...kill... }
EC/EventService.java:6750   mIsAndroidAudioMngStandard = getRecordBoolean(SYS_SOUND_MANAGER_TYPE, false);   // "Sys_SoundManager_Type"
EC/EventService.java:6581   setRecordDefaultValue(SYS_SOUND_MANAGER_TYPE, "1");
```
Default is "1" -> true -> `kill3rdAPK` is a no-op on a stock unit unless the SysVar was set to 0. Re-read on every SysVar change (`:4910`, `:5446`, `:5979`).

`postRunModeActivity(int)` (`:7993-8003`): if no BT call (`mBTConnectState < 4`) or mode in {GPS, BT, APPLIST}: broadcast `com.megaview.avm.window_hide`, then `startModeActivity(i)` (`:8103-8215`) which launches the mode's package (SRC_RADIO -> `Set_CustomizedRadioPackage`, MUSIC/MOVIE -> customised players, SRC_BT/BTMUSIC -> btsuite, SRC_CARCONSOLE -> canbus2 ConsoleActivity, SRC_APPLIST -> customerui activity or the ALLAPPS broadcast, SRC_SETUP -> settings + `sendMode(SRC_MCU_VERSION)`, NONE/NULL -> `sendMode(SRC_NULL)`). It does not itself claim audio; the launched app calls `setCurModeCallback` + `sendMode`.

`notifyModeKeyEvt(int key)` (`:9555`) = `notifyValidModeEvt(4098, 0, key)` -> delivers an MCU key to the current mode's callback AND broadcasts `MCU_KEY_INFOR`. Any app can inject a key this way (ordinal 59).

`getValidMode()` (`:9165`) = `mValidMode.getIntValue()`. `exitCurMode(int)` (`:8916-8945`): no-op unless `i == mValidMode`; then clears callback, `mValidMode = SRC_NULL(99)`, writes `Sys_Last_Mode`, `sendMode(SRC_NULL,false)`, broadcasts EXIT_VALID_MODE + VALID_MODE.

`eSrcMode` (`EC/EventUtils.java`, `getValue()` = `(byte)(value & 0xFF)`, `valueOf(unknown)` = SRC_NONE):
NONE 0, RADIO 1, DVD 2, USB 3, CARD 4, IPOD 5, BT 6, BTMUSIC 7, CMMB 8, TV 9, MOVIE 10, MUSIC 11, EBOOK 12, IMAGE 13, ANDROID 14, VMCD 15, NETWORK 16, CARMEDIA 17, CAR_BT 18, HDMI 19, CARCONSOLE 30, PHONELINK 31, CARPLAY 32, DAB 34, SXM 39, AUX 40, BACKCAR 41, GPS 42, HOME 43, REHOME 44, COMPASS 45, STANDBY 46, EQ 47, BACKLIGHT_SET 48, SETUP 49, FCAM 50, RCAM 51, BCAM 52, DVR 53, CUSTOMIZE 60-66, MCU_VERSION 80, TW8823_VERSION 81, NULL 99, POWERON 100, POWEROFF 101, MIX_GPS 102, IDLE_MODE 103, IDLE_MODE_RELEASE 104, DONGHUA_END 105, CARAIR 150 (0x96), BT_ECAR 152, EXPLORER 153, APPLIST 154, CAR_AUX 155, FILE_MANAGER 156, BT_ONLY 157, PIC 158, MORESETTING 159. "Audio source" modes are those <= 40.

### 9. Other gateway facilities

- **Backlight**: `sendBacklight(byte day, byte night)` (AIDL 60) -> MCU `{0x2E, day, night, fineLow(Set_Back_Light_fine_Low_Key def 80), fineHi(def 200), 0}` then RefreshBacklight broadcast (`EC/EventService.java:9634-9659`). Day/night are 1..20 (`adjustBLLevel`, `:9560` range check), SysVars `Set_Day_Light` (def 18) / `Set_Night_Light` (def 8); which one is live follows `mLAMPConnected`. MCU keys 262/263 step it. `setSystemBrightness()` (68) is a no-op on Android 13 (`if (SDK_INT <= 27)`, `:9690-9700`).
- **Illumination**: MCU sys frame 0x71 byte1 bits: 0x80 disc, 0x08 headlamp (`mLAMPConnected`), 0x04 brake, 0x02 reverse, 0x01 ACC (`:2290-2400`). Headlamp -> `LAMP_STATUS` broadcast + `Sys_LAMP_STAUS_CHECK`, and day/night when `Sys_Day_Night_Mode == 0`.
- **ACC / sleep**: `Sys_Acc_Delay` -> MCU `{0x49, 0x17, v/60, v%60}` (`sendAccDelayTime`); ACC off -> `setAccSleep` sends `ACTION_ACC_SLEEP_STATUS_EVT` 0 + `EventUtils.EVENT_DISCONNECT_BT` (`:3535`); MCU wake frame 0x96 -> sleep status 1 (`:2270`).
- **Beep**: `beep()` (7) -> MCU `{0x06}` only if key-beep enabled (`:4308`). **Mute**: `sendMuteState(bool)` (8) -> `{0x0A, 0/1}` (`:4319`), state echoes as frame 0x78 -> MAIL_VOL broadcast. **Play state**: `sendPlayState` -> `{0x19, !playing}`.
- **Reboot / reset**: `sendSoftWareReboot()` (135) -> `{0x49,0x1B,0x01}`; `sendSystemReset()` (116) -> `{0x3B,0x00}` (MCU reset); factory reset = Android `FACTORY_RESET` broadcast (`EC/EventUtils.java:2747`); `ZXW_ACTION_REBOOT_SYS_REBOOT` (bare string) restarts the gateway service (`EvtModel.java:1082`). Power-off: MCU key 1 -> `sendMode(SRC_POWEROFF)` x5 after `sync` (`:2699-2727`).
- **Standby / black screen**: keys 247/252/21; `setSysStandyMode`, `setSysBlackScreenState`; `MCU_KEY_DIM` 246.
- **Upgrade**: `SendARMUpgrade`, `enterUpgradeMode`, `waitCanEnterUpgradeMode` (`:6361`), `ACTION_UPGRADE_DEVANDWIFI_EVT`; `isSystemUpgrade` suppresses screensaver etc.
- **Task list / gestures**: `SZ`ACTION_SHOW_TASK_LIST (in), `com.szchoiceway.eventcenter.Gestures` (in).
- **`Sys_CarType`** -> `mCanCarType` int, compared against `CARTYPE_*` (`EC/EventUtils.java:318-940`, e.g. 281 special-cases keys, 178/179 instrument animation) and passed to `setTrackData` to pick trajectory art. No RHD flag; it is a CAN-protocol/model id.
- **`Sys_CustomerType`** -> `mCustomerType` (default 88, `:332`, read `:6868`). 53 = an OEM build that gets `CustomStatusbar`, `CURRENT_MODE_INFO:` text messages, `com.linkswell.sxmradio` kill exemption and USB multi-camera (`:7108`, `:8843`, `:8935`, `:14247`, `:14283`); 58 = original-car amplifier (HOST_MCU_BUTTON_KEY relay); 13 = instrument-panel animation.
- **MCU write from any app**: `ACTION_MCU_CMD_EVENT` broadcast (section 4) is unprotected and goes straight to the serial port.

### Corrections to the launcher

| Launcher constant / claim | Wrong | Correct |
|---|---|---|
| `GatewayHandshake.UIMODE_EXTRA_KEYS` (booleans "uiMode","UIMODE","EventUtils.UIMODE","uiModeNight") | keys and type | one **int** extra `"Extra_Day_Night_UiMode"`, 1 day / 2 night / 3 auto / 0 headlamp. Sending booleans makes the gateway apply mode 0 |
| `GatewayHandshake` "gateway->launcher arrival proves the channel is live" | it is a day/night request needing an echo, sent only on Sys_Day_Night_Mode change (portrait) or headlamp change (landscape) | treat as request; reply with the same int, or the gateway applies it itself after 2 s |
| `CarEvents.ACTION_ACC_OPEN_CLOSE_EVT`, `ACTION_ACC_SLEEP_STATUS_EVT` `com.choiceway.eventcenter.EventUtils....` | prefix | `com.szchoiceway.eventcenter.EventUtils....` (EC/EventUtils.java:42,44) |
| `AccSleep` "value encoding unconfirmed" | - | `ACC_Status` 1 = awake/ACC on, 0 = entering sleep |
| `CarEvents.MCU_KEY_INFOR_ACTION` `com.choiceway...MCU_KEY_INFOR` | prefix | `com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR` (:1521) |
| `CarEvents.MCU_CAR_CAN_RADAR_INFO` `com.choiceway...` | prefix | `com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO` (:1444) |
| `CarEvents.CAN_BASIC_EVT`, `CAN_TPMS_DATA_EVT`, `CAN_SEAT_DATA_EVT`, `CAN_SLS_DATA_EVT`, `CAN_FUEL_CONSUMPTION_INFOR`, `CAN_CENTER_CONSOLE_INFOR` `com.choiceway...` | prefix, and nothing sends them | `com.szchoiceway.eventcenter.EventUtils....`; drop the sniffers |
| `CarEvents.CAN_CAR_TIRP_INFO` | never sent | remove; trip data is EventBus-internal to canbus2 |
| `CarEvents.ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT` + `EXTRA_LAUNCHER = "LAUNCHER_EXTRA"` | action and extra | action is the bare string `"ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT"`, extra `"zxw_Launcher"` = `"AppList"` (:74, :1406) |
| `CarEvents.CAR_AIR_STATE_ACTION` `com.szchoiceway.canbus.carairstruct` | prefix | `com.choiceway.canbus.carairstruct` (CB/CanUtils.java:48); extra key is right |
| `AIR_BYTE_EXTRA_KEYS` | no byte[] is ever attached | ship `com.szchoiceway.canbus.CarAirState` and read the Parcelable |
| `ClimateState.fromAirData` / `CarService.getAirData()` | `getAirData` returns null | no AIDL climate read; use the Parcelable |
| `OUT_TEMP_INT_EXTRA_KEYS` / `OUT_TEMP_STR_EXTRA_KEYS` | wrong strings | String extra `"com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR"` (already unit-suffixed); int form never sent |
| `ZXW_CAN_WHEEL_TRACK_EVT` "extras undocumented" | - | int under `"com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT_EXTRA"`: bit7 sign, bits0-6 = \|raw\|/14 |
| `RadarState` layout/polarity | polarity, LEVEL_MAX 8, 0xFF clear | byte0 = 1; 1-4 front, 5-8 rear; 30 closest ... 150, 0xA0 clear, 0 none; smaller = closer |
| `SwcFallback.hostKey` expects 3/4 and CAR_KEY codes | `HostKeyStatus` is 1 down / 0 up; `HostKeyWord` in {2 vol-, 3 vol+, 4 mute, 1}; only sent with original-amp routing | drop it as a wheel path |
| `SwcFallback.mcuKey` translates only 76/77/78 | codes are `MCU_KEY_*` (EC/EventUtils.java:1458-1656): HOME 9 (MENU), BACK 85 (RETURN), NEXT 2, PREV 3, PLAYPAUSE 6, VOL+/- 18/19, MUTE 17, TALK 23, HANGUP 22, MODE 16, VOICE 116 | map the real table; `MCU_KEY_SYS_*` 76-79 never appear |
| `SwcFallback` "edge encoding UNCONFIRMED" | - | `MCU_KEY_INFOR` has no edge; one broadcast per press, long presses are separate codes |
| `CarEvents.ACTION_DAY/NIGHT_BACKLIGHT_CHANGED` used as illumination (`illuminationSeen`) | fires on brightness-slider SysVar change, not headlamps | use `com.szchoiceway.uiModeNightChanged` (`mode` bool), `com.szchoiceway.eventcenter.LAMP_STATUS` + SysVar `Sys_LAMP_STAUS_CHECK`, or `UiModeManager` |
| README "No volume event" / StatusIndicators poll | - | `com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL` (`...MAIL_VOL_VAL` int, bit7 mute), `com.szchoiceway.SEND_APP_ACTION_EVT` (`VoiceKeyMAINVOL`, `VoiceKeyMUTE`), and `ZXW_MESSAGE_TO_ICCOMMUNICATION` `"SYSTEM_VOLUME:<mute>,<vol>"` |
| StatusIndicators `addMessageListener(ICommunication)` channel | never fed | the text protocol rides the broadcast above; LocalSocketServer is never started |
| CAR_API 3.3 "LocalSocket secondary channel" | dead code | see section 6 |
| `CarService.claimRadio` / `sendMode` "runs kill3rdAPK unless Sys_SoundManager_Type set" | default "1" disables the kill | on a stock unit `kill3rdAPK` is a no-op; only `Sys_SoundManager_Type=0` enables it |
| `CarService.sendBacklight(level 0..255, mode)` | params are (day, night) each 1..20 | `sendBacklight(day, night)`; pass both current SysVar values |
| `CarService.applySystemBrightness()` | no-op on SDK > 27 | remove |
| README "Vendor sendMode value table" TODO | - | section 8 enum |
| `CarService` "SRC_RADIO twice because vendor does" | first call waits for ACK when `z=true`; vendor passes false | one `sendMode(1,true)` suffices |
| `ICallbackfn.checkIsActive` semantics | never called | return anything |
| CAR_API 1.3 `MCU_CAR_CAN_INFO` "bulk CAN frame" | it is 3 bytes `[speed km/h, rpmH, rpmL]` from canbus2; `MCU_MSG_CAN_ALL_INFO` is the raw MCU 0xA5 passthrough | speed is available without decoding the CANBOX stream |
| `SteeringReading` "sign convention unconfirmed" | still unresolved | bit7 of the WHEEL_TRACK extra = negative raw; left/right not settled in the decompile |
| `VendorLauncher`/`CustomStatusbar` assumptions about customer 53 | - | 53 is an OEM variant flag; RAV4 unit's value must be read from `Sys_CustomerType` (default 88) |

---

## 4. Settings, CAN box and Bluetooth (`settings`, `canbus2`, `btsuite`)

Paths: `D=decompiled`, `EC=$D/com.szchoiceway.eventcenter/sources/com/szchoiceway/eventcenter`,
`ST=$D/com.szchoiceway.settings/sources/com`, `CB=$D/com.szchoiceway.canbus2/sources/com/szchoiceway/canbus2`,
`BT=$D/com.szchoiceway.btsuite/sources/com/szchoiceway`.
Launcher: `L=launcher`.

Global fact that shapes several answers: the settings app never writes the SysVar provider directly. Every write goes
through the gateway AIDL `IEventService.changeSetup(key, value)`; the provider is the fallback only when unbound.

`ST/zxw/lib/ui/util/SystemPropertiesHelps.java:54-62`
```java
public static boolean updateRecord(String str, String str2, boolean z) {
    ...
    if (z && EventServiceHelps.getInstance().getIEventService() != null) {
        EventServiceHelps.getInstance().changeSetup(str, str2);
    } else {
        getProvider().updateRecord(str, str2);
    }
```
Bind target: `ST/zxw/lib/ui/service/EventServiceHelps.java:5-6` `SERVICE_ACTION = "com.szchoiceway.eventcenter.EventService"`, `SERVICE_PACKAGE_NAME = "com.szchoiceway.eventcenter"`; `changeSetup` is txn 78 (`ST/szchoiceway/eventcenter/IEventService.java:692,968`).
The gateway's `changeSetup` handler is the giant key switch in `EC/EventService.java:4660-6200`; a raw provider write is
also observed (ContentObserver) but the AIDL path is what the vendor UI exercises.

---

### 1. VendorChrome: tool/nav bar hide keys

**`SYS_SHOW_TOOL_NAVI_BAR_WND` is a SystemProperties key, not a SysVar row, and it is an OUTPUT (state mirror), not an input.**

`EC/SysProviderOpt.java:422`
```java
public static final String SYS_SHOW_TOOL_NAVI_BAR_WND = "SYS_SHOW_TOOL_NAVI_BAR_WND";
```
`EC/EventService.java:14425-14434`
```java
private void showToolNaviAndStatusWnd(boolean z) {
    if (this.isLandRoverShowToolBar != z) {
        this.isLandRoverShowToolBar = z;
        SystemProperties.set(SysProviderOpt.SYS_SHOW_TOOL_NAVI_BAR_WND, z ? "1" : "0");
        synchronized (this.mShowTool) {
            Intent intent = new Intent(EventUtils.ACTION_SHOW_TOOL_NAVI_BAR_WND_EVENT);
            intent.putExtra(EventUtils.EXTRA_SHOW_TOOL_WND_DATA, z ? 1 : 0);
            sendBroadcastAsUser(intent, UserHandle.ALL);
```
Constants: `EC/EventUtils.java:96` action `"com.szchoiceway.eventcenter.EventUtils.ACTION_SHOW_TOOL_NAVI_BAR_WND_EVENT"`, `:1247` extra `"EventUtils.ACTION_SHOW_TOOL_WND_DATA"` (int 0/1). Nothing reads the property or the SysVar back (grep `SHOW_TOOL_NAVI_BAR_WND` over all decompiles: only `:14244`, `:14428`).

The gateway's own floating tool/nav/status windows exist only for specific `Sys_UINumber` / `Sys_CustomerType` values:

`EC/EventService.java:14226-14257`
```java
public void initFloating() {
    int i = this.mUINumber;
    if (i == 108 || i == 127 || i == 126) {          // UI_NUM_CHWY_UI4, _1600x720, _NAVI
        ToolNavibar toolNavibar = new ToolNavibar(this); ... toolNavibar.showView();
        ToolStatusbar toolStatusbar = new ToolStatusbar(this); ... showView();
        SystemProperties.set(SysProviderOpt.SYS_SHOW_TOOL_NAVI_BAR_WND, "1");
    }
    if (this.mCustomerType == 53) { this.mSystemStatusbar = new CustomStatusbar(this); }   // CUSTOMER_NUM_SIYI
    if (this.mUINumber == 44) { ... ToolCKNavibar ... ToolCKToolbar ... }                  // UI_NUM_CARCOOL_LANDROVER
```
`EC/EventService.java:14271-14273` `if (this.mUINumber == 10008) { new SideWindow(this, true); }` (Bentley). Numbers: `EC/customer/Customer.java:95-99,87,118,36`.
Runtime toggle is `controlToolNavibar(boolean)` (`:14313`), driven by foreground-app tracking (`:934`), no key.

On a default-skin unit none of those windows exist. The "vendor nav bar" is then Android's own navigation bar, sized and
enabled by the gateway from SysVar at boot and on key change:

`EC/utils/SystemUtils.java:90-95`
```java
int recordInteger3 = ...getRecordInteger(SysProviderOpt.SYS_CUSTOMER_NAVIBAR_HEIGHT_KEY, 0);
if (recordInteger3 > 0 && recordInteger3 != 110) {
    SystemProperties.set("persist.sys.show_navigationbar.always", "1");
} else {
    SystemProperties.set("persist.sys.show_navigationbar.always", "0");
```
`EC/utils/SystemUtils.java:112-132` (Sys_Landscape=1 branch)
```java
SystemProperties.set("persist.sys.show_statusbar", "1");
SystemProperties.set("persist.sys.show_navigationbar", "0");
...
if (recordInteger3 > 0) {
    SystemProperties.set("persist.sys.show_navigationbar", "1");
    SystemProperties.set("persist.sys.navigation_bar_height_landscape", recordInteger3 + "");
} else {
    SystemProperties.set("persist.sys.show_navigationbar", "0");
    SystemProperties.set("persist.sys.navigation_bar_height_landscape", "0");
```
`:134-141` (Sys_Landscape=0): both `persist.sys.show_statusbar` and `show_navigationbar` forced `"1"`, heights `-1`.
Key: `EC/SysProviderOpt.java:283` `SYS_CUSTOMER_NAVIBAR_HEIGHT_KEY = "Sys_Customer_NaviBar_Height_Key"` (int px; factory options 0 = "No bottom bar", 170/212/220/270, seekbar max 500: `ST/szchoiceway/factory/FactorySetFragment.java:170-215`; companion string `Sys_Customer_NaviBar_Height_Key_str`, `ST/.../SystemPropertiesHelps.java:713`). Changing it re-runs `initNaviAndStatusBarHeight` live (`EC/EventService.java:5125,5172`); the vendor UI still toasts "restart system" (`ST/szchoiceway/factory/FactorySetItemCkbView.java:103-104`).

Status bar: `Sys_customer_statusbar` (`EC/SysProviderOpt.java:285`) is written by the gateway itself (`EC/utils/SystemUtils.java:84,87,164`), never read as a switch; `persist.sys.show_statusbar` is always set to `"1"` in every branch. No SysVar hides the status bar.

`Sys_Statusbar_Icon_Config_Key` (`EC/SysProviderOpt.java:430`) has **no reader anywhere** (grep all decompiles: only constant definitions in seven SysProviderOpt copies). `DEFAULT_ICON_CONFIG` pairs with `Sys_Function_Icon_Config_Key` (`EC/customer/Customer.java:51,201`; writer `EC/utils/SystemUtils.java:647`), which is the home-page icon list, not the status bar.

Verdict: `VendorChrome.KEY_SHOW_NAVI_BAR` name is right but the mechanism is wrong (sysprop, write-only mirror, only for UI 108/126/127). `KEY_STATUSBAR_ICON_CONFIG` is dead. The real persistent switch for the bottom bar is `Sys_Customer_NaviBar_Height_Key = 0` (needs `Sys_Landscape = 1`, key `EC/SysProviderOpt.java:335`). UNRESOLVED: our unit's `Sys_UINumber` value (not in the decompile; read the row on-device).

---

### 2. RootTier keys

`L/app/.../data/RootTierController.kt:39-40` and `RootTierSettingsScreen.kt:152-155` only reuse `VendorChrome.KEYS`, so the GUESSED set is exactly `Sys_Statusbar_Icon_Config_Key` and `SYS_SHOW_TOOL_NAVI_BAR_WND`. See §1: first is unread, second is a sysprop. `presentKeys()` (`VendorChrome.kt:78`) will report both absent on a stock table unless something else inserted them; the feature is a no-op by design then.

Other launcher keynames checked against `EC/SysProviderOpt.java` / `ST/.../SystemPropertiesHelps.java`:
- `SettingKeys.UI_NUMBER_KEY = "uiNumberKey"` (`L/app/.../data/SettingKeys.kt:110`) is WRONG. `uiNumberKey` is the helper method name; the stored keyname is `"Sys_UINumber"`:
  `ST/zxw/lib/ui/util/SystemPropertiesHelps.java:378-379`
  ```java
  public static int uiNumberKey(boolean z, int i) {
      int iPublicRecord = publicRecord("Sys_UINumber", z, i, true);
  ```
  `EC/SysProviderOpt.java:458` `SYS_UI_NUMBER_KEY = "Sys_UINumber"`. Same error in `CUSTOMERUI_NOTES.md:26,106,126`.
- All other `SettingKeys` strings match `EC/SysProviderOpt.java` (spot-checked lines 33,65,80-81,219,233-234,273,275,287,338,372,400,424-425).

---

### 3. Power / sleep keys

| Key | Type / domain | Who writes | What the gateway does |
|---|---|---|---|
| `SET_ACC_ON_DELAY` | int seconds 0..7 | user page "ACC on delay" | packed as `& 7` into MCU frame 0x10 byte 5 |
| `Sys_Acc_Delay` | int seconds (sent as min:sec) | nothing in settings app | MCU frame `49 17 mm ss` |
| `ACC_OFF_DELAY` | never read; default "0" | nothing | change -> `sendFactoryMcuSet()` |
| `Sys_Power_Off_Delay` | boolean 0/1 (factory checkbox "ACC off delay") | factory | bit1 of factory MCU byte 8 |
| `Sys_Sleep_Switch` | boolean 0/1 (factory), default 1 on SDK>27 | factory | bit4 of factory MCU byte 10 |
| `SYS_SLEEP_TIME` | enum 1/2/3/other -> 960/1440/2880/480; default "2" | nothing in settings app | MCU frame `49 05 hi lo` |
| `Sys_Screen_Off_When_Acc_Change` | boolean, default 1 (factory "screen and ACC") | factory | `sendFactoryMcuSet()` |
| `SYS_AUTO_START_SCREENSAVER_TIME` | seconds in {0,60,300,600,1800} | user page | handler 319 after n*1000 ms |
| `SYS_AUTO_START_CLOSE_SCREEN_TIME` | seconds in {0,60,300,600,1800} | user page | handler 320, only customer 69 |

Evidence:

ACC-on delay options 0s..7s: `ST/szchoiceway/view/ItemTextRightCheckBoxView.java:450-486`
```java
protected boolean clickOpenAccDelayedTime(PageInfoBean pageInfoBean) {
    ...
    case R.string.str_0s: i = 0; break;
    case R.string.str_1s: i = 1; break;
    ...
    case R.string.str_7s: i = 7; break;
    }
    ...
    SystemPropertiesHelps.I.set_acc_on_delay(true, i);
```
Consumed: `EC/EventService.java:9976`
```java
byte[] bArr = {16, (byte) ...SYS_VOLUME_FADER..., (byte) ...SYS_SUOLUODE_SCREEN_PARAMETER..., b, (byte) (iBIT_OFF3 & 255), (byte) (this.mSysProviderOpt.getRecordInteger(SysProviderOpt.SET_ACC_ON_DELAY, 0) & 7), 0, 0, 0, 0, 0};
```

`Sys_Acc_Delay` in seconds: `EC/EventService.java:3169-3172`
```java
private void sendAccDelayTime() {
    int recordInteger = this.mSysProviderOpt.getRecordInteger(SysProviderOpt.SYS_ACC_DELAY, 0);
    byte[] bArr = {73, 23, (byte) (recordInteger / 60), (byte) (recordInteger % 60)};
```
Triggered on change `:4923-4925`. No settings-app writer (grep `Sys_Acc_Delay|SYS_ACC_DELAY` in `$ST`: none).

`SYS_SLEEP_TIME` is an enum, not minutes: `EC/EventService.java:9361-9371`
```java
public void sendSleepTime(int i) {
    int i2;
    if (i == 1) { i2 = 960; }
    else if (i != 2) { i2 = i != 3 ? 480 : 2880; }
    else { i2 = 1440; }
    byte[] bArr = {73, 5, (byte) ((i2 >> 8) & 255), (byte) (i2 & 255)};
```
Default `:6540` `setRecordDefaultValue(SysProviderOpt.SYS_SLEEP_TIME, "2")`. Units of 960/1440/2880/480 are UNRESOLVED (MCU side; 1440 = minutes in a day is the plausible read: 1=16 h, 2=24 h, 3=48 h, else 8 h).

Booleans: `EC/EventService.java:10186` `if (this.mSysProviderOpt.getRecordBoolean(SysProviderOpt.SYS_POWER_OFF_DELAY, false)) { iBIT_OFF15 = BIT_ON(b18, 1);`; `:10228` `if (...getRecordBoolean(SysProviderOpt.SYS_SLEEP_SWITCH, false)) { iBIT_OFF21 = BIT_ON(0, 4);`; `:10033` `SYS_SCREEN_OFF_WHEN_ACC_CHANGE` read as boolean default true. All three are rendered by `FactorySetItemCkbView` (checkbox rows: `ST/szchoiceway/factory/FactorySetFragment.java:94-96,114-120`; handler `FactorySetItemCkbView.java:118,170,180`). Defaults `:6538-6541,6647,6706`.

Screensaver / close-screen options: `ST/szchoiceway/view/ItemTextRightCheckBoxView.java:644-664,672-692`
```java
case R.string.str_10_min: i = 600; break;
case R.string.str_1_min:  i = 60;  break;
case R.string.str_30_min: i = 1800; break;
case R.string.str_5_min:  i = 300; break;
case R.string.str_never:  i = 0;   break;
...
SystemPropertiesHelps.I.sys_auto_start_screensaver_time(true, i);
```
Keynames `ST/.../SystemPropertiesHelps.java:618-624`; consumer `EC/EventService.java:14380-14394` (`sendEmptyMessageDelayed(319, recordInteger * 1000)`; close-screen only `if (this.mCustomerType == 69)`).

Corrections for `PowerSettingsScreen.kt`: `SET_ACC_ON_DELAY` range is 0..7 s (not 0..30); `ACC_OFF_DELAY` has no value semantics; `Sys_Power_Off_Delay` is a 0/1 flag, not 0..60; `SYS_SLEEP_TIME` is 1/2/3 enum, not 1..60 min; `Sys_Sleep_Switch` is correct as boolean. Missing: `SYS_AUTO_START_SCREENSAVER_TIME`, `Sys_Screen_Off_When_Acc_Change`.

---

### 4. Sys_CarType / Sys_CustomerType domains, RHD

Three keys select the car, all read by canbus2 at parser init: `CB/services/CanbusDataService.java:1234-1239`
```java
int recordInteger  = ...getRecordInteger(SysProviderOpt.SYS_CAMRY_AIR_SUPPLIER_KEY, 0);   // "Sys_camry_air_Supplier_id" = CAN box vendor
int recordInteger2 = ...getRecordInteger(SysProviderOpt.SYS_CAR_VEHICLE_DERIES_KEY, 0);   // "Sys_Vehicle_deries" = make
int recordInteger3 = ...getRecordInteger(SysProviderOpt.SYS_CAR_TYPE_KEY, 0);             // "Sys_CarType" = model within make
int recordInteger4 = ...getRecordInteger(SysProviderOpt.SYS_CUSTOMER_TYPE_KEY, 0);
int recordInteger5 = ...getRecordInteger(SysProviderOpt.SYS_CARINFOR_ID, 0);              // "Sys_CarInfor_ID" = year/trim
```
Vendor (supplier) 1..18: `:1240-1293` (1 Simple, 2 Xinbas, 3 Luzheng, **4 Raise**, 5 LianHangTong, **6 Vios/Hiworld**, 7 RuiDaWei, 8 XinCheng, 9 ChangYuanTong, 10 OD, 11 Ruishengwei, 12 TangDu, 13 XFY, 14 XinChi, 15 HaoShouYin, 16 KeYiChuang, 17 ZhiYin, 18 SanWu). Same names as the asset headings `$D/com.szchoiceway.canbus2/resources/assets/cartype/simple_cartype_en.json:26-27,4030-4031,11120-11121`.

Make: `CB/bean/CanConstantInfo.java:602-650`, `VEHICLE_DERIES_TOYOTA = 1` (`:645`), `_HONDA = 7`, `_VW = 8`, `_FORD = 2`, `_NULL = 0`.
Model (Toyota family): `CB/bean/CanConstantInfo.java:497-543`
```java
public static final int CARTYPE_TOYOTA_CAMRY = 1;
public static final int CARTYPE_TOYOTA_RAV4 = 2;     // :543
public static final int CARTYPE_TOYOTA_COROLLA = 5;
public static final int CARTYPE_TOYOTA_HIGHLANDER = 7;
public static final int CARTYPE_TOYOTA_CHR = 10;
```
Year (RAV4, value of `Sys_CarInfor_ID`): `CB/bean/YearType.java:1017-1034` `TOYOTA_RAV4_TYPE_2013 = 0`, `_2016 = 1`, `_13_18 = 2`, `_16_19_RONGFANG = 3`, `_20 = 4`, `_19_TW = 5`, `_09_13 = 6`, `_13_16 = 7`, `_2022 = 8`, `_16_21 = 9`, `_22_PRESENT = 10`, `_13_PRESENT_NA = 11`, `_19_PRESENT_TW = 12`, `_24_HI = 16`, `_24_LO = 17`. Which subset is offered depends on the vendor section of the JSON (Hiworld section at `simple_cartype_en.json:12977-12990`: 0 "Fully compatible", 7 "13-16", ...).

So `Sys_CarType` alone is meaningless without `Sys_Vehicle_deries`; a RAV4 on this unit should read `Sys_Vehicle_deries=1, Sys_CarType=2`.

`Sys_CustomerType`: OEM/customer id, table `EC/customer/Customer.java:12-50` (settings copy `ST/szchoiceway/customer/Customer.java:5-42`), e.g. `CUSTOMER_NUM_CHWY = 13`, `_SIYI = 53`, `_KFT = 69`, `_KELAIDE = 52`. `Sys_UINumber` skin ids `EC/customer/Customer.java:78-118` (`UI_NUM_COMMON = 0`, `UI_NUM_CHWY_UI4 = 108`, `UI_NUM_H_CHEKU_BENTLEY = 10008`).

**RHD flag:** no SysVar. Left/right rudder is a CAN-box car setting the Hiworld Toyota box reports in its car-settings frame and the head unit only echoes:
`CB/model/vios/toyota/HiworldCanParseToyota.java:168-169` `case 98: OnHandleCanCarSetInfoCmd(bArr);` and `:672`
```java
addConsoleInfo(bArr[8], 2, 1, 1, R.string.tag_vehicle_left_and_right_rudder_settings);   // frame 0x62, data byte 6, bit 2
```
Written back with `CB/ui/carset/vios/toyota/HiworldToyotaSetConfig.java:54` (`addValueIdArray(..., 1, new int[]{0, 1}, {lbl_left_rudder, lbl_right_rudder}, 19)`) via `getSendToCanByteArray1` `:11-13` `{3, 106, 1, (byte) i, (byte) i2}` (frame `5A A5 03 6A 01 19 val CS`). Value lives only in canbus2's in-memory `mConsoleInfoMap` keyed by the string `tag_vehicle_left_and_right_rudder_settings`; no broadcast, no SysVar. Raise Toyota has no rudder item. Other vendors expose "left/right hand traffic" the same way (`CB/ui/carset/raise/gm/RaiseGmSettingsConfig.java:60`). `Set_RightSignDetect` (`EC/SysProviderOpt.java:159`) is the turn-signal side-camera option, not RHD. So `Reachability.KNOWN_RHD_CAR_TYPES` must stay empty; the only on-device evidence would be canbus2's console page.

---

### 5. Day/night and brightness

`Sys_Day_Night_Mode` values: `ST/szchoiceway/view/ItemTextRightCheckBoxView.java:503-525`
```java
case R.string.str_lbl_According_to_time: i = 3; break;
case R.string.str_lbl_auto:              i = 0; break;
case R.string.str_lbl_day:               i = 1; break;
case R.string.str_lbl_night:             i = 2; break;
...
SystemPropertiesHelps.updateRecord("Sys_Day_Night_Mode", String.valueOf(i));
```
Gateway semantics `EC/EventService.java:setDayNightMode` (`grep -n 'void setDayNightMode(int'`): 1 -> `sendSysUiModeNight(false)`, 2 -> `sendSysUiModeNight(true)`, 3 -> sunrise/sunset (GPS `getRcRl()`, else table) -> `UiModeManager.setNightMode(1|2)`, 0 -> follows headlamp (ILL) input `mLAMPConnected`. Default `"3"` (`:6621`); UI 128 forces `"2"` (`:6734`). Broadcasts: `"com.szchoiceway.uiModeNightChanged"` extra `"mode"` boolean (`sendSysUiModeNight`), and `com.szchoiceway.eventcenter.EventUtils.ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT` extra `Extra_Day_Night_UiMode` int (`EC/EventUtils.java:66,1235`; sender `sendDayNightUiModeToLauncher`, portrait only, `:4939-4941`).

Backlight is NOT `Sys_Light_Level_set`. The vendor slider writes `Set_Day_Light` / `Set_Night_Light` (0..20) and pushes them to the MCU:
`ST/zxw/lib/ui/util/SystemPropertiesHelps.java:316-319`
```java
public static int dayLightKey(boolean z, int i) {
    if (z) {
        updateRecord("Set_Day_Light", i + "");
        EventServiceHelps.getInstance().sendBacklight((byte) i, (byte) nightLightkey(false, 18));
```
Range: `ST/szchoiceway/data/DataManage.java:251-256` `settingsBean.setMaxProgress(20)`. Defaults: day 18 (`:319`) / gateway default "20" (`EC/EventService.java:6502`), night 8 (`:9640`).
MCU frame: `EC/EventService.java:9643-9648`
```java
public void sendBacklight(byte b, byte b2) {
    if (this.mIsSecondScreenDisplay && this.mNightMode) { b = b2; }
    byte[] bArr = {46, b, b2, (byte) ...getRecordInteger(SET_BACK_LIGHT_FINE_LOW_KEY, 80), (byte) ...getRecordInteger(SET_BACK_LIGHT_FINE_HI_KEY, 200), 0};
```
(`0x2E day night fineLow fineHi 00`; `Set_Back_Light_fine_Low_Key`/`_Hi_Key` `EC/SysProviderOpt.java:80-81`). AIDL `IEventService.sendBacklight(byte, byte)` txn 60 (`ST/szchoiceway/eventcenter/IEventService.java:864,1054`). A provider-only write of `Set_Day_Light` makes the gateway broadcast `com.szchoiceway.ACTION_DAY_BACKLIGHT_CHAGNED` (permission `com.szchoiceway.permission.broadcast`) but does not resend the frame (`:4847-4854`); call the AIDL or expect the DIM key to resync. The panel DIM key cycles Set_Day_Light through 3/12/20 (`ProccessDIMKey` `:7909-7940`).

`Sys_Light_Level_set` (`EC/SysProviderOpt.java:338`) is a 0..3 dim *level* remapped once at `:13882-13898` (`0->1, 1->2, 3->0`, default 3) and forwarded to SystemUI over the socket (`makeCarLightLevelSocketString`); no settings UI touches it (grep `Light_Level` in `$ST` non-constant: none). `BrightnessController.kt` writing the framework `screen_brightness` is orthogonal to all of this; the launcher's claim that `Sys_Light_Level_set` is "the backlight" (`SettingKeys.kt:23`, `CAR_API.md` 2.3) is wrong.

---

### 6. canbus2 parking radar

Two decoders matter. Selection: vendor 4 -> `CB/model/raise/CarPairingRaise.java:7-14` (header 0x2E); vendor 6 -> `CB/model/vios/CarPairingVios.java:169-179` (header `5A A5`, RAV4 year ids are not in the TP002/TP004 lists so -> `HiworldCanParseToyota`).

Framing: ingress broadcast `MCU_MSG_CAN_ALL_INFO` = `"com.choiceway.eventcenter.EventUtils.MCU_MSG_CAN_ALL_INFO"` extra `"EventUtils.CAR_AIR_DATA"` byte[] (`CB/services/CanbusDataService.java:237-238`, `CB/CanUtils.java:321`). After slicing the parser sees, for 5A A5: `b[0]=LEN, b[1]=CMD, b[2..]=DATA` (`CanbusDataService.java:450`); for 0x2E: `b[0]=0x2E, b[1]=CMD, b[2]=LEN, b[3..]=DATA` (`:497`).

Hiworld Toyota, CMD 0x41 (65): `CB/model/vios/toyota/HiworldCanParseToyota.java:162-163,903-921`
```java
for (int i = 0; i < 4; i++) {
    int i2 = bArr[i + 2] & 0xFF;                      // DATA[0..3] = REAR
    if (i2 >= 1 && i2 <= 5) { this.m_stBackRadarInfo[i] = (byte) (i2 * 30); }
    else { this.m_stBackRadarInfo[i] = EventUtils.CMD_CAN_AIR; }   // 0xA0
}
for (int i3 = 0; i3 < 4; i3++) {
    int i4 = bArr[i3 + 6] & 0xFF;                     // DATA[4..7] = FRONT
    if (i4 >= 1 && i4 <= 5) { this.m_stHeadRadarInfo[i3] = (byte) (i4 * 30); }
    else { this.m_stHeadRadarInfo[i3] = EventUtils.CMD_CAN_AIR; }
}
sendRadarInfo();
```
Raise Toyota, CMD 0x1E rear / 0x1D front (`CB/model/raise/toyota/RaiseCanParseToyota.java:8,13,119-124`), table `:96` `iRadarArray = {160, 30, 60, 90, 110, 150}`, 4 bytes each at `bArr[3..6]`, raw 0..5 (`:1134-1169`); `bArr[7]` of the rear frame carries settings bits (bit7 display, bit6 sensitivity, bits0-2 volume).

Delivery to other apps: `CB/model/CanDataParseBase.java:1221-1227`
```java
public void sendRadarInfo() {
    byte[] bArr = this.m_stHeadRadarInfo; byte[] bArr2 = this.m_stBackRadarInfo;
    byte[] bArr3 = {1, bArr[0], bArr[1], bArr[2], bArr[3], bArr2[0], bArr2[1], bArr2[2], bArr2[3]};
    int recordInteger = SysProviderOpt.getInstance().getRecordInteger(SysProviderOpt.Sys_Plugin_radar_Set, 0);
    ... if (recordInteger == 0) sendBroadcastCanDataExtra(CanUtils.MCU_CAR_CAN_RADAR_INFO, "EventUtils.CAR_CAN_DATA", bArr3);
```
Action `"com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO"` (`CB/CanUtils.java:195`), `UserHandle.ALL`. Payload is 9 bytes: `[0x01, F1..F4, R1..R4]`; each byte is a **distance code** 30/60/90/110/150 (Raise adds 160 = clear) or **0xA0 = no object / no data** (`EventUtils.CMD_CAN_AIR = -96`, `$D/com.szchoiceway.canbus2/sources/com/szchoiceway/zxwlib/utils/EventUtils.java:108`). Smaller = closer. The vendor overlay treats `160` as "empty" (`CB/ui/touchpad/RadarPopWindow.java:44-46`).

Compared with `L/carlib/.../RadarState.kt`: byte[1..4] front, byte[5..8] rear, header byte[0] -- layout guess is right. Wrong: polarity and scale. `level(i)` treats 0 as clear and `coerceIn(0, 8)` (`RadarState.kt:90`) turns every real value (30..160, 0xA0) into 8 = "closest". Correct decode: `0xA0`, `160` -> clear; `30/60/90/110/150` -> proximity `1 - (v-30)/120`; L->R order per index is UNRESOLVED (the RAV4 backcar UI is an empty stub `CB/ui/backcar/vios/toyota/HiworldRav4BackcarUI.java:10-11`).

---

### 7. canbus2 HVAC

Hiworld Toyota, CMD 0x31 (49) `OnHandleCanAirCmdVertical`, `CB/model/vios/toyota/HiworldCanParseToyota.java:213-300` (DATA = `bArr[2..]`):
```
b[2]: bit6 power, bit5 AC max, bit4 rear air, bit3 auto, bit2==0 dual, bit1 centralized, bit0 temp unit (1=F)
b[3]: bit7 rear lock, bit6 AC, bit5 air quality, bit4 outside circulation, bit3 AQS, bit2 dual, bit1 ECO, bit0 purifier
b[4]: bit7 rear auto, bit6 auto defog, bit5 rear defrost, bit4 front max defrost, (>>2)&3 right seat heat, &3 left seat heat
b[5]: (>>6)&3 right seat cool, (>>4)&3 left seat cool
b[6]: mode 1=auto 3=foot 5=level+foot 6=level 12=head+foot 13=head+level 14=all
b[7]&0x0F fan (max 7); b[8] left temp; b[9] right temp; b[10] rear mode; b[11]&0x0F rear fan; b[12] rear temp; b[13] outside temp
```
Temp code `:989-1000`: `254` -> LO, `255` -> HI, C = `code*0.5`, F = `code/2`. Outside temp `:314-327` `(b[13]&0xFF)*0.5-40` C, 255 -> "--". Legacy CMD 0x82 layout `:929-983` is ignored once a 0x31 frame has been seen (`:931-933`). Steering-wheel heat: UNRESOLVED for Hiworld (no `bWheelHeat` in that class).

Raise Toyota, CMD 0x28 `OnHandleCanAirCmd` `CB/model/raise/toyota/RaiseCanParseToyota.java:814-925`: `b[3]` bit7 power, bit6 AC, bit5 recirc, bit4 auto, bit2 dual, bit1 max front; `b[4]` bit7 head, bit6 level, bit5 foot, `&0x0F` fan; `b[5]/b[6]` L/R temp; `b[7]` bit0 unit, bit7 max front, bit6 rear, bit4 ECO, bit3 AC max; `b[8]` outside `x*0.5-40`; seat/wheel in CMD 0x58 (`:258-296`, wheel heat `"A"/"1"/"2"`). Temp code `:954-1012`: `0` LO, `31` HI, `32..35` -> `i*0.5`, `36..37` -> `i*0.5-3`, else `(i-1)*0.5+18` C; F verbatim, 255 HI.

Write path: yes, canbus2 sends control frames. Frames are wrapped by `CB/utils/SendUtil.java` and broadcast to the MCU bridge as `com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT` extra `EventUtils.MCU_CMD_DATA` byte[] (`$D/.../zxwlib/utils/EventUtils.java:25,267`). Hiworld key frame `{0x02, 0x3D, code, 0x01}` -> `5A A5 02 3D code 01 CS` with CS = `(sum-1)&0xFF`, then a release with byte3 = 0 (`HiworldCanParseToyota.java:1784-1938`). Codes: POWER 1, AC 2, SYNC 3, AUTO 4, front defrost 5, rear defrost 6, recirc 7, FAN+ 11, FAN- 12, LTEMP+ 13, LTEMP- 14, RTEMP+ 15, RTEMP- 16, L seat heat 17, R seat heat 18, MODE 21, DUAL 41. Raise key frame `2E C7 07 d0..d6 CS` bitmap at `RaiseCanParseToyota.java:2011-2235` (AC = d0.1, FAN+ = d1.1, LTEMP+ = d3.1, RTEMP+ = d4.1).

Third-party entry point, no binder needed: `CB/CarAirClickWithVoice.java:225,253-257`
```java
intentFilter.addAction("CAR_AIR_KEY_KEY");
...
if (action.startsWith("CAR_AIR_KEY_KEY")) {
    int intExtra = intent.getIntExtra("car_key_value", -1);
```
`car_key_value` in `CanUtils.CAR_AIR_KEY_*` (`CB/CanUtils.java:52-180`: POWER 0, FAN_ADD 1, FAN_SUB 2, LEFT_TEMP_ADD 3, LEFT_TEMP_SUB 4, RIGHT_TEMP_ADD 5, RIGHT_TEMP_SUB 6, AUTO 7, AC 8, DUAL 10, INNER_LOOP 12, FWndMaxDefrost 15, RWndMaxDefrost 16, LEFT_SEAT_HOT 18, RIGHT_SEAT_HOT 20, MODE 21, WHEET_HOT 37). `getAirData`/`sendCanbusData` on `IEventService` are not the HVAC path.

State delivery: `CB/model/CanDataParseBase.java:472-486` broadcasts `"com.choiceway.canbus.carairstruct"` extra `"com.choiceway.canbus.carairstruct.airstate"` = Parcelable `com.szchoiceway.canbus.CarAirState` (`CB/CanUtils.java:47-48`). A receiver must carry that class (or unparcel by hand). Outside temp additionally as String on `"com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT"` extra `..._EXTRA_STR` (`CB/CanUtils.java:14-15`).

---

### 8. canbus2 vehicle signals

Common: everything below is broadcast with `sendBroadcastAsUser(intent, UserHandle.ALL)` from `CB/model/CanDataParseBase.java:1660-1665`; nothing is written to SysVar (grep `updateRecord(` in `CB/model`: only reverse-camera flags). Trip/TPMS detail only reach the in-process `mConsoleInfoMap` (`:1131-1133`) for the console UI.

| Signal | Hiworld Toyota decode (DATA = b[2..]) | Raise Toyota decode | Broadcast |
|---|---|---|---|
| speed + RPM | CMD 0x32 `:413-415` `rpmH=b[4], rpmL=b[5], speed=b[7]<<8|b[6]` | CMD 0x7D sub 3 `CB/model/raise/RaiseCarInfoUtil.java:148-149` `speed=(b[5]*256+b[4])/100`; sub 10 `:153-154` RPM bytes `/4` | `com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_INFO` extra `EventUtils.CAR_CAN_DATA` byte[3] `{speed, rpmH, rpmL}` (`CanDataParseBase.java:1205-1207`); speed clamped 0..240 by consumers |
| steering | only wheel-track: CMD 0x11 `:818-828` `i=b[9]|b[8]<<8`; bit15 -> `BIT_ON((65535-i)/14, 7)` else `i/14` | CMD 0x29 `RaiseCanParseToyota.java:1068-1079` 12-bit, `/10`, bit7 = side | `com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT` extra `..._EXTRA` int `&0xFF` (`CanDataParseBase.java:1316-1318`, `CB/CanUtils.java:368-369`). Magnitude/14 with bit7 = direction; no degrees. |
| outside temp | CMD 0x31 `:314-327` `b[13]*0.5-40` | CMD 0x28 `:845-855` `b[8]*0.5-40`, sent once (guard bug `:88`) | `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT` extra `..._EXTRA_STR` String e.g. `"23.5C"` (`:1552-1555`) |
| doors | CMD 0x11 -> `OnHandleCanDoorInfoCmd(b[6])` `:923-927`: bit7 FL, bit6 FR, bit5 RL, bit4 RR, bit3 tailgate, bit2 bonnet | CMD 0x24 `:720-725` bit7 FR, bit6 FL, bit5 RL, bit4 RR, bit3 tailgate; `b[4]&1` handbrake | `com.szchoiceway.eventcenter.EventUtils.ACCORD_DOOR_INFO` extra `EventUtils.CAR_DOOR_DATA` byte: bit7 FL, bit6 FR, bit5 RR, bit4 RL, bit3 tailgate, bit2 bonnet (`:1260-1296`, `CB/ui/door/DoorInfoWindow.java:211-291`) |
| seatbelt | UNRESOLVED (none in Hiworld class) | CMD 0x7D sub 6 `RaiseCarInfoUtil.java:35-38` `b[4]` bit5 driver, bit4 passenger, 0 = fastened | `Action_DashboardInfo` extra `Extra_DashboardInfo` Parcelable `com.szchoiceway.canbus2.bean.DashboardInfo` (`CanDataParseBase.java:1607-1616`; parcel order `CB/bean/DashboardInfo.java:451-487`) |
| lights | CMD 0x18 `:340-351` `b[3]` bit7 right, bit6 left, bit3 hazard (camera trigger only) | CMD 0x7D sub 1 `RaiseCarInfoUtil.java:51-66` `b[4]` bit7 parking, bit6 low, bit5 high, bit4 left, bit3 right, bit2 fog | turn signals: `com.szchoiceway.eventcenter.EventUtils.CAR_RIGHT_SIGH_EVT` extra `..._TRA` int (`:392`, `CanUtils.java:187-188`); rest DashboardInfo (Raise only) |
| fuel | no level; consumption CMD 0x13 `:504-556` (unit `b[12]`: 1 km/L, 2 L/100km, 3 MPG-UK, else MPG-US; `b[2..3]` BE16 x0.1), CMD 0x16/0x17 history | CMD 0x22/0x23/0x27 BE16 x0.1 (`:703-718,794-812`) | console only |
| TPMS | CMD 0x48 `:353-374` `b[2]` bit7 warn; wheel n: `(b[4+n]&0xFF)+(b[9+n]&0xFF)` kPa, 0xFE = "--" | CMD 0x25 `:727-767` unit `b[3]&3` (1 PSI, 2 kPa x2.5, else bar x0.1), FL..spare `b[4..8]` | Raise -> DashboardInfo strings; Hiworld console only |
| trip | CMD 0x13: range `b[4..5]`, avg speed `b[10..11]` km/h, elapsed `b[8..9]` min; odometer/trip A/B UNRESOLVED | CMD 0x21 `:667-701`; odo/trips CMD 0x7D sub 4 `RaiseCarInfoUtil.java:69-108` (total 24-bit km, range x0.1, tripA/B LE24 x0.1) | Raise -> DashboardInfo; Hiworld console only |

`SteeringReading.kt`'s move to the 0x11 frame is consistent with the decompile: Hiworld puts the wheel-track word in the basic-status frame (`:818`) and the broadcast extra is a 7-bit magnitude/14 with a direction flag, so `ZXW_CAN_WHEEL_TRACK_EVT` never carried degrees.

---

### 9. btsuite status exposure

App runs as `android.uid.system`, all broadcasts unpermissioned, one helper: `BT/btsuite/BTUtils.java:374-383`
```java
public static void sendMessage(Context context, String str, String str2, int i) {
    Intent intent = new Intent(str);
    if (str2 != null) { intent.putExtra("com.szchoiceway.btsuite.DATA_STR", str2); }
    intent.putExtra("com.szchoiceway.btsuite.DATA_INT", i);
    context.sendBroadcastAsUser(intent, UserHandle.ALL);
```
Actions `BT/btsuite/BTUtils.java:88-105` (prefix `com.szchoiceway.btsuite.`):

| Action | DATA_INT | DATA_STR | sender |
|---|---|---|---|
| `HBCP_EVT_BT_POWER_STATUS` | 1 on / 0 off | "" | `BT/parse/ParseFEasycom.java:175-179` |
| `HBCP_EVT_HSHF_STATUS` | HFP state 0..6 | none | `ParseFEasycom.java:412` (only when audio not in car), `:238`, `BTService.java:617` |
| `HBCP_EVT_HSHF_GET_STATUS` | HFP state 0..6, on change | none | `ParseFEasycom.java:423` |
| `HBCP_EVT_CONTACT_NUM` | 0 | number | `ParseFEasycom.java:498` (states 4/5) |
| `HBCP_EVT_CONTACT_NAME` | 0 | name or geo string | `ParseFEasycom.java:499` |
| `HBCP_EVT_CUR_CONNECTED_DEVICE_NAME` | 0 | phone name | `BTService.java:1536-1538` (on request key 8) |
| `HBCP_EVT_DEVICE_NAME` | 0 | head-unit BT name | `BTService.java:1563-1565` |
| `HBCP_EVT_AV_STATUS` | 4 playing / 3 paused | track title | `BTService.java:262-265` |
| `HBCP_EVT_SPEAKING_TIME` | int[] {min, sec} | none | `BTService.java:746-749` |

HFP state enum `BT/btsuite/BTUtils.java:115-121`: 0 initialising, 1 ready (no phone), 2 connecting, 3 connected idle, 4 outgoing, 5 incoming, 6 active call; `>3` = call in progress (`BTService.java:735`). A2DP: `HBCP_STATUS_AV_PLAYING = 4`, `AV_PAUSE = 3` (`BTUtils.java:110,112`).
Never sent: `HBCP_EVT_PAIR_STATUS`, `HBCP_SEARCH_BT_EVT_STATUS`, `HBCP_EVT_DEVICE_PINCODE`, `HBCP_EVT_CONTACT_ADDRESS`, `HBCP_EVT_OBD_DEV_STATUS`. Phone battery, signal, artist/album, pairing state are not on the broadcast surface; artist/album/position go only to the gateway via `IEventService.setValidModeAllInfor` (`BTService.java:2058-2068`) and can be read back with `IEventService.GetBTStatus()` / `getBTMusicStatus()` (`BT/eventcenter/IEventService.java:633,683`).

SysVar: writes `SYS_BT_NAME_KEY` (`BT/fragments/SetBTNameFragment.java:56`), `Sys_BT_Moudle_Software_Version` (`ParseFEasycom.java:133`), `Sys_Has_set_volume_by_call`, `Sys_volume_before_bluetooth_set` (`:434-435,454`), `SYS_MUTE_MIC` (`BTService.java:2178`); reads `Sys_bt_power_is_on_key`, `Sys_BTDeviceType`, `Sys_BT_Launch_Sound_Key`. No SysVar for connection/call state.

Binder: `<service android:name="com.szchoiceway.btsuite.BTService" android:exported="true">` action `com.szchoiceway.btsuite.BTService` (`$D/com.szchoiceway.btsuite/resources/AndroidManifest.xml:77-82`); descriptor `com.szchoiceway.btsuite.IBTService` (`BT/btsuite/IBTService.java:5,14-23`): `String getContractAddress()`, `void hideBTFloatWnd()`, `void sendData(String)`. `sendData` passes raw module commands (`BTUtils.java:10-87`): `DW<num>` dial, `DE` answer, `DG` hang up, `DF` reject, `MA` play/pause, `MD` next, `ME` prev, `DC`/`DD` HFP connect/disconnect.

Inbound broadcasts (`BTService.java:1219-1254`): `zxw_bluetooth_contral_action` int `zxw_bluetooth_contral_key` (3 audio->phone, 4 audio->car, 5 dial `zxw_bluetooth_contral_key_value_str`, 7 query power, 8 re-broadcast device name, 9 query name, 10 connect MAC, 11 disconnect) `:1496-1530`; `com.szchoiceway.btsuite.HBCP_PHONE_NUM_EVENT` extra `..HBCP_EXTRA_PHONE_NUM_EVENT` dial `:1331-1336`; `..HBCP_HANGUP_EVENT` `:1338-1341`; `com.szchoiceway.action.BT_CONNECT` (`bt_name`) / `BT_DISCONNECT` / `BT_SCAN` / `BT_GET` `:1446-1486`; `com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR` int `EventUtils.MCU_KEY_VALUE` (2 next, 3 prev, 4 play, 5 stop, 6 play/pause, 22 hang up, 23 answer) `:1801-1836`.

Mapping for `VendorBtStatus.kt`: the extras are always `com.szchoiceway.btsuite.DATA_INT` / `DATA_STR`; "POWER" -> `DATA_INT` 1/0; "HSHF" -> `DATA_INT` 0..6 with connected = `>= 3`, in-call = `> 3`; "CONNECTED_DEVICE_NAME" -> `DATA_STR` (non-empty = connected). The "single numeric extra" heuristic will misread `HBCP_EVT_SPEAKING_TIME` (int[]) and `HBCP_EVT_AV_STATUS` (3/4).

---

### 10. Exported components usable by a launcher

settings (`$D/com.szchoiceway.settings/resources/AndroidManifest.xml:50-107`):
- `com.szchoiceway.settings/.settings.MainActivity` exported, MAIN/LAUNCHER; dispatches by `Sys_UINumber` / customer to `LandMainActivity` etc. (`ST/szchoiceway/settings/MainActivity.java:12-28`), no documented extras.
- `com.szchoiceway.settings/.settings.FactoryActivity` **not exported** (`:62-68`, no `android:exported`, no filter). Extras `SHOW_POSITION` int, `IS_HIDE_OTHER_POSITION` boolean (`ST/szchoiceway/settings/FactoryActivity.java:17-33`). Reachable as root via `am start -n`. Password gate: `ST/zxw/lib/ui/util/PasswordValidHelps.java:7,15,28-34,44-50` (`"1688"`, `"111000"`, default `"7890"`/`"8861"`, override SysVar `Sys_custom_factory_password`).
- `com.zxw.lib.ui.service.MediaNotificationService` exported (`:100-103`); `content://com.szchoiceway.settings.CoreContentProvider` exported (`:104-107`).

canbus2 (`$D/com.szchoiceway.canbus2/resources/AndroidManifest.xml:35-113`):
- `com.szchoiceway.canbus2/.activity.AirUIActivity` exported, singleInstance (HVAC panel) `:35-41`.
- `.activity.ClimateActivity` exported but `android:enabled="false"` `:42-56`; `.activity.ConsoleActivity` exported, `enabled="false"` `:57-71`; `.activity.ConsoleNewActivity` exported, enabled, MAIN/LAUNCHER `:72-85`. Console page extra: `ConsoleActivity.UI_PAGE = "GotoPageTag"` (`CB/activity/ConsoleActivity.java:6,51`), value = fully-qualified fragment class name, resolved with `Class.forName` and only if it is in the current car's page list (`CB/ui/console/ConsoleUILandscapeDefault.java:222-233`).
- `.activity.SelectCarActivity` exported (car/vendor selector) `:94-102`.
- `.services.CanbusDataService` exported, action `com.szchoiceway.canbus2.services.CanbusDataService` `:86-93`; `onBind` returns a bare `android.os.Binder` (`CB/services/CanbusDataService.java:118-120`), so nothing to call. Control is by broadcast (`CAR_AIR_KEY_KEY`, section 7).
- Receiver `.CanbusDataServiceAutoStart` exported: `BOOT_COMPLETED`, `com.szchoiceway.eventcenter.EventUtils.ACTION_ACC_SLEEP_STATUS_EVT` `:103-110`.

btsuite (`$D/com.szchoiceway.btsuite/resources/AndroidManifest.xml`):
- `com.szchoiceway.btsuite/.BTMainActivity` exported, MAIN/LAUNCHER (`:49-60`); String extra `"GotoPageNum"` in `DialPage`, `CallRecordPage`, `PhoneBookPage`, `SetPage`, `BTMusic` (`BT/btsuite/BTMainActivity.java:92`, `BT/bean/DisplayPageId.java:6-21`). No pairing-page id.
- `.BTMusicActivity` exported, MAIN/LAUNCHER (`:37-48`), no extras.
- `.BTService` exported (binder above). Provider `content://com.szchoiceway.btsuite.CallListProvider/query` read-only, projection[0] = calltype 2 received / 3 dialed / 4 missed / 5 all (`BT/btsuite/CallRecManager.java:150-153,261`).

No EQ activity exists in these three apps (EQ lives in the gateway/SystemUI socket, out of scope). No CAN debug activity in canbus2 (the separate `com.szchoiceway.canbusdebug` package exists in `$D`, not analysed).

---

### Corrections to the launcher

1. `VendorChrome.KEY_SHOW_NAVI_BAR = "SYS_SHOW_TOOL_NAVI_BAR_WND"`: right string, wrong store. It is `SystemProperties`, written by the gateway as a mirror, read by nobody, and only for UI 108/126/127. Writing it to SysVar does nothing. (`EC/EventService.java:14428`)
2. `VendorChrome.KEY_STATUSBAR_ICON_CONFIG = "Sys_Statusbar_Icon_Config_Key"`: no reader in any package. Dead key. (`EC/SysProviderOpt.java:430` only)
3. Persistent bottom-bar switch is `Sys_Customer_NaviBar_Height_Key` (0 = no bar, else px) gated by `Sys_Landscape = 1`; the gateway applies it live via `persist.sys.show_navigationbar`. (`EC/utils/SystemUtils.java:90-132`) No key hides the status bar.
4. `SettingKeys.UI_NUMBER_KEY = "uiNumberKey"` -> `"Sys_UINumber"`. (`ST/.../SystemPropertiesHelps.java:379`, `EC/SysProviderOpt.java:458`)
5. `PowerSettingsScreen`: `SET_ACC_ON_DELAY` 0..7 s (not 0..30); `ACC_OFF_DELAY` has no value (never read); `Sys_Power_Off_Delay` is 0/1 (not 0..60); `SYS_SLEEP_TIME` is enum 1/2/3 -> 960/1440/2880 (default 2), not minutes 1..60. (`ItemTextRightCheckBoxView.java:450-486`, `EC/EventService.java:9361-9371,10186,6538-6541`)
6. Screensaver/close-screen keys missing from `SettingKeys`: `SYS_AUTO_START_SCREENSAVER_TIME`, `SYS_AUTO_START_CLOSE_SCREEN_TIME` (seconds in {0,60,300,600,1800}), `Sys_Screen_Off_When_Acc_Change` (0/1). (`ItemTextRightCheckBoxView.java:644-692`)
7. `CarSettingsController` writes the provider directly; the vendor path is `IEventService.changeSetup` (txn 78) on `com.szchoiceway.eventcenter/.EventService`. Provider-only writes of `Set_Day_Light`/`Set_Night_Light` do not reach the MCU; use `IEventService.sendBacklight(day, night)` (txn 60) or `changeSetup`. (`SystemPropertiesHelps.java:54-62,316-328`, `EC/EventService.java:4847-4854,9643`)
8. `Sys_Light_Level_set` is a 0..3 dim level for SystemUI, not the backlight; backlight is `Set_Day_Light`/`Set_Night_Light` 0..20 (frame `2E day night 80 200 00`). (`EC/EventService.java:13882-13898,9643-9648`, `DataManage.java:256`)
9. `Sys_Day_Night_Mode`: 0 auto (headlamp), 1 day, 2 night, 3 by time (default 3). Companion broadcasts `com.szchoiceway.uiModeNightChanged` extra `mode` boolean, and `...ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT` extra `Extra_Day_Night_UiMode`. (`ItemTextRightCheckBoxView.java:503-525`, `EC/EventService.java:6621`)
10. `Reachability`: `Sys_CarType` is a model index inside `Sys_Vehicle_deries` (Toyota = 1, RAV4 = 2); year is `Sys_CarInfor_ID`; CAN vendor is `Sys_camry_air_Supplier_id` (4 Raise, 6 Hiworld). No RHD SysVar; rudder is a CAN-box setting (Hiworld frame 0x62 byte 6 bit 2). Keep `KNOWN_RHD_CAR_TYPES` empty. (`CB/services/CanbusDataService.java:1234-1239`, `CanConstantInfo.java:543,645`, `HiworldCanParseToyota.java:672`)
11. `RadarState`: layout `[hdr, F1..F4, R1..R4]` is right; values are distance codes 30/60/90/110/150 (small = close), 160 and 0xA0 = clear. `LEVEL_MAX = 8` with `coerceIn` makes every real reading "closest". (`CanDataParseBase.java:1221-1227`, `HiworldCanParseToyota.java:903-921`)
12. `VendorBtStatus`: extras are fixed `com.szchoiceway.btsuite.DATA_INT` / `DATA_STR`; HSHF int 0..6 (connected >= 3, call > 3); POWER 1/0; AV_STATUS 4 playing / 3 paused; device name in `DATA_STR`. Drop the key-hint heuristics. (`BTUtils.java:88-121,374-383`)
13. `SteeringReading`: the vendor's `ZXW_CAN_WHEEL_TRACK_EVT` extra is `|track|/14` with bit7 = side, never degrees; deriving the angle from the 0x11 frame is the only source. (`HiworldCanParseToyota.java:818-828`, `CanDataParseBase.java:1316-1318`)
14. `SysVar.kt` docs / `CAR_API.md` 2: the `SysVarProvider` `<provider>` is not declared in the settings, canbus2 or btsuite manifests either (grep `SysVarProvider` in all `AndroidManifest.xml`: none); its host is still UNRESOLVED.

---

## 5. The remaining OEM apps

Paths: `D=decompiled`, `<pkg>/…` = `D/<pkg>/sources/…`,
`EC=D/com.szchoiceway.eventcenter/sources/com/szchoiceway/eventcenter`.
All twelve apps are `sharedUserId=android.uid.system` (manifest root element of each).
None declares `android:permission` on its exported components, so anything below marked
*exported* is reachable from the launcher by explicit intent.

Shared facts (used by several apps, stated once):

- **eSrcMode table** (`EC/EventUtils.java:2006-2065`) — the `sendMode()` value table the launcher
  README says is missing: `RADIO=1 DVD=2 USB=3 CARD=4 IPOD=5 BT=6 BTMUSIC=7 TV=9 MOVIE=10 MUSIC=11
  IMAGE=13 ANDROID=14 HDMI=19 CARCONSOLE=30 PHONELINK=31 CARPLAY=32 DAB=34 AUX=40 BACKCAR=41 GPS=42
  HOME=43 SETUP=49 FCAM=50 RCAM=51 BCAM=52 DVR=53 CUSTOMIZE=60..66 NULL=99 CARAIR=150 APPLIST=154`.
- **ICallbackfn** = `notifyEvt(int iEvtMsgid, int wParam, int lParam, byte[] byData, String strData)`
  + `checkIsActive()` (`com.szchoiceway.musicplayer/…/eventcenter/ICallbackfn.java:18-24`, ordinals
  notifyEvt=1, checkIsActive=2 at `:28-29`). Matches the launcher's AIDL. Message ids
  (`musicplayer/…/zxwlib/utils/EventUtils.java:153-163,240`): `EVENT_MODE_CHANGE=4097` (you lost the
  source, stop), `EVENT_KEY_EVENT=4098` (lParam = MCU key, byData[0] = focus state),
  `EVENT_BACKCAR_START=4099/END=4100`, `EVENT_BT_EVENT=4101` (wParam 1 = call active),
  `EVENT_BREAK_STATE=4104`, `LEAVE_STANDBY=4105`, `ACC_CHANGE=4106`, `EVENT_CAMERA_STATUS=4107`,
  `EVENT_NAV_SOUND_EVENT=4108` (lParam 1 = nav prompt playing → duck).
- **MCU media keys delivered via 4098** (`…/zxwlib/utils/EventUtils.java:268-324`): `POWER=1 NEXT=2
  PREV=3 PLAY=4 STOP=5 PLAYPAUSE=6 REPEAT=29 RANDOM=30 181=pause 199=resume 247=pause 511=EXIT`.
- **Gateway → everyone now-playing broadcast** (not in CAR_API): `sendValidModeAllInfor()`
  `EC/EventService.java:9180-9199` sends `com.szchoiceway.eventcenter.EventUtils.VALID_MODE_INFOR_CHANGE`
  (`EC/EventUtils.java:1849`) with extras `Title, Ablum(sic), Artist, CurTrack, TotTrack, CurFolder,
  TotFolder, CurTime, TotTime, LoopMode, RepeatMode, PlayStatus`, fired from every
  `setValidModeAllInfor()` (`:9186`). Unprotected, `sendBroadcastAsUser(ALL)`.
- **Shared vendor lib `com.szchoiceway.zxwlib`** (inside musicplayer/videoplayer):
  `EventServiceProxy.java` wraps the whole AIDL; `NewPackageConstant.java:5-42` is the vendor's
  component table (radio, BT, canbus2 climate/console, 360, zlink CarPlay/AA/HiCar, weather, apk
  installer, dashboard, DSP…).
- **Privileged install channel** (any `android.uid.system` app): set prop `sys.apk_path=<shell cmd>`
  then `ctl.start=install_apk`; the init service runs the string and writes `true` back
  (`com.szchoiceway.apkinstall/…/InstallAllActivity.java:457-465`, also
  `com.szchoiceway.gps/…/zxw/lib/ui/util/BootLogoUtils.java:13-18`). Root is not needed for it, system
  uid is. The launcher does not have that uid, so root remains its route.

---

### com.szchoiceway.musicplayer

1. Vendor local/USB music player. Label `@string/app_name`, icon `@drawable/yinyue_n`. Owns source
   `SRC_MUSIC (11)`.
2. Manifest: `MainActivity` exported, `singleInstance`, filters MAIN/LAUNCHER + VIEW + GET_CONTENT
   (opens audio by URI). `MusicPlayerService` exported, action
   `com.szchoiceway.musicplayer.MusicPlayerService`. Perms: storage, FOREGROUND_SERVICE,
   INTERACT_ACROSS_USERS, BLUETOOTH_CONNECT, COARSE_LOCATION. targetSdk 31.
3. Gateway: binds by action `com.szchoiceway.eventcenter.EventService`
   (`utils/EventcenterUtil.java:120-126`). `sendModeToEventcenter(mode)` = `sendMode(mode,false)` then
   `setCurModeCallback(mode, mModeCallback)` (`:147-148`); `exitCurMode(mode)` on stop (`:159`).
   Mode int is `SRC_MUSIC` (`MusicPlayerService.java:1102`). Callback → `notifyEvt`
   (`MusicPlayerService.java:682-719`): 4097 stop, 4098 → `OnKeyEvent(lParam)` (`:542-590`, key table
   above; `511` finishes the activity), 4101 pauses on BT call, 4108 ducks for nav prompts.
   Broadcasts consumed (`MusicPlayerService.java:860-950`):
   `com.szchoiceway.ACTION_VOICE_CTRL` (extra `VoiceKeyWord`, Chinese phrases only, e.g. `随机播放`),
   `com.szchoiceway.ACTION_GET_CUR_PLAY_LIST` (extra `com.szchoiceway.EXTRA_CUR_PLAY_LIST_TYPE`=11),
   `com.szchoiceway.ACTION_PLAY_BY_INDEX` (`…EXTRA_CUR_PLAY_LIST_TYPE`=11 + `EXTRA_INDEX`),
   `com.szchoiceway.ACTION_NOTIFY_ADD_FLASH_MAP`, `ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_EVT`.
   Broadcasts sent (all `sendBroadcastAsUser(ALL)`): `MUSIC_PLAY_LIST_ACTION`
   (extra `MUSIC_PLAY_LIST_EXTRA` String[]) `:258-260`; `…ZXW_ACTION_NOTIIFY_MEDIA_PLAY_PATH`
   (`…_EXTRA` = path) `:512-514`; `com.szchoiceway.ACTION_NOTIFY_CUR_PLAY_LIST` (`EXTRA_CUR_PLAY_LIST`
   String[], `EXTRA_CUR_PLAY_INDEX`) `:1012-1016`; raw MCU mute `ACTION_MCU_CMD_EVENT` with
   `EventUtils.MCU_CMD_DATA={76, t}` `:1118-1123`; and on activity destroy
   `com.szchoiceway.ACTION_START_SERVICE` (`servicePackageName`,`serviceName`) to the gateway so the
   player service survives (`MainActivity.java:228-235`; handled `EC/EvtModel.java:1045`).
   SysVar read: `SYS_UI_NUMBER_KEY`, `SYS_CUSTOMER_TYPE_KEY`, `SYS_SCREEN_CHIP_KEY`,
   `SYS_SECOND_SCREEN_FLAG_KEY`, `SYS_NEED_GET_CUR_PLAY_LIST`.
4. **No MediaSession** (only androidx ContextCompat references). Now-playing reaches the gateway solely
   through `setValidModeAllInfor(title, album, artist, curTrack, totTrack, curFolder, totFolder,
   curTime, totTime, loopMode, repeatMode, playStatus)` gated on `getValidMode()==11`
   (`MusicPlayerService.java:623-630`; `iPlayStatus` 3 = playing, 4 = paused per `:718,557`;
   loop modes `ALL=0 ONE=1 RANDOM=2 FOLDER=3 ORDER=4` `zxwmediaplaylib/bean/StaticData.java:67-71`).
   Steering keys arrive only via the 4098 callback, never as KeyEvents. No floating window (the
   `hjq/EasyWindow` in the tree is only used by `ToastUtils`).
5-9. n/a.

### com.szchoiceway.videoplayer

1. Vendor video player, `SRC_MOVIE (10)`. Icon `@drawable/shipin_n`.
2. Manifest: `MainActivity` exported singleInstance (MAIN/LAUNCHER + VIEW). Services exported:
   `VideoPlayerService` (action = its class name) and `PIPService` (action
   `com.szchoiceway.videoplayer.PIPService`). Same perms as musicplayer.
3. Same `EventcenterUtil` pattern (`utils/EventcenterUtil.java:147-159`), mode `SRC_MOVIE`
   (`VideoPlayerService.java:1010,1020`). `notifyEvt` `:567-600` identical to music. Extra receivers
   (`:725-729`): `…MCU_MSG_BRAKE_EVT` and a ContentObserver on SysVar `Sys_CurBreakSate`
   (`SYS_CUR_BREAK_STATE_KEY`, `:1062-1070`) → `ActivityEvent(2)` → `setBreakState()`
   (`MainActivity.java:233`) blanks the picture while the handbrake is off. Injects Android key 3 (HOME)
   through `Instrumentation.sendKeyDownUpSync` when PIP closes (`MainActivity.java:301`,
   `zxwlib/utils/MultipleUtils.java:114-125`). On start it force-starts the scanner:
   component `com.szchoiceway.zxwmedia/.ScanFileService` (`base/VideoApp.java:43-45`). Sets props
   `Sys.Zxw.SecondScreen`, `StartFromPIP`.
4. No MediaSession. Metadata via `setValidModeAllInfor` (`VideoPlayerService.java` same signature).
   **Mini screen exists**: `PIPService` builds a `PIPControllerLandscape2` floating view with
   `WindowManager.LayoutParams.type=2003` (TYPE_SYSTEM_ALERT), `flags=40`, gravity top-left, parked
   bottom-right (`ui/PIPControllerLandscape2.java:274-290`); tapping it sets `StartFromPIP=1` and
   `startActivityIfNotRuning(videoplayer MainActivity)` (`:266-267`). `MainActivity` binds the PIP
   service (`MainActivity.java:255`) and reads `StartFromPIP` on focus (`:61-65`).
5-9. n/a.

### com.szchoiceway.zxwmedia

1. Background media *scanner* (not a player): walks internal + USB, keeps a SQLite index with ID3
   parsing, serves music/video players. Icon `@mipmap/ic_launcher`.
2. Manifest: `ScanFileService` exported (no filter, start by component); `MainActivity` exported,
   `excludeFromRecents`, no filter; `AutoStart` receiver on BOOT_COMPLETED; provider
   `com.szchoiceway.zxwmedia.provider.FileProvider` **exported** (authority
   `com.szchoiceway.zxwmedia.provider.FileProvider`, no permission).
3. No gateway use. Listens MEDIA_MOUNTED/EJECT (`ScanFileService.java:96-97`) and its own actions
   `ACTION_RESCEN_BY_FLASH_TYPE`/`_PATH` (extras `RESCEN_BY_FLASH_TYPE`/`_PATH`), `delete`,
   `ACTION_GET_CUR_CACHED_FLASH_MAP` (`:5-10,169-172`).
4. Provider schema: URIs `/query/#`, `/update/#`, `/delete/#` (`provider/FileProvider.java:36-38`);
   table `MusicFileList(_id, flashTag, folderName, folderPath, fileName, filePath, fileType
   [Music|Video|All], fileLength, title, artist, album)` (`db/FileDao.java:5-19`) plus `MusicID3List`.
   Any app can query it: a ready-made library index for a launcher media browser.
5-9. n/a.

### com.szchoiceway.gps

1. GNSS status page (satellite count/SNR, lat/lon/alt/speed). Label `str_app_name_gps`. Built on the
   vendor settings lib (`com.zxw.lib.ui`), hence the huge permission list.
2. Manifest: `activity/GPSActivity` exported singleInstance MAIN/LAUNCHER; `MediaNotificationService`
   exported (lib boilerplate); provider `com.szchoiceway.gps.CoreContentProvider` exported (lib
   boilerplate). Perms include INSTALL/DELETE_PACKAGES, INJECT_EVENTS, REBOOT, RECOVERY,
   MEDIA_CONTENT_CONTROL — inherited from the settings lib, not used by the GPS page.
3. Gateway only through the lib (`zxw/lib/ui/service/EventServiceHelps.java`, full AIDL wrapper).
   `SettingApp`-style apps send `sendMode(SRC_SETUP=49)` on start (seen in learn.key
   `SettingApp.java:38`); gps activity itself makes no mode/metadata calls.
5. **Publishes nothing.** `GpsView.java:160,172` registers `GnssStatus.Callback` + `requestLocationUpdates("gps",1000,0)`
   and only updates its own views; speed = `getSpeed()*3.7f` (`:202`, wrong factor, should be 3.6).
   No broadcast, no provider row. Nav app config is read/written through the lib helper:
   `SystemPropertiesHelps.java:694,746` → SysVar `Set_NavPackageName`, `Set_NavClassName`,
   `Set_NavLableName`. **Gateway launch path**: `EC/EventService.java:7707 runGPSMode()` reads those two
   keys, tries `HDMIManage.startTaskToMainDisplay(pkg,cls)`, else `Intent(MAIN)` +
   `setComponent(pkg,cls)` + `startActivityAsUser(CURRENT_OR_SELF)` (`:7710-7725`). It is reached by
   `postRunModeActivity(SRC_GPS=42)` (`:8108`), which the wheel/panel key `55` triggers (`:2426-2427`).
   The gateway also writes the nav package to `/data/local/maps.txt` (`:150,7202`).
6-9. n/a.

### com.szchoiceway.navigation

1. **Misnamed**: it is the camera/HDMI input viewer, not a navigator. Activities `CAMActivity`
   (front/rear/blind cams) and `HDMIActivity` (HDMI-in). Icon `@mipmap/ic_launcher`.
2. Manifest: both activities exported singleInstance MAIN/LAUNCHER. Perms: CAMERA,
   SYSTEM_ALERT_WINDOW, INTERACT_ACROSS_USERS, COARSE_LOCATION. `NewPackageConstant.KEY_AUX`
   references `…navigation.AUXActivity` but it is not in this build's manifest.
3. Binds by component `com.szchoiceway.eventcenter/.EventService` (`NaviApp.java:137`,
   `BaseActivity.java:95`). `CAMActivity.java:70-89`: `mCameraType` 0 → `sendMode(SRC_RCAM=51,true)` +
   `setCameraChannel("10")`, 1 → `SRC_FCAM=50` + `"9"`, 2 → `SRC_BCAM=52` + `"2"`; picks
   `Camera.open(0|1)` surface index (`camera/CameraManager.java:119-126`). `HDMIActivity.java:115`
   `sendMode(SRC_HDMI=19,false)`, `setCameraChannel("8")` (`:43`), `setCurModeCallback(19, cb)`
   (`:544`), `exitCurMode(19)` (`:497`), `setValidModeInfor(...)` for the title. Receivers
   (`CAMActivity.java:670-673`, `HDMIActivity.java:702-704`):
   `com.szchoiceway.eventcenter.EventUtils.ACTION_SWITCH_CAM_CHANNEL` (int extra
   `EventUtils.CAM_CHANNEL_NUM` 0/1/2), `com.szchoiceway.eventcenter.force_camera_close`,
   `MCU_CAR_CAN_RADAR_INFO`, `ACTION_VOICE_CTRL`, CLOSE_SYSTEM_DIALOGS. Brake gating identical to video
   (`SYS_CUR_BREAK_STATE_KEY`). SysVar: `CAM_CHANNEL_KEY` (persisted camera), `CAM_FullScreenMode_Key`,
   AUX colour keys (`SYS_BRIGHT/CONTRAST/HUE/MSATURATION_AUX_SET`), `SYS_BACKCAR_CAMERA_MIRRORING`.
   Signal detection polls sysfs `/sys/video_state/`, `/sys/pr2000/pr2000`
   (`camera/CamerasSignalDetection.java:8-17`); prop `sys.zxw.support.xs9922b.common.camera`.
4-9. Relevant to the launcher's reverse-camera TODO: the video path is the plain Android Camera HAL
   (`Camera.open`), selected by `IEventService.setCameraChannel("<n>")` after claiming a `SRC_*CAM`
   mode. Nothing here relates to `Set_NavPackageName`.

### com.szchoiceway.photoreader

1. Bundled demo slideshow (16 `icon_demo_*` mipmaps, en/zh variants), auto-advances every 5 s, any
   touch finishes it (`activity/RunActivity.java:7-8,25-30`). Not a photo viewer for user media.
2. Manifest: `RunActivity` exported singleInstance, **no intent filter** (start by component only);
   the launcher-visible "Pictures" icon (`ICON_PIC`) is not this app.
3. No gateway, no broadcasts, no SysVar.
4-9. n/a.

### com.szchoiceway.learn.key

1. Steering-wheel / panel key learning UI (Application class `com.szchoiceway.SettingApp`, same lib as
   gps). Four learners: `CarWheelActivity` (resistive SWC), `McuWheelActivity` (panel keys),
   `TouchKeyLearnActivity` / `SecondTouchKeyLearnActivity` (capacitive touch strips).
2. Manifest: all four activities exported singleInstance, each in its own `:process`; first three
   have MAIN/LAUNCHER filters. Lib provider/service exported as in gps.
3/6. **Protocol** (`view/CarWheelView.java`): learning is driven with `IEventService.sendWheelKey(n)`
   (`:376-378`), which the gateway sends to the MCU as frame `{0x07, n}`
   (`EC/EventService.java:6369-6371`). Control codes: `112` enter learn mode (`:366-367`, on window
   visible), `113` exit, `114` save (`:337,370-372`), `115` clear all (`:341`), `116` high-impedance /
   `117` low-impedance wheel (`:315-326`, also toggles bit 2 of SysVar `Sys_McuSet`). To learn a
   function: pick it on screen, the view assigns the lowest free slot 0..14 (`:78-86`) and sends
   `sendWheelKey(slot)` (`:102`); user presses the wheel key; the gateway broadcasts
   `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR` (extras `…STEER_WHEEL_INFOR_LPARAM` = slot,
   `…WPARAM` = success flag, `…VOLTAGE` = ADC 0-255 → `*3.3/255` V, `:160-164`) and
   `…STEER_WHEEL_STATUS` with int extra `EventUtils.STEER_WHEEL_STUDY_STATUS` = 16-bit mask of learned
   slots (`EC/EventService.java:3065-3066`; lib receiver `zxw/lib/ui/broadcast/IBroadcastImp.java:130`).
   Function→slot map is persisted as a **JSON string** `{"<icon title>":"<slot>"}` in SysVar
   `wheel_key_learn_custom` (`:293`, `zxw/lib/ui/util/SystemPropertiesHelps.java:733-734`); panel keys
   in `mcu_panel_key_learn_custom` (`:737-738`). Function icon ids (`view/item/ItemIconBeanView.java`
   `IdsEnum`): `svg_wheel_mode_c, next_c, pre_c, gj, dh(phone), jy(mute), gd(hang up), jt(answer),
   s_add, s_j, mode_voice, mode_360, mode_fm, mode_back, mode_home, mode_ok, mode_video, mode_music…`
   (slot value = bit index, `BitMasksEnum :19-33`). Consequence for the launcher: the
   `STEER_WHEEL_INFOR` LPARAM the launcher already decodes is a *slot* number; which function it means
   is whatever `wheel_key_learn_custom` maps it to. The launcher's `SteeringWheelSettingsScreen.kt:39`
   treats that key as a 0/1 scalar — it is not.
   Touch-key learning uses broadcasts `com.choiceway.FatUtils.ZXW_TOUCH_LEARN_ID/INFOR/STATUS` (+`_EXTRA`)
   and writes `/data/local/touchkeycfg.xml` (chmod 777) / `/mnt/privdata1/.zxwfactory/touchkeycfg.xml`,
   then `com.choiceway.FatUtils.ZXW_RELOAD_TOUCH_KEY_CFG` (`settings/TouchKeyLearnActivity.java:8-13,
   156,177`, `fattouchkey/TouchKeyConfig.java:94`).
4,5,7-9. n/a.

### com.szchoiceway.apkinstall

1. Vendor "one-click" APK installer from USB. Icon `@drawable/icon_app_logo_apk`.
2. Manifest: `MainActivity` exported MAIN/LAUNCHER; `InstallAllActivity`, `ApkCopyActivity`,
   `ApkDeleteActivity`, `UsbInstallActivity` exported, no filters. targetSdk 28. Perms include
   INSTALL_PACKAGES, DELETE_PACKAGES, RECOVERY, SYSTEM_ALERT_WINDOW.
3. Binds the gateway by component (`ApkInstallApp.java:58`) but calls nothing on it. Listens
   MEDIA_MOUNTED/UNMOUNTED, WIFI_STATE_CHANGED (`MainActivity.java:179-181`).
7. **What it can do**: runs `pm install -r <path>` for every APK under `<usb>/apk_cn` or `/apk_en`
   via the init hook (`InstallAllActivity.java:404-410,459-460`; polls `sys.apk_path==true`),
   copies those folders to `/mnt/privdata1/apk_{cn,en}` (`ApkCopyActivity.java:12-15`) and can delete
   them. `/data/local/cmd` is a leftover constant (`:5`). Danger for a rooted launcher: `pm install -r`
   of a same-package APK signed with a different key fails, but a *same-key* APK on a USB stick
   named `apk_en/` would silently replace the launcher on the next "install all". It cannot uninstall
   third-party packages by itself (delete only removes files from `privdata1`).

### com.szchoiceway.canbusdebug

1. Engineering tap: a floating hex console showing every CAN/MCU frame. No launcher icon.
2. Manifest: only `CanbusDebugService` exported (no filter; start by component). `MainActivity`
   exists (gesture-unlock screen, `MainActivity.java:19-23`) but is not exported. Perms:
   SYSTEM_ALERT_WINDOW, SYSTEM_APPLICATION_OVERLAY, storage.
3. Receives (`CanbusDebugService.java:87-95`): `com.choiceway.eventcenter.EventUtils.MCU_MSG_CAN_ALL_INFO`
   and `…onCmdMcuATAData` (byte[] extra `EventUtils.CAR_AIR_DATA`), `…ACTION_MCU_CMD_EVENT`
   (`EventUtils.MCU_CMD_DATA`), `ZXW_CAN_KEY_EVT`, `CAR_RIGHT_SIGH_EVT`. Sends raw
   `ACTION_MCU_CMD_EVENT` frames typed by the user (`DataUI.java:464-468`) and `ZXW_CANBUS_TEST_ACTION`
   (`:372`). Overlay: `type=2003, flags=288` (`DataUI.java:184-186`).
7. Danger: none passive; the send box is an arbitrary MCU-frame injector (same channel the launcher's
   `ACTION_MCU_CMD_EVENT` uses). Useful as a reference for the CAN bulk frame layout the launcher
   still lacks (`formatData` at `:110-118` shows which extras carry which frame).

### com.lfg.szchoiceway.canupgrade

1. MCU / CAN-box / A/C-box / BT-module firmware flasher (12 vendor CAN protocols under
   `CanParase/`: Raise, Hiworld, Luzheng, Keleos/KSP, Buick/TangDu, Camry, Ford, LHT, Bagu/DAQIE…).
2. Manifest: `MainActivity` MAIN/LAUNCHER (exported attribute absent → implied by filter). Declares
   `com.szchoiceway.permission.broadcast` itself (no protectionLevel) and holds it plus
   `com.choiceway.permission.broadcast`. targetSdk 28.
3. AIDL used (`MainActivity.java:969,1002`, `CanParase/*.java`): `enterUpgradeMode()` /
   `exitUpgradeMode()` (MCU), `enterCanUpgradeMode()` / `exitCanUpgradeMode()`, `sendCanbusData(byte[])`
   (`BaguCanParase.java:657-660`), `sendCanbusUpgradeData`, `responseCanUpgrade*`, `setUpgradeCallback`,
   `changeSetup(SYS_CAN_UPDATE_KEY,"1"/"0")` (`:3561-3564`), `changeSetup("Sys_MCUComBaudRate", …)`
   (`:4055,4071`, packs A/C baud in low byte and CAN baud in high byte). Listens to per-protocol
   progress broadcasts `com.szchoiceway.eventcenter.{keleosCanParase,BuickCanParase,DAQIE,KELEOCan,
   KSPCan}.Upgrade{Start,Data,End,State}` and `…PackageNum` (`KeleosCanParase.java:368-374`).
7. Inputs: `mcu_dapin.bin`, `mcu_zxw_dapin.bin`, `RL7A/78/7G/7W.bin`, `RL88/8A/8G/8W.bin`,
   `RL98/9A/9G.bin`, `canmcuupdate.hex`, `<vendor>canupdate.bin`, `dfu.dfu` (BT module, written to
   `/dev/ttyS3` through `com.goodocom.dfu.DfuApi`, `:156`, `EC/EventUtils.java:1015`) from
   `/storage/usb_storage[1-3]/`, `/storage/emulated/0/`, `external_sd0/1` (`:46-81,173-176`).
   Danger: a bad flash bricks the MCU (no SWC, no power sequencing, no reverse). Nothing here targets
   the Android side or the launcher; keep such files off the USB sticks you leave in the car.

### com.choiceway.weather

1. Weather widget/app (`@mipmap/tianqi`), Chinese provider **Seniverse (心知天气)**.
2. Manifest: `MainActivity` and `UIActivity` both MAIN/LAUNCHER + VIEW, singleInstance;
   `service/WeatherService` exported (no filter). Perms: INTERNET, location, boot.
3. Listens (`service/WeatherService.java:496-500`): `com.choiceway.action.WEATHER_LOAD` (force
   refresh, rate-limited at `:564`), `…WEATHER_EXIT`, `…ACTION_ACC_SLEEP_STATUS_EVT`, LOCALE_CHANGED,
   wifi. Sends (`utils/Util.java:125-198`): `com.choiceway.action.WEATHER_UPDATE` (extras `results`
   Parcelable of its own class, **`json_results` String** = raw API JSON, `load_time` long),
   `…FIVE_DAYS_WEATHER_UPDATE` (same shape), `…AIR_QUALITY_UPDATE`, `…DRIVING_RESTRICTION_UPDATE`, and
   `com.szchoiceway.zxwauto.ACTION_KEYEVENTNOTIFY` (`keycode`,`down`).
8. API (`api/ApiService.java:5-13`): base `https://api.seniverse.com/`, endpoints `v3/weather/now.json`,
   `v3/weather/daily.json`, `v3/air/now.json`, `v3/life/driving_restriction.json`, query
   `key=<vendor key, in the APK>` (a shared vendor key, free tier), `location=<lat>:<lon>` from
   `LocationManager` (`WeatherService.java:149-171`) or a stored city id, `language` from locale,
   `unit=c|f`. Reusable: the `json_results` extra of `WEATHER_UPDATE` is plain Seniverse JSON any app can
   parse; sending `com.choiceway.action.WEATHER_LOAD` triggers a fetch. Whether the key still answers
   from Canada is untested; the free tier is China-centric.

### com.zjinnova.zlink

1. Zlink 5.4.62 (zjinnova) phone-link receiver: CarPlay (wired/wireless), Android Auto, HiCar,
   AirPlay/Android mirror, DLNA, CarLife. **The DEX is packed** (Tencent Legu: `MyWrapperProxyApplication`,
   `com/wrapper/proxyapplication/*`, payload `assets/0OO00l111l1l` 4.3 MB, only 13 stub classes
   decompile). Everything below is from the manifest, `assets/cnf_vendor_zhuoxw.yaml` (vendor channel
   `zhuoxw`, OTG switch commands per SoC — `trinket`/`bengal` = this Qualcomm family, `:22-35`), native
   strings, and the gateway's bridge `EC/manager/ZlinkManage.java`.
2. Manifest: exported activities `features.main.MainActivity` (singleTask; actions MAIN/LAUNCHER,
   **`zjinnova.android.intent.action.ZLINK_MAIN`**, `…MAIN_PAGES`), and per-protocol
   singleInstance launchers with own taskAffinity: `features.launcher.CarPlayActivity`, `AutoActivity`,
   `HiCarActivity`, `MirrorActivity`, `CarLifeActivity`, `CarPlayAutoActivity`, `dlna.DlnaActivity`,
   plus `*UnavailableActivity` and `ActivationActivity` (licensing). Receivers: `StartupBroadcastReceiver`
   (`zjinnova.android.intent.action.START_DAEMON_SERVICE`, `zjinnova.intent.action.START_ZLINK_SERVICE`,
   `com.zjinnova.zlink`, LOCKED_BOOT_COMPLETED), `MediaButtonReceiver` (MEDIA_BUTTON),
   `PhoneStateReceiver`, `InstallUpgradeBroadcastReceiver` (DOWNLOAD_COMPLETE → self-update).
   Service `DaemonService` exported behind its own permission `zjinnova.android.permission.ZLINK_SERVICE`.
   Providers: FileProvider (not exported), Tencent MID provider (exported, analytics). Perms: everything
   (BLUETOOTH_PRIVILEGED, NETWORK_SETTINGS, OVERRIDE_WIFI_CONFIG, READ_LOGS, MANAGE_EXTERNAL_STORAGE…).
   targetSdk 23, minSdk 21.
3/9. **Bridge protocol** (gateway side, `ZlinkManage.java`). zlink → gateway: broadcast action
   `com.zjinnova.zlink` with String extras `status`, `command`, `phoneMode`, `phoneType` (`:23-33,140-150`).
   `status` values handled (`:205-300`): `MAIN_PAGE_SHOW`, `MAIN_PAGE_HIDDEN`, `EXIT`, `CONNECTED`
   (stores `phoneMode` into SysVar `SYS_ZXW_ZJ_PHONELINK_TYPE_KEY`, values `carplay_wired|carplay_wireless|
   auto_wired|auto_wireless|hicar_*|airplay_*|android_mirror_*|dlna_*`, then `onStartCarPlayMode()` →
   `setCurModeCallback(32, cb)` + `sendMode(SRC_CARPLAY=32, true)` `:365-366`), `DISCONNECT`
   (`exitCurMode(32)`), `PHONE_CALL_ON/OFF` (mutes streams 3/4, forwards to AutoNavi
   `AUTONAVI_STANDARD_BROADCAST_RECV KEY_TYPE=10047`), `MAIN_AUDIO_START/STOP` →
   `setValidModeInfor("Carplay"|"Android Auto"|"HUAWEI HiCar"|"Airplay"|"DLNA", "", playing)`
   (`:591-605`). **That is the whole now-playing path: title = protocol name, no track metadata**
   (`Title` in `VALID_MODE_INFOR_CHANGE` will read "Carplay"). `command` values: `REQ_OS_AUDIO_FOCUS`,
   `ACTION_ZJ_PHONEFOUND/IPODFOUND`, `CMD_MIC_START/STOP`, `EVENT_DEVICE_MIC_REQUEST/RELEASE`.
   Config RPC: `com.zjinnova.zlink.GET_DATA_REQ` (`key`) → gateway answers `…GET_DATA_RES` (`key`,`value`
   from OS config), `…WRITE_DATA` (`key`,`value`) (`:23-26,153-172`).
   Gateway → zlink (all `sendBroadcastAsUser(ALL)`, no permission): `com.zjinnova.zlink` with
   `command=SILENT|SILENT_BREAK` (`:437-441`), `ACTION_ENTER|ACTION_EXIT` (`:448-453`),
   `REQ_SPEC_FUNC_CMD` + int `specFuncCode` (`:608-613`; codes `KEYCODE_SPEECH_ON=1500, LEFT_TURN=1501,
   RIGHT_TURN=1502, REQUESTUI_MAP=1504, PHONECALL=1505, MUSIC=1506, NOWPLAYING=1507, HOME_PAGE=1508`
   `:35-42`, and Android media keys 85/87/88 forwarded verbatim while mode==32,
   `EC/EventService.java:13442-13448`; `5`/`6` are sent on SWC events `:2565,2570`). Day/night:
   `com.zjinnova.zlink.action.OUT_DARK_START/STOP` + prop `rw.out.dark` (`:184-187`); ACC:
   `…action.POWER_ON/POWER_OFF` (`:194-196`). Phone status out to the estate:
   `com.szchoiceway.eventcenter.EventUtils.ACTION_CARPLAY_TELEPHONE_STATUS_EVENT` int
   `EventUtils.ACTION_CARPLAY_TELEPHONE_STATUS_DATA` (`EC/EventUtils.java:53-54,2666-2667`), derived by
   polling prop `vendor.audio.hu.mic` every 300 ms (`ZlinkManage.java:516-536`).
   **Launching**: `startZlinkActivity()` (`:486-505`) picks the activity from
   `SYS_ZXW_ZJ_PHONELINK_TYPE_KEY` (HiCar / Auto / Mirror / Dlna / else `CarPlayActivity`) via
   `EventUtils.startActivityIfNotRuning(ctx, "com.zjinnova.zlink", cls)` (`EC/EventUtils.java:2217-2230`,
   plain `Intent` + class name). `startZlinkMainActivity(page, feature)` fires
   `zjinnova.android.intent.action.ZLINK_MAIN` with String extras `page`, `feature` (`:476-483`).
   Voice "open CarPlay" does `postRunModeActivity(32)` (`EC/model/VoiceCtrlModel.java:440`).
   **"Customer type" gate**: `SYS_CUSTOMER_TYPE_KEY` is *not* consulted in `ZlinkManage`; what gates
   zlink is (a) prop `rw.zlink.disable.features` written from `SYS_LAUNCHER_APP_HIDE_KEY` — letters
   `w l a b d y z q r h i c e` disable protocols (default `ldyzqrce`; hiding `CarPlayActivity` in the
   launcher-hide list yields `wldyzqrce`, hiding btsuite yields `wlabdyzqrhice`) followed by
   `killProcess(com.zjinnova.zlink)` (`:568-584`); (b) `displayIcon()` enabling/disabling zlink
   components with `setComponentEnabledSetting` (`:512`); (c) licensing props `sys.zlink.regcode`,
   `sys.zlink.barcode`, `rw.zlink.mfi.id`, `rw.zlink.hw.modelId` (native strings, `libCoreUtils.so`);
   (d) `SYS_CARAUTO_RADIO_RUNNING` suppresses the CarPlay mute on mode change (`:64,77`).
   Other native props of note: `rw.zlink.mode.default`, `rw.zlink.aa.display`, `rw.zlink.conn.bg`,
   `rw.zlink.bt.*`, `zj.phonelink.type`, `zj.carplay.os.new`, `zj.driver.pos`, `persist.zj.aec*`.
4. Source int `SRC_CARPLAY=32` for every protocol (`:366,376`). No MediaSession observable from the
   decompile (packed); the launcher's MediaScreen already sees zlink as a session, so it exposes one
   at runtime. Steering keys reach it only as `REQ_SPEC_FUNC_CMD` broadcasts from the gateway.

---

### Launcher opportunities

Checked against `dr/launcher/README.md`, `CAR_API.md` and a grep of the launcher tree: none of the
identifiers below appear there today except where noted.

1. **Use the `sendMode` value table.** `EventUtils.eSrcMode` (`EC/EventUtils.java:2006-2065`) is in the
   decompile; README §"Known TODOs" says it is not. MediaScreen can switch sources:
   `sendMode(11,false)` + `setCurModeCallback(11, cb)` = vendor music, `10` video, `1` radio, `7` BT
   music, `32` CarPlay, `19` HDMI, `50/51/52` cams. Mirror the vendor pairing (`setCurModeCallback`
   *after* `sendMode`, `exitCurMode(n)` when leaving) and remember `sendMode` calls `kill3rdAPK()`.
2. **Subscribe to `VALID_MODE_INFOR_CHANGE`** instead of polling `getValidMode*Infor()`: one
   unprotected broadcast with title/album/artist/track/time/loop/play-state for whichever vendor source
   is live. Replaces the 3 s poll for the vendor half of the media card.
3. **Drive the vendor players without their UI**: broadcast `com.szchoiceway.ACTION_GET_CUR_PLAY_LIST`
   (`EXTRA_CUR_PLAY_LIST_TYPE`=11 or 10) to receive `com.szchoiceway.ACTION_NOTIFY_CUR_PLAY_LIST`
   (String[] list + index), then `com.szchoiceway.ACTION_PLAY_BY_INDEX` (`…_TYPE`, `EXTRA_INDEX`) to
   jump; `MUSIC_PLAY_LIST_ACTION`/`ZXW_ACTION_NOTIIFY_MEDIA_PLAY_PATH` give live path/list updates. Keep
   the player service alive the vendor way: `com.szchoiceway.ACTION_START_SERVICE` to
   `com.szchoiceway.eventcenter` with `servicePackageName`/`serviceName`.
4. **Library browser for free**: query `content://com.szchoiceway.zxwmedia.provider.FileProvider/query/1`
   (exported, no permission) for the scanned `MusicFileList` (title/artist/album/path/fileType).
   Force a rescan with the `ACTION_RESCEN_BY_FLASH_*` intents or by starting
   `com.szchoiceway.zxwmedia/.ScanFileService`.
5. **CarPlay deep links.** Fire `Intent("zjinnova.android.intent.action.ZLINK_MAIN")` with `page`/
   `feature` extras, or start `com.zjinnova.zlink/com.zjinnova.android.zlink.features.launcher.CarPlayActivity`
   (Auto/HiCar/Mirror variants likewise) — exported, no permission. Choose by SysVar
   `SYS_ZXW_ZJ_PHONELINK_TYPE_KEY` exactly as `startZlinkActivity()` does. Send
   `com.zjinnova.zlink` + `command=REQ_SPEC_FUNC_CMD` + `specFuncCode=1500/1504/1506/1507/1508` for
   Siri / Maps / Music / Now Playing / Home from launcher buttons or SWC (the gateway sends these
   itself unpermissioned, so a normal app can). Listen to `com.zjinnova.zlink` (`status=CONNECTED|
   DISCONNECT|MAIN_PAGE_SHOW|MAIN_PAGE_HIDDEN`, `phoneMode`) for a live "phone connected" chip, and to
   `…ACTION_CARPLAY_TELEPHONE_STATUS_EVENT` for call state. The launcher today only labels the package
   (`media/SourceLabels.kt`, `HomeScreen.kt:428`).
6. **Do not** put `CarPlayActivity`/`AutoActivity`/`HiCarActivity` class names or `com.szchoiceway.btsuite`
   into `SYS_LAUNCHER_APP_HIDE_KEY`: the gateway rewrites `rw.zlink.disable.features` from that string
   and kills zlink (`ZlinkManage.java:568-584`).
7. **SWC learning is not a scalar.** `wheel_key_learn_custom` holds JSON `{"svg_wheel_<fn>":"<slot>"}`;
   `STEER_WHEEL_INFOR_LPARAM` is the slot. `SteeringWheelSettingsScreen.kt:39-41` should read the map
   to label wheel presses by function, and can host its own learner: `sendWheelKey(112)`, per function
   `sendWheelKey(slot)`, wait for `STEER_WHEEL_INFOR` (WPARAM success) / `STEER_WHEEL_STATUS`
   (`EventUtils.STEER_WHEEL_STUDY_STATUS` mask), `sendWheelKey(114)` to save, `113` to exit. Write the
   JSON back via root `content update`.
8. **Nav app launch parity**: the gateway launches the configured nav with `Intent(MAIN)` +
   `setComponent(Set_NavPackageName, Set_NavClassName)`, or `postRunModeActivity(42)` does it for you
   (and is what SWC key 55 fires). `NavRepository.kt` already reads the keys; calling
   `postRunModeActivity(42)` would also give the gateway its bookkeeping (`lastStandByMode`, audio).
9. **Camera views**: instead of hosting `BackCarActivity`, start
   `com.szchoiceway.navigation/.CAMActivity` (front/rear/blind, channel via
   `ACTION_SWITCH_CAM_CHANNEL` int `EventUtils.CAM_CHANNEL_NUM` 0/1/2) or `.HDMIActivity`; close them
   with `com.szchoiceway.eventcenter.force_camera_close`. For an embedded feed the recipe is
   `sendMode(50|51|52,true)` → `setCameraChannel("9"|"10"|"2")` → `Camera.open(0)` on a SurfaceView
   (`CAMActivity.java:70-89`, `camera/CameraManager.java:119-156`), but that needs the camera claimed
   while the vendor apps are not.
10. **Video PIP**: `com.szchoiceway.videoplayer/.PIPService` is exported; binding it from the launcher
    gives the vendor's floating player. Its return path relies on prop `StartFromPIP` (system-uid only),
    so for the launcher start `MainActivity` directly.
11. **Weather without an API key**: register for `com.choiceway.action.WEATHER_UPDATE` /
    `FIVE_DAYS_WEATHER_UPDATE` / `AIR_QUALITY_UPDATE` and parse the `json_results` String (Seniverse v3
    JSON); send `com.choiceway.action.WEATHER_LOAD` to refresh. Verify the vendor key still answers for
    `lat:lon` outside China before building a card on it.
12. **Voice/text control of vendor apps** is Chinese-only (`ACTION_VOICE_CTRL` `VoiceKeyWord` matched
    against strings like `播放本地音乐`); not worth wiring for an English UI.
13. **Danger list for the rooted launcher** (do not expose these as tiles): `com.szchoiceway.apkinstall`
    (`pm install -r` of anything under `<usb>/apk_en`), `com.lfg.szchoiceway.canupgrade` (MCU/CAN-box
    flash, `enterUpgradeMode()` with no confirmation beyond the file existing),
    `com.szchoiceway.canbusdebug/.CanbusDebugService` (arbitrary MCU frame injector). `AppRepository`
    should keep them hidden or behind the parked-only gate.
14. `ICallbackfn` is confirmed (`notifyEvt(int,int,int,byte[],String)`, ordinals 1/2); README's "signature
    never recovered" note is stale. `setCurModeCallback(n, cb)` after `sendMode(n)` delivers 4097/4098
    events to the launcher exactly as to the vendor players — that is the push channel for SWC media
    keys when the launcher owns the source.

---

