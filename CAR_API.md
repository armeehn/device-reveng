# Choiceway GT6-EAU — Car Integration API Reference

Developer spec for building a **custom launcher** and **custom apps** that integrate with the car,
reverse-engineered from the decompiled head-unit apps on this device (Android 13, 1920x720
landscape, rooted).

**Primary source (the car gateway):** `com.szchoiceway.eventcenter`
(`mcu-analysis/eventcenter-src/`). This app owns the serial link to the MCU/CANBOX, decodes car
events, re-broadcasts them to the rest of Android, and exposes an AIDL control service. It runs as
`sharedUserId="android.uid.system"`.

Evidence is cited as `file:line` relative to
`mcu-analysis/eventcenter-src/sources/`. Every item is tagged **[confirmed]** (present in decompiled
code) or **[inferred]** (deduced, not directly provable from the files we have).

> **APK note:** `mcu-analysis/apks/com.szchoiceway.canbus2.apk` is **truncated/corrupt** (jadx and
> unzip both fail on it). Several declarations we would like to quote verbatim — the
> `SysVarProvider` `<provider>` element and the `<permission android:protectionLevel=...>` for the
> Choiceway broadcast permission — live in that app (or a framework/settings overlay) and could
> **not** be recovered. Those points are flagged **[inferred]** below.

---

## 0. TL;DR for the custom launcher / app author

| You want to… | Use | Requires |
|---|---|---|
| Know when the car goes into/out of **reverse** | Receive `com.choiceway.eventcenter.ACTION_BACKCAR_START` / `_END` | Hold `com.szchoiceway.permission.broadcast` |
| Know **ACC on/off** and **sleep** | Receive `...ACTION_ACC_OPEN_CLOSE_EVT` / `...ACTION_ACC_SLEEP_STATUS_EVT` | none (sent unprotected) |
| Get **steering-wheel key** presses | Receive `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR` | Hold the permission |
| Get **climate / A/C** state | Receive `com.choiceway.canbus.carairstruct` (Parcelable `CarAirState`) | none, but need the `CarAirState` class |
| Get **parking radar** distances | Receive `...MCU_CAR_CAN_RADAR_INFO` (byte[]) | none |
| Read/write a **car setting** (car type, reverse video type, illumination, radio favorites…) | `ContentResolver` on `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` | read: none; write: system/root **[inferred]** |
| **Control** the radio, EQ, volume, mute, mode switch, send raw CAN | Bind AIDL `com.szchoiceway.eventcenter.EventService` | exported service; some ops effectively need system app **[inferred]** |
| Be the **HOME launcher** | Normal `<category HOME/DEFAULT>` activity | normal app OK; system app for privileged widgets |

**Big caveat for launchers:** the stock launcher `com.szchoiceway.customerui` and the gateway both
run as `android.uid.system`. A **normal** third-party launcher can receive every broadcast below and
read the provider, but **cannot** hold `android.uid.system`, so a few things (writing SysVar,
binding some privileged flows, `WRITE_SECURE_SETTINGS`) will need the launcher to be installed as a
**privileged/system app** (push to `/system/priv-app` + platform signature) — feasible because the
device is rooted. See §6.

---

## 1. Broadcast / Intent API

### 1.1 The Choiceway broadcast permission

```
EventUtils.java:1698
    public static final String PERMISSION_CHOICEWAY_BROADCAST = "com.szchoiceway.permission.broadcast";
```

- **Name:** `com.szchoiceway.permission.broadcast`
- **How it is used:** the gateway sends its *protected* car events with
  `sendBroadcastAsUser(intent, UserHandle.ALL, PERMISSION_CHOICEWAY_BROADCAST)`
  (e.g. `EventService.java:8978, 8994, 2856, 3239, 3309, 4749, 4848…`; `EvtModel.java:127, 918`;
  helper `EventUtils.java:2144`). Only receivers that **hold** this permission receive those
  broadcasts. Many other events (ACC, speed toggle, GPS) are sent **without** a permission arg and
  are receivable by anyone.
- **Protection level:** **[inferred]** — the `<permission>` element is not declared in the
  eventcenter or auxcamera manifests (checked) and the canbus2 APK is corrupt. Given that all
  producers are `android.uid.system` and it is a vendor-private permission, it is almost certainly
  `signature` or `signatureOrSystem`. **If it is `signature`, a normal app cannot obtain it** and
  will silently miss the protected broadcasts — your app then either needs the platform signature
  (system app) or must fall back to the unprotected events / the AIDL service / raw serial.
  To use it: `<uses-permission android:name="com.szchoiceway.permission.broadcast"/>`.

### 1.2 How the gateway sends broadcasts (pattern to mirror)

```
EventUtils.java:2143  sendAirInfoBroadcast(...)  -> Intent(action).putExtra(CAR_AIR_DATA, byte[]) ; sendBroadcastAsUser(.., PERMISSION_CHOICEWAY_BROADCAST)
EventUtils.java:2147  sendKeyEventBroadcast(ctx,int) -> Intent(MCU_KEY_INFOR_ACTION).putExtra(MCU_KEY_VALUE,int)
EventService.java:8972 startBackcar() -> sendBroadcastAsUser(new Intent(ACTION_BACKCAR_START), ALL, PERMISSION_CHOICEWAY_BROADCAST)
```

All action/extra names below are `public static final String` in `EventUtils.java` unless noted.

### 1.3 CAR → APP events (you register a `BroadcastReceiver`)

| Action string | Const (`EventUtils`) | Extras (key → type → meaning) | Perm? | Evidence |
|---|---|---|---|---|
| `com.choiceway.eventcenter.ACTION_BACKCAR_START` | `ACTION_BACKCAR_START` | none | **yes** | `EventService.java:8978` **[confirmed]** |
| `com.choiceway.eventcenter.ACTION_BACKCAR_END` | `ACTION_BACKCAR_END` | none | **yes** | `EventService.java:8994` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.MCU_MSG_BACKCAR_START` | `MCU_MSG_BACKCAR_START_EVT` | none | no | `EventService.java:664` (raw MCU-level reverse) **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.MCU_MSG_BACKCAR_END` | `MCU_MSG_BACKCAR_END_EVT` | none | no | `EventService.java:709` **[confirmed]** |
| `...EventUtils.ACTION_ACC_OPEN_CLOSE_EVT` | `ACTION_ACC_OPEN_CLOSE_EVT` | `ACC_Status` → int → 1=ACC on, 0=off | no | `EventService.java:3404-3407` **[confirmed]** |
| `...EventUtils.ACTION_ACC_SLEEP_STATUS_EVT` | `ACTION_ACC_SLEEP_STATUS_EVT` | `ACC_Status` → int, 1 = awake (`:492,2274`), 0 = entering sleep (`:3535`); also targeted to `com.szchoiceway.btsuite/.BTServiceAutoStart` | no | `EventService.java:3397-3400` **[confirmed]** |
| `...EventUtils.HANDLER_ACC_POWER_OFF_EVT` | `HANDLER_ACC_POWER_OFF_EVT` | — | no | const `EventUtils.java` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR` | `STEER_WHEEL_INFOR` | `EventUtils.STEER_WHEEL_INFOR_LPARAM` → int **learned slot + 1** (`bArr[1]+1`, 1..10 — NOT a `CAR_KEY_*` code; the function is whatever `wheel_key_learn_custom` maps that slot to, §4), `..._WPARAM` → int (3=down,4=up/release), `..._VOLTAGE` → int (ADC 0..255, ×3.3/255 V) | **yes** | `EventService.java:2846-2857` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_STATUS` | `STEER_WHEEL_STATUS` | `EventUtils.STEER_WHEEL_STUDY_STATUS` → int, 16-bit mask of learned slots | — | `EventService.java:3065-3066` **[confirmed]**, see §4 |
| `com.szchoiceway.btsuite.HBCP_EVT_BT_POWER_STATUS` | (btsuite `BTUtils`) | `com.szchoiceway.btsuite.DATA_INT` → 1 on / 0 off, `..DATA_STR` → "" | no | `ParseFEasycom.java:175-179` **[confirmed]** |
| `com.szchoiceway.btsuite.HBCP_EVT_HSHF_STATUS` / `_HSHF_GET_STATUS` | (btsuite) | `DATA_INT` → HFP state 0 init, 1 ready, 2 connecting, 3 connected, 4 outgoing, 5 incoming, 6 active call (connected = ≥3, in call = >3) | no | `ParseFEasycom.java:412,423`, `BTUtils.java:115-121` **[confirmed]** |
| `com.szchoiceway.btsuite.HBCP_EVT_CUR_CONNECTED_DEVICE_NAME` | (btsuite) | `DATA_STR` → phone name (sent on control key 8) | no | `BTService.java:1536-1538` **[confirmed]** |
| `com.szchoiceway.btsuite.HBCP_EVT_AV_STATUS` | (btsuite) | `DATA_INT` → 4 playing / 3 paused, `DATA_STR` → track title | no | `BTService.java:262-265` **[confirmed]** |
| `com.szchoiceway.btsuite.HBCP_EVT_CONTACT_NUM` / `_CONTACT_NAME` | (btsuite) | `DATA_STR` → caller number / name (HFP states 4/5) | no | `ParseFEasycom.java:498-499` **[confirmed]** |
| `com.szchoiceway.btsuite.HBCP_EVT_SPEAKING_TIME` | (btsuite) | `DATA_INT` → **int[]** {min, sec} | no | `BTService.java:746-749` **[confirmed]** — no battery/signal exists on this surface |
| `com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR` | `MCU_KEY_INFOR_ACTION` | `EventUtils.MCU_KEY_VALUE` → int `MCU_KEY_*` code (`EventUtils.java:1458-1656`); one broadcast per press, no edge, long presses are their own codes | no | `EventUtils.java:2147-2153`, sent `EventService.java:8966` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.ACTION_HOST_MCU_BUTTON_KEY` | `ACTION_HOST_MCU_BUTTON_KEY` | `HostKeyWord` → int 1..4 (2 vol−, 3 vol+, 4 mute), `HostKeyStatus` → byte 1 down / 0 up. **Not a key path**: the volume relay to an original-car amplifier, sent only when that routing is configured (`:4224-4235`) | no | `EventService.java:4271-4289` **[confirmed]** |
| `...EventUtils.SHOW_CAR_SPEED_EVENT` | `SHOW_CAR_SPEED_EVENT` | none (UI toggle only; **speed value is not in this intent** — see note) | no | `EventService.java:5214,5747,6280` **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO` | `MCU_CAR_CAN_RADAR_INFO` | `EventUtils.CAR_CAN_DATA` → byte[9] `{1, F1..F4, R1..R4}`; each a distance code 30 (closest) / 60 / 90 / 110 / 150, 0xA0 = clear, 0 = no data. Sent by canbus2 only while `Sys_Plugin_radar_Set` = 0. Left→right order within a bank UNRESOLVED | no | `CanDataParseBase.java:1221-1229`, `HiworldCanParseToyota.java:903-921` **[confirmed]** |
| `...EventUtils.MCU_CAR_CAN_PLUG_IN_RADAR_INFO` | `MCU_CAR_CAN_PLUG_IN_RADAR_INFO` | byte[] `CAR_CAN_DATA` | no | const **[confirmed]** |
| `EventUtils.CAR_RADAR_STATE_EVT_TRA`, `CAR_RADAR_BEEP_EVT_TRA` | — | radar on/off + beep | — | consts **[confirmed]** |
| `com.szchoiceway.canbus.carairstruct` | `CAN_NEW_CAR_AIR_DATA_INFO_EVT` | `com.choiceway.canbus.carairstruct.airstate` (`CAN_NEW_CAR_AIR_DATA_INFO`) → **Parcelable `com.szchoiceway.canbus.CarAirState`** | no | broadcast `EvtModel.java:1133-1135`, received `EvtModel.java:749-750` **[confirmed]** |
| `...EventUtils.MCU_CAR_AIR_INFO` (+ `_CROWN`, `_CROWN_OTHER`) | `MCU_CAR_AIR_INFO` | raw A/C frame variants | no | consts **[confirmed]** |
| `...EventUtils.MCU_CAR_AIR_CLICK` | `MCU_CAR_AIR_CLICK` | A/C panel key echo | no | const **[confirmed]** |
| `...EventUtils.SHOW_CAR_AIR_EVT` / `HIDE_CAR_AIR_EVT` / `ACTION_SHOW_CAR_AIR_WND_EVENT` | — | `EXTRA_SHOW_WND_DATA`("EventUtils.ACTION_SHOW_WND_DATA") → int 0/1 | no | `EventService.java:8970-8973` **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.CAR_LIGHT_STATE` (+ `_LEFT`, `_RIGHT`) | `CAR_LIGHT_STATE*` | turn-signal / lamp state | no | consts **[confirmed]** |
| `...EventUtils.MCU_CAR_RIGHT_SIGH_EVT`, `CAR_RIGHT_SIGH_EVT` | — | `CAR_RIGHT_SIGH_EVT_TRA` (turn-signal / camera assist) | no | consts **[confirmed]** |
| `com.szchoiceway.ACTION_DAY_BACKLIGHT_CHAGNED` | `ACTION_DAY_BACKLIGHT_CHENAGHED` | none. **Not illumination**: fires when the SysVar brightness target `Set_Day_Light` changes | **yes** | `EventService.java:4847-4853` **[confirmed]** |
| `com.szchoiceway.ACTION_NIGHT_BACKLIGHT_CHAGNED` | `ACTION_NIGHT_BACKLIGHT_CHENAGHED` | none. Same, for `Set_Night_Light` | **yes** | `EventService.java:4852,5384` **[confirmed]** |
| `com.szchoiceway.eventcenter.LAMP_STATUS` | `LAMP_CONNECTION_CHANGE` | none; headlamp state in SysVar `Sys_LAMP_STAUS_CHECK` "1"/"0", written first (`:2340`) | no | `EventService.java:802` **[confirmed]** |
| `com.szchoiceway.uiModeNightChanged` | (literal) | `mode` → boolean, true = night: the system night mode the gateway just applied | no | `EventService.java:14089-14093` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.RefreshBacklight` | `ACTION_REFRESH_BACKLIGHT` | brightness/illumination refresh | — | const **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.ACCORD_DOOR_INFO` | `ACCORD_DOOR_INFO` | `EventUtils.CAR_DOOR_DATA` → byte: 0x80 FL, 0x40 FR, 0x20 RR, 0x10 RL (rear pair swapped by `Sys_Rear_Door_Tip_Set`), 0x08 tailgate, 0x04 bonnet; set = open. `MCU_CAR_DOOR_INFO` from the gateway only ever carries 0 | no | `CanDataParseBase.java:453-460,1260-1296`, `DoorInfoWindow.java:211-291` **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_INFO` | `MCU_CAR_CAN_INFO` | `EventUtils.CAR_CAN_DATA` → byte[3] `{speed km/h, rpmH, rpmL}` from canbus2 (Hiworld frame 0x32). **Not a bulk frame.** ⚠ the 0x32 speed field did not track road speed on the 2026-08-29 drive | no | `CanDataParseBase.java:1205-1208` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.MCU_MSG_CAN_ALL_INFO` | `MCU_MSG_CAN_ALL_INFO` | `EventUtils.CAR_AIR_DATA` → byte[] raw MCU 0xA5 frame (the framed CANBOX stream) | no | `EventService.java:2060-2067` **[confirmed]** |
| `...EventUtils.CAN_BASIC_EVT` | `CAN_BASIC_EVT` | **never sent**; the receiver at `EvtModel.java:522` is an empty `return` | — | const only |
| `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT` | `CAN_CAR_OUT_SIDE_TEMP_EVT` | `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR` → String, unit-suffixed (e.g. "23℃"); the int `..._EXTRA` is never put | no | `CanDataParseBase.java:1552-1555`, `CanUtils.java:14-15` **[confirmed]** |
| `...EventUtils.CAN_TPMS_DATA_EVT`, `CAN_SEAT_DATA_EVT`, `CAN_SLS_DATA_EVT`, `CAN_FUEL_CONSUMPTION_INFOR`, `CAN_CENTER_CONSOLE_INFOR`, `CAN_CAR_TIRP_INFO` | — | **never sent**: constants only (`EventUtils.java:186-201`, `Camera360Receiver.java:13`). TPMS and trip data stay inside canbus2's EventBus | — | consts |
| `com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL` | `MCU_MSG_MAIL_VOL` | `...MCU_MSG_MAIL_VOL_VAL` → int `(mute ? 0x80 : 0) \| volume`, `...MCU_MSG_SHOW_VOL_WND` → boolean | no | `EventService.java:3105-3125` **[confirmed]** |
| `...EventUtils.MCU_408_INFO0/1/2`, `MCU_408_CRUISE_SPEED`, `MCU_408_MEM_SPEED`, `MCU_3DH_INFO` | — | car-specific dashboards (PSA 408 etc.) | no | consts **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT`, `...RADIO_EVENT_INFOR`, `com.szchoiceway.radio.frequency` (`BROADCAST_RADIO_FREQUENCY_EVENT`, extra `com.szchoiceway.radio.frequency_extra`) | — | radio band/freq/RDS | no | consts **[confirmed]** |
| Music now-playing: `com.choiceway.musicplayer.ZXW_MUSIC_PLAY_SONG_NAME_EVT` / `..._ARTIST_NAME_EVT` / `..._ALBUM_NAME_EVT` / `..._PLAYFILE_EVT` | `ZXW_MUSIC_PLAY_*_EVT` | matching `*_EXTRA` String | no | consts **[confirmed]** |

> **Getting the numeric car speed:** `SHOW_CAR_SPEED_EVENT` is only a *show/hide* toggle. The actual
> speed lives inside the gateway as `mGpsSpeed` (GPS) or `camera360Receiver.getCanCarSpeed()` (CAN)
> — `EventService.java:1172-1176`. It is **not** published as a clean broadcast extra. To read speed:
> (a) take byte[0] of canbus2's `MCU_CAR_CAN_INFO` digest (above — but see the 0x32 caveat), (b)
> read GPS speed yourself via `LocationManager`, or (c) decode the framed CANBOX stream on
> `MCU_MSG_CAN_ALL_INFO`. **[confirmed]** behaviour; the gateway itself publishes no speed extra.

### 1.4 APP → CAR / APP → gateway commands (you `sendBroadcast`)

These are actions the gateway (`EvtModel`/`EventService`) *listens for* — send them to drive the car
UI or the CAN layer. Registered in `EvtModel.java:210-300`.

| Action | Const | Extra → meaning | Evidence |
|---|---|---|---|
| `com.szchoiceway.ACTION_LAUNCHER_KEY_CTRL` | `ACTION_LAUNCHER_KEY_CTRL` | drives launcher control (`startLauncherCtrl`) | recv `EvtModel.java:906-908` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT` | `ZXW_CAN_KEY_EVT` | `..._EXTRA` → int CAN key code → routed to media/mode logic | recv `EvtModel.java:492-511`; sender helper `EventUtils.java:2610-2617` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.ZXW_SYS_KEY` | `ZXW_SYS_KEY_EVT` | `ZXW_SYS_EXTRA` → int syskey (6=play/pause) | recv `EvtModel.java:541-549` **[confirmed]** |
| `com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT` | `ZXW_CAN_WHEEL_TRACK_EVT` | `com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT_EXTRA` → int: bit7 = raw angle negative, bits 0-6 = \|raw\|/14 (never degrees); which side bit7 means is UNRESOLVED | sent `CanDataParseBase.java:1316-1318`, `HiworldCanParseToyota.java:818-829`; recv `EvtModel.java:534-538` **[confirmed]** |
| `...EventUtils.MCU_CAR_CAN_RADAR_INFO` | `MCU_CAR_CAN_RADAR_INFO` | byte[] `CAR_CAN_DATA` (also used app→gateway to inject radar) | `EvtModel.java:525` **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT` | `ACTION_MCU_CMD_EVENT` | raw MCU command passthrough | const **[confirmed]** |
| `com.szchoiceway.eventcenter.EventUtils.ACTION_CLICK_SYSTEM_KEYCODE_EVENT` | `ACTION_CLICK_SYSTEM_KEYCODE_EVENT` | inject a system keycode | const **[confirmed]** |
| `com.szchoiceway.ACTION_SHOW_HIDE_NAV_BAR` | `ACTION_SHOW_HIDE_NAV_BAR` | show/hide system nav bar | const **[confirmed]** |
| `com.szchoiceway.systemui.Splitscreen` / `switchSplitscreen` / `com.szchoiceway.launcher3.Splitscreen` | `ACTION_SPLITSCREEN` etc. | split-screen control | consts **[confirmed]** |
| `com.szchoiceway.eventcenter.action.SCREEN_SAVER` | `ACTION_SCREEN_SAVER` | `screensaverEnable` (`SCREEN_SAVER_EXTRA_KEY`) → bool | consts **[confirmed]** |
| `com.szchoiceway.eventcenter.action.Reboot` / `ZXW_ACTION_REBOOT_SYS_REBOOT` | `ACTION_SYSTEM_REBOOT` | reboot HU | consts **[confirmed]** |
| `Sidebar_function_action` | `SIDEBAR_FUNCTION_ACTION` | `Sidebar_function_extra` → function id | recv `EvtModel.java` **[confirmed]** |
| `com.szchoiceway.eventcenter.GET_DATA_REQ` / `WRITE_DATA` / `GET_DATA_RES` | `ACTION_CONFIG_GET_DATA/…` | config read/write RPC over broadcast | consts **[confirmed]** |
| `com.szchoiceway.ACTION_VOICE_CTRL` / `ACTION_AIR_VOICE_CTRL` | — | voice control; `AirVoiceKeyWord`, `AirVoiceParam`, `VoiceKeyDIM` extras | consts **[confirmed]** |
| `zxw_bluetooth_contral_action` (to btsuite) | `EventUtils.BLUETOOTH_CONTRAL_ACTION` | `zxw_bluetooth_contral_key` → int 3 audio→phone, 4 audio→car, 5 dial (`zxw_bluetooth_contral_key_value_str` = number), 7 query power, 8 re-send device name, 10 connect (str = MAC), 11 disconnect; receiver registered without a permission | recv `btsuite/BTService.java:1496-1530` **[confirmed]**; effect on the module **[UNVERIFIED]** |
| `com.szchoiceway.btsuite.HBCP_HANGUP_EVENT` (to btsuite) | (btsuite) | none — hang up | recv `BTService.java:1338-1341` **[confirmed]**, effect **[UNVERIFIED]** |
| `...EventUtils.MCU_KEY_INFOR` (to btsuite) | `MCU_KEY_INFOR_ACTION` | `EventUtils.MCU_KEY_VALUE` → int 22 hang up, 23 answer (also 2 next, 3 prev, 4 play, 5 stop, 6 play/pause for BT music) | recv `BTService.java:1801-1836` **[confirmed]**, effect **[UNVERIFIED]** |
| *(activity)* `com.szchoiceway.btsuite/.BTMainActivity` | — | String `GotoPageNum` → `DialPage`, `CallRecordPage`, `PhoneBookPage`, `SetPage` (only when `BTTypeUtil.isDoubleBluetooth()`), `BTMusic` | `BTMainActivity.java:92-131`, `bean/DisplayPageId.java` **[confirmed]** |

> CarPlay / phone-link bridge (`com.zjinnova.zlink.*`), HiCar (`com.huawei.hicar`),
> Android Auto, DVR (`com.szchoiceway.usbdvr.*`), and 3rd-party 360 (`com.sjs.vehicleinfo.*`,
> `com.sjs.vrbackcarapp`) actions are also defined in `EventUtils.java` if you need those bridges.

---

## 2. Content providers — the `SysVar` settings store

The single most useful data store. Class `SysProviderOpt.java` is the gateway's own wrapper.

```
SysProviderOpt.java:51   CONTENT_NAME = "content://com.szchoiceway.eventcenter.SysVarProvider/SysVar"
SysProviderOpt.java:564-565  columns: "keyname", "keyvalue"
SysProviderOpt.java:710-714  update: WHERE keyname=? , values{keyvalue}
SysProviderOpt.java:717-723  insert: values{keyname, keyvalue}
```

- **Authority:** `com.szchoiceway.eventcenter.SysVarProvider`  **Table/path:** `SysVar`
- **Columns:** `keyname` (TEXT, the setting key), `keyvalue` (TEXT — everything is stored as a string;
  ints/longs are parsed by `getRecordInteger/Long`, `SysProviderOpt.java:741-755`).
- **Change notifications:** the provider notifies with a URI encoding `key=value`; observe with a
  `ContentObserver` (`SysProviderOpt.java:519-541`) — useful for a launcher to live-update.
- **Provider declaration / write permission:** **[inferred]** — the `<provider>` is **not** declared
  in the eventcenter or auxcamera manifests, so it is exported by another package (almost certainly
  `com.szchoiceway.canbus2`, whose APK is corrupt here). Reads via `query` succeed for the gateway
  without extra permission; writes (`update`/`insert`) are done by the `android.uid.system` gateway.
  Expect **reads to be open** and **writes to require system uid or root**. On this rooted device you
  can always write via a root shell or a privileged app.

### 2.1 Reading (any app)

```java
Uri uri = Uri.parse("content://com.szchoiceway.eventcenter.SysVarProvider/SysVar");
try (Cursor c = getContentResolver().query(uri, null, "keyname=?",
        new String[]{ "Sys_CarType" }, null)) {
    if (c != null && c.moveToFirst())
        String val = c.getString(c.getColumnIndex("keyvalue"));
}
// or read the whole table (query(uri,null,null,null,null)) and build a map — SysProviderOpt.java:561-566
```

### 2.2 Writing (system app / root)

```java
ContentValues v = new ContentValues();
v.put("keyvalue", "3");
getContentResolver().update(uri, v, "keyname=?", new String[]{ "Sys_backcar_Video_Type" });
// insert if missing: v.put("keyname",k); v.put("keyvalue",val); getContentResolver().insert(uri,v);
```
From a root shell: `content update --uri content://com.szchoiceway.eventcenter.SysVarProvider/SysVar --bind keyvalue:s:3 --where "keyname='Sys_backcar_Video_Type'"`

### 2.3 Useful setting keys (all `public static final String` in `SysProviderOpt.java`)

Values are device-specific enums; ranges below are **[inferred]** from naming unless stated.

| Key constant | keyname string | Meaning |
|---|---|---|
| `SYS_CAR_TYPE_KEY` | `Sys_CarType` | Model index within `Sys_Vehicle_deries` (Toyota: Camry 1, RAV4 2, Corolla 5, Highlander 7, C-HR 10; `canbus2 CanConstantInfo.java:497-543`). No RHD flag: steering side lives only in the CAN box console map |
| `SYS_CAR_VEHICLE_DERIES_KEY` | `Sys_Vehicle_deries` | Make: 0 none, 1 Toyota, 2 Ford, 7 Honda, 8 VW (`CanConstantInfo.java:602-650`) |
| `SYS_CARINFOR_ID` | `Sys_CarInfor_ID` | Year/trim index inside the model (`YearType.java:1017-1034`) |
| `SYS_CAMRY_AIR_SUPPLIER_KEY` | `Sys_camry_air_Supplier_id` | CAN box vendor 1..18 (4 Raise, 6 Hiworld) |
| `SYS_CUSTOMER_TYPE_KEY` | `Sys_CustomerType` | OEM/customer id, default 88. 53 = OEM build with gateway status bar + USB multi-camera, 58 = original-car amplifier relay, 13 = instrument-panel animation (`EventService.java:332,7108,8843,14247`) |
| `SYS_UI_NUMBER_KEY` | `Sys_UINumber` | Skin id, 0 = common (`SysProviderOpt.java:458`; NOT `uiNumberKey`, that is the helper method) |
| `SYS_BACKCAR_TYPE` | `SYS_BACKCAR_TYPE` | Reverse camera type |
| `SYS_BACKCAR_VIDEO_TYPE` | `Sys_backcar_Video_Type` | Reverse **video input** type (CVBS/AHD/…) |
| `SYS_BACKCAR_6752_VIDEO_TYPE` | `Sys_6752_Backcar_Video_Type` | Reverse video type for TW6752 decoder |
| `SYS_BACKCAR_CAMERA_MIRRORING` | `Sys_Backcar_Camera_Mirroring` | Mirror reverse image |
| `SYS_BACKCAR_FULLSCREEN` | `Sys_backcar_fullscreen` | Reverse full-screen |
| `SYS_BACKCAR_WINDOW_TYPE` | `Sys_Backcar_Window_Type` | Reverse window layout |
| `SYS_BACKCAR_SPEED_THRESHOLD` | `Sys_Backcar_speed_threshold` | 0/1/2 → 0/30/50 km/h auto-exit (`EventService.java:9003-9010`) |
| `SYS_BACKCAR_DISPLAY_RADAR_KEY` | `Sys_BackCar_Display_Radar_Key` | Show radar overlay in reverse |
| `SYS_REVERSE_ASSIST_LINE_KEY` | `Sys_Reverse_Assist_Line_Key` | Static guide lines on/off |
| `SYS_TRACK_LINE_KEY` | `Sys_TrackLineType` | Dynamic trajectory line type |
| `SYS_RADAR_TYPE_KEY` / `SYS_RADAR_TONE_KEY` / `SYS_RADAR_TONE_TYPE` | `Sys_RadarTypeEnable` / `Sys_RadarToneEnable` / `Sys_RadarToneType` | Parking radar type + beep |
| `SYS_MCU_PANEL_LIGHT_KEY` | `Sys_MCU_Panel_Light_Key` | Panel button illumination |
| `SYS_MCU_SOFT_LIGHT_CONTROL_SET` | `Sys_Mcu_soft_light_control_Set` | Soft (CAN) illumination control |
| `SYS_LIGHT_LEVEL_SET` | `Sys_Light_Level_set` | 0..3 dim level forwarded to SystemUI (`EventService.java:13882-13898`), NOT the backlight |
| `SET_DAY_LIGHT_KEY` / `SET_NIGHT_LIGHT_KEY` | `Set_Day_Light` / `Set_Night_Light` | MCU backlight 0..20, defaults 18 / 8. Reaches the MCU only via `sendBacklight(day, night)` (frame `2E day night 80 200 00`); a provider write alone only broadcasts (`EventService.java:4847-4854,9643-9648`) |
| `SYS_DAY_NIGHT_MODE` | `Sys_Day_Night_Mode` | 0 follow headlamps, 1 day, 2 night, 3 by sunrise/sunset; default 3 (`ItemTextRightCheckBoxView.java:503-525`, `EventService.java:6621`) |
| `SYS_CAR_AMBIENT_LIGHT_KEY` / `SYS_MULTICOLOR_KEY_LIGHT` | `sys_car_ambient_light_key` / `sys_multicolor_key_light` | Ambient / multicolour key light |
| `SYS_AIR_PANNEL_TYPE_KEY` | `Sys_Air_Pannel_type` | A/C panel protocol type |
| `SYS_AIR_CONDITIONING_BAUD_RATE` | `Sys_Air_conditioning_baud_rate` | A/C board serial baud |
| `SYS_REAR_AIR` / `SYS_BAR_AIR_SHOW_SET` | `Sys_rear_air` / `Sys_BarAirShow_Set` | Rear A/C present / show A/C bar |
| `SYS_CAR_SPEED_UNIT` | `Sys_Car_Speed_Unit` | 0=km/h 1=mph (`EventService.java:5001-5007`) |
| `SET_SHOW_CAR_SPEED_KEY` | `Set_ShowCarSpeed` | Show speed overlay |
| `SET_ACC_ON_DELAY` | `SET_ACC_ON_DELAY` | Seconds 0..7, packed `& 7` into MCU frame 0x10 (`ItemTextRightCheckBoxView.java:450-486`, `EventService.java:9976`) |
| `SYS_ACC_DELAY` | `Sys_Acc_Delay` | Seconds, sent as MCU `49 17 mm ss` (`EventService.java:3169-3172`); no vendor UI writes it |
| `ACC_OFF_DELAY` | `ACC_OFF_DELAY` | Never read; a change only re-sends the factory MCU set |
| `SYS_POWER_OFF_DELAY` | `Sys_Power_Off_Delay` | 0/1 factory "ACC off delay", bit1 of factory MCU byte 8 (`EventService.java:10186`) |
| `SYS_SLEEP_SWITCH` | `Sys_Sleep_Switch` | 0/1 factory flag, bit4 of factory MCU byte 10 (`:10228`) |
| `SYS_SLEEP_TIME` | `SYS_SLEEP_TIME` | Enum 1/2/3 -> MCU 960/1440/2880 (else 480), default 2; unit of the MCU value UNVERIFIED (`EventService.java:9361-9371,6540`) |
| `SYS_SCREEN_OFF_WHEN_ACC_CHANGE` | `Sys_Screen_Off_When_Acc_Change` | 0/1, default 1 (`:10033`) |
| `SYS_AUTO_START_SCREENSAVER_TIME` / `SYS_AUTO_START_CLOSE_SCREEN_TIME` | same | Seconds in {0, 60, 300, 600, 1800}; close-screen acts only on customer 69 (`ItemTextRightCheckBoxView.java:644-692`, `EventService.java:14380-14394`) |
| `SYS_CUSTOMER_NAVIBAR_HEIGHT_KEY` / `SYS_LANDSCAPE_KEY` | `Sys_Customer_NaviBar_Height_Key` / `Sys_Landscape` | Bottom bar height px, 0 = no bar (factory 170/212/220/270), honoured only with `Sys_Landscape` = 1; re-applied live on `changeSetup` (`utils/SystemUtils.java:90-132`, `EventService.java:5125,5172`). No key hides the status bar |
| `SYS_MCU_VERSION` / `SYS_UPGRADE_CANBOX_VERSION` | `Sys_McuVersion` / `Sys_Upgrade_Canbox_Version` | MCU / CANBOX firmware ver |
| `SYS_CAN_BAUD_RATE` / `SYS_MCU_COM_BAUDRATE_KEY` | `Sys_Can_baud_rate` / `Sys_MCUComBaudRate` | CAN / MCU UART baud |
| Radio favorites | `Rdo_MyFavorite0..5` (`RDO_MyFavorite0_KEY…`) | Stored radio presets |
| Launcher/home | `Sys_Home_Page_Display`, `SYS_LAUNCHER_APP_HIDE_KEY`, `Sys_Function_Icon_Config_Key`, `Sys_Statusbar_Icon_Config_Key` | Home page + status/nav bar config (see §6) |
| Screen | `Sys_Screen_Width`/`Sys_Screen_Height`/`Sys_Screen_Density`/`Sys_Landscape` | Panel geometry (1920x720) |

(The class defines ~380 keys — audio EQ `Set_Snd_Freq*`, DSP, 360-camera, gyro, HDMI, dual-screen,
customized-app package/class slots, etc. Grep `SysProviderOpt.java` for the full list.)

---

### 2.4 btsuite call history — `CallListProvider`

- **Authority:** `com.szchoiceway.btsuite.CallListProvider`, path `query` only
  (`CallListProvider.java:33-35,49-55`). Exported, **no `android:permission`**
  (`btsuite/AndroidManifest.xml:89-93`) **[confirmed]**; a read from a normal uid is
  **[UNVERIFIED]** on the car.
- **Selector:** `projection[0]` = call type as a decimal string: 2 received, 3 dialed, 4 missed,
  5 all (`CallRecManager.java:142-161`, tab buttons `CallHistoryUIControllerLandscape.java:271-276`,
  icons `cklh/adapter/ListAdapter.java:49-54`). Newest first, 50 rows per type / 150 for all.
  Selection, args and sort order are ignored; a null or empty projection returns null.
- **Columns** (`CallRecManager.java:261`): `_id`, `name`, `num`, `date` (`%d-%02d-%02d`),
  `time` (`%02d:%02d:%02d`), `calltype`, `timeDetail` (sort key).
- Read-only: `insert` / `update` / `delete` are no-ops. Launcher wrapper: `carlib/VendorCallLog.kt`.

## 3. Services / AIDL / bound interfaces

### 3.1 `EventService` (the control service)

- **Package:** `com.szchoiceway.eventcenter`
- **Component:** `com.szchoiceway.eventcenter.EventService`, `android:exported="true"`,
  intent-filter action **`com.szchoiceway.eventcenter.EventService`** (manifest lines 77-83).
- **Bind interface:** AIDL `IEventService`,
  descriptor **`com.szchoiceway.eventcenter.IEventService`** (`IEventService.java:960`).
- **Returned from:** `EventService.onBind()` (`EventService.java:1662`), impl
  `ServiceStub extends IEventService.Stub` (`EventService.java:11786`).
- **Callback interface:** `ICommunication` (descriptor `com.szchoiceway.eventcenter.ICommunication`,
  `ICommunication.java:33`) with `notifyMessage(String)` / `checkIsActive()`; register via
  `addMessageListener(ICommunication)`.
- **Access:** because the service is `exported=true`, any app can `bindService` to it. But it runs as
  `android.uid.system`; **[inferred]** control ops that reach into secure settings / the MCU may
  enforce a caller check or simply require the caller to also be system for side effects. Treat
  read-only methods as callable from a normal app and control methods as "works best as a system app".

To bind:
```java
Intent i = new Intent("com.szchoiceway.eventcenter.EventService");
i.setPackage("com.szchoiceway.eventcenter");
bindService(i, conn, BIND_AUTO_CREATE);
// in onServiceConnected: IEventService svc = IEventService.Stub.asInterface(binder);
```

### 3.2 Selected `IEventService` methods (full list: `IEventService.java:15-666`)

| Category | Methods |
|---|---|
| **Mode / source** | `sendMode(int,boolean)`, `getValidMode()`, `exitCurMode(int)`, `postRunModeActivity(int)`, `notifyModeKeyEvt(int)`, `setCurModeCallback(int,ICallbackfn)` |
| **Radio** | `sendRadioKey(int)`, `getRadioFreq()`, `getRadioFreqList()`, `getRadioBand()`, `getRadioNum()`, `sendUserFreq(int,boolean)`, RDS/TA/PTY/AF getters, `setRadioCallback(ICallbackfn)` |
| **Audio / EQ / volume** | `sendEQMode(int)`, `getEQMode()`, `SetSndFreq(byte,byte)`, `sendSndFreqArray(byte[])`, `sendBalFadValue(int,int)`, `getBALFADValue()`, `sendMuteState(boolean)`, `IsMuteOn()`, `sendVolState(boolean,int)`, `getMainVolval()`, `sendSndSWVol(int)`, `getLoudness()`, `beep()` |
| **Keys** | `sendSystemKey(int)`, `sendWheelKey(int)`, `sendTVKey(int)`, `sendDVRKey(byte)`, `sendTVTouchBtnKey(int)`, `sendTouchPos(int,int,boolean)` |
| **Climate / CAN** | `getAirData(int,byte[])`, `sendCanbusData(byte[])`, `SetCanVer(String)`, `getCanVer()`, `setCanA5DataCallback/A6DataCallback(ICallbackfn)` |
| **Car state getters** | `IsBackCarConneted()`, `IsBrakeConneted()`, `IsHDMIConnected()`, `IsUSBConnected()`, `getMacanSignalState()`, `getRightSighData()`, `getGyroData()` |
| **Settings passthrough** | `getSettingInt/Long/Float/Boolean/String(...)`, `putSettingInt/Long/Float/Boolean/Str(...)`, `commitSetting()`, `appySetting()`, `changeSetup(String,String)` — a typed front-end to the SysVar store |
| **Media metadata** | `getValidMode*Infor()`, `getValidPlayState()`, `getValidCurTrack/Time/Folder()`, `setValidModeAllInfor(...)` |
| **Power / system** | `sendSoftWareReboot()`, `sendSystemReset()`, `setSystemBrightness()`, `openTVout(int,boolean)`, camera/upgrade APIs |
| **Listener** | `addMessageListener(ICommunication)` |

**`sendRadioKey(int)` values** (from the vendor radio app's key handlers; the gateway sends
the int untouched as MCU frame `{0x02, key}`): 1–6 recall preset N, 7–12 store preset N,
13 preset scan, 14/15 step down/up, 16/17 seek down/up, 18 auto-store (AMS), 19 stereo/mono,
20 DX/LOC, 30 band FM, 31 band AM. `sendUserFreq(int freq, boolean fm)` → `{0x0C, hi, lo,
fm ? 0 : 1}`, freq in the units `getRadioFreq()` reports. Claiming tuner audio is
`setCurModeCallback(1, cb)` + `setRadioCallback(cb)` + `sendMode(1, wait)` (`eSrcMode.SRC_RADIO`
= 1); the boolean means "wait for the MCU's ACK, frame 0x70" (`EventService.java:3933-3958`), so
one call with `true` replaces the vendor's two with `false`. `sendMode` calls `kill3rdAPK()`, but
that is gated by SysVar `Sys_SoundManager_Type`, which **defaults to "1" = no kill**
(`:6581,6750,8294`); only a unit set to 0 force-stops non-system foreground tasks.
Also 21 AF toggle, 22 PTY seek (after `sendSetup(3, ptyIndex)`), 23 TA toggle, 24 band
cycle, 25 next, 26 previous. Units: `getRadioFreq()` is FM in 10 kHz units (9630 = 96.30 MHz),
AM in kHz. `getRadioBand()`: 0..2 = FM1..FM3, 3..6 = AM. `getRadioPTYName()` returns the **RDS
PS station name** (the gateway's `mRadioPSName`); the genre is `getRadioPTYNum()`. The radio
callback delivers `notifyEvt(what, …)` with what = 0 status bits (arg2 packed: RDS 1, PTY 2,
AF 4, TA 8, ST 16, LOC 32, AMS 64, APS 128), 1 band, 2 tune slot, 3 freq (arg2), 5 PTY num,
6 PS name (`str`); the mode callback delivers 4097 mode change (arg2 = new mode) and 4098 MCU
key (arg2). `ZXW_RADIO_INFO_EVT` (`com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT`)
carries int extras `RadioBndNum`, `RadioTuneNum`, `RadioCurFreq`. `Rdo_MyFavorite0..5` hold
the decimal string of `freq | (am ? 0x10000 : 0)` (0 = empty).


### 3.3 Secondary channel: LocalSocket

**Dead code.** `LocalSocketServer` (`com.szchoiceway.LocalServerSocket`) is never instantiated,
and the `addMessageListener(ICommunication)` listeners are stored but never called. The text lines
`SocketUtils.java` formats (`CURRENT_MODE_INFO:`, `SYSTEM_VOLUME:<mute>,<vol>`, `CAR_ACC_STATUS:`,
`BACK_LIGHT_LEVEL:`, …) ride the `com.szchoiceway.eventcenter.ZXW_MESSAGE_TO_ICCOMMUNICATION`
broadcast as String extra `zxw_MessageToListener` (`EventService.java:12917-12925`). **[confirmed]**

---

## 4. Steering-wheel (SWC) key events

Three coexisting paths — a custom launcher should listen to **all three** to be safe:

1. **Dedicated broadcast (recommended)** — gateway decodes MCU wheel frame `onCmdWheelEvent`
   (`EventService.java:2846-2857`) and broadcasts
   `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR` with:
   - `EventUtils.STEER_WHEEL_INFOR_LPARAM` (int) = key index `bArr[1]+1`
   - `EventUtils.STEER_WHEEL_INFOR_WPARAM` (int) = `4` when released, `3` when pressed
   - `EventUtils.STEER_WHEEL_INFOR_VOLTAGE` (int) = raw resistive-key ADC
   Sent **with** `PERMISSION_CHOICEWAY_BROADCAST`.

2. **MCU key broadcast** — `MCU_KEY_INFOR` (`MCU_KEY_VALUE` int `MCU_KEY_*` code) from
   `sendKeyEventBroadcast` (`EventUtils.java:2147`): every key the MCU reports, panel and learned
   SWC alike, one broadcast per press. `ACTION_HOST_MCU_BUTTON_KEY` is **not** a key path (§1.3).

3. **Injected Android KeyEvents** — for standard media keys the gateway calls `sendKeyDownUpSync(...)`
   (e.g. power=26 at `EventService.java:3428`), so ordinary `onKeyDown` handling in your Activity
   catches some wheel keys too.

**RAV4 (Hiworld TYF2) wheel keys are CAN, not resistive.** They ride frame `0x11` (Basic Status,
`MCU_MSG_CAN_ALL_INFO`): `bArr[4]` = key id, `bArr[5]` = 1 on every frame while held, 0 on release
(`HiworldCanParseToyota.java:831-891`, `OnHandleCanKeyCmd`; launcher payload offsets p[2]/p[3]).
Ids: 1 VOL+, 2 VOL−, 3 MUTE, 4 VOICE, 5 TALK (HANGUP during a call), 6 HANGUP, 8 and 13 PREV,
9 and 14 NEXT, 12 MODE, 15 PLAY/PAUSE, 16 RETURN. The CAN app emits the MCU key **once, on the
release frame** (`:853-885` → `sendMCUKey`, `:888`); VOL± instead repeat once per frame after 5
held frames (`:838-848`). `sendMCUKey` is `ZXW_CAN_KEY_EVT` (`CanDataParseBase.java:1078,
1518-1536`) → `EvtModel.java:492-511` → `EventService.ProcessCanKey` (`:13021-13110`): 2/3/6 →
injected KeyEvent 87/88/85, 85 → KeyEvent 4, 16 → `switchMode()`, 17 → `sendSystemKey(12)`,
116 → `startVoice()`, 23 → CarPlay/BT. Hold duration never leaves the CAN app; the launcher
decodes it from the frames itself (`carlib/WheelGestures.kt`). Frame period **UNVERIFIED**.

**SWC/panel keycode constants** (`EventUtils.java`), the values you receive/emit:

`CAR_KEY_*` (`:1000-1014`): `POWER=1, HOME=2, FAV=3, PREV=4, NEXT=5, MENU=6, PHONE=7, MEDIA=8,
RADIO=9, BACK=10, L_TUNE_L=11, L_TUNE_R=12, R_TUNE_L=13, R_TUNE_R=14`.
`MCU_KEY_SYS_*` (`:1617-1620`): `HOME=76, MENU=77, ESC=78, WINCE=79`.
There are also Porsche/vendor variants (`CAR_PORSCHE_KEY_*`, `KSP_PORSCHE_KEY_*`).

**Learning/mapping.** `STEER_WHEEL_INFOR_LPARAM` is a learned **slot + 1**, not a function.
The vendor learn app (`com.szchoiceway.learn.key`, `view/CarWheelView.java`) drives the MCU with
`IEventService.sendWheelKey(n)` (frame `{0x07, n}`): `112` enter learn mode, `<slot>` (lowest
free 0..14) learn the next press into that slot, `114` save, `113` exit, `115` clear all,
`116`/`117` high/low-impedance wheel (also bit 2 of `Sys_McuSet`). The MCU answers with
`STEER_WHEEL_INFOR` (LPARAM≠0 read as success) and `STEER_WHEEL_STATUS`
(`EventUtils.STEER_WHEEL_STUDY_STATUS` = 16-bit mask of learned slots). The result is persisted
as a **JSON object** in SysVar `wheel_key_learn_custom` (`SYS_WHEEL_INDEX_CUSTOM_KEY`):
`{"<icon id>":"<slot>"}`, e.g. `{"svg_wheel_next_c":"1","svg_wheel_mode_home":"0"}` (panel keys:
`mcu_panel_key_learn_custom`). Icon ids = fields of `base/WheelCustomKey.java`, meanings from
`manager/McuToArmDataManage.java:441-520`: `svg_wheel_mode_c` Mode, `next_c` Next, `pre_c` Prev,
`gj` Power, `dh` Navi, `jy` Mute, `gd` Hangup, `jt` Talk, `s_add` VolAdd, `s_j` VolSub,
`mode_voice`, `mode_360` Camera, `mode_fm`, `mode_back`, `mode_home`, `mode_ok`, `mode_video`,
`mode_music`, `mode_backlight_brightness`, `mode_original_car_info`, `mode_audio_dsp`,
`mode_settings`, `mode_car_android`, `mode_fenping` SplitScreen, `mode_houtai` RecentTask,
`mode_aux`, `mode_ams`, `mode_aps`, `mode_loud`, `mode_chewaijiankong`. The gateway itself
does `Gson.fromJson` on this key at startup: a scalar there crash-loops it (launcher v2.4.2
incident). The launcher parses it read-only (`carlib/WheelKeyMap.kt`). That the MCU's `bArr[1]`
equals the taught slot is inferred, **UNVERIFIED** on the car.

Register example:
```java
IntentFilter f = new IntentFilter("com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR");
registerReceiver(swcReceiver, f, "com.szchoiceway.permission.broadcast", null);
// in onReceive: int idx = i.getIntExtra("EventUtils.STEER_WHEEL_INFOR_LPARAM",0);
//              int st  = i.getIntExtra("EventUtils.STEER_WHEEL_INFOR_WPARAM",0); // 3=down,4=up
```

---

## 5. MCU protocol (raw serial — fallback only)

Prefer the broadcast/provider/AIDL API. Direct serial should only be used if you replace or bypass
the gateway. Confirmed facts from code:

- **Device / speed:** `new Device("/dev/ttyHS1", "115200")` (`EventService.java:1685`);
  `UART_DEV_SPEED = 115200` (`EventUtils.java:1837`). `android.permission.SERIAL_PORT` held.
- **Frame parsing entry points:** the gateway dispatches decoded commands through
  `onCmd*` handlers (`EventService.java:1890-3070`): `onCmdSysEvent`, `onCmdKeyEvent`,
  `onCmdCarAirEvent`, `onCmdCanEvent`, `onCmdMcuIRRadarData`, `onCmdWheelEvent`, `onCmdRadioEvent`,
  `onCmdMcuSleepState`, `onCmdMcuHdmiResolutionData`, etc.
- **Sys/reverse status byte (the "0x71" system event)** — `onCmdSysEvent`
  (`EventService.java:2290-2397`), decode of `bArr[1]` (byte1) and `bArr[2]` (byte2):

  | Bit | Mask | byte1 meaning | byte2 meaning |
  |---|---|---|---|
  | 0 | 0x01 | **ACC status** on/off | Left turn signal |
  | 1 | 0x02 | **Reverse / backcar** engaged | — |
  | 2 | 0x04 | Brake connected | — |
  | 3 | 0x08 | **Lamp / illumination** (small light) → day/night | HDMI present |
  | 4 | 0x10 | Right turn signal | — |
  | 6 | 0x40 | USB-in-DVD | Start/Stop state |
  | 7 | 0x80 | (DVD disc via `CMD_FREQ_SEL`) | Macan signal state |

  So "reverse start" = byte1 bit `0x02` set while ACC on (`EventService.java:2354`); the gateway then
  fires `startBackcar()` → `ACTION_BACKCAR_START`.
- **Air-conditioning key opcodes** (`EventUtils.java:955-994`, `CAR_AIR_KEY_*`, one byte each). Selected:
  `POWER=0, FAN_ADD=1, FAN_SUB=2, L_TEMP_ADD=3, L_TEMP_SUB=4, R_TEMP_ADD=5, R_TEMP_SUB=6, AUTO=7,
  AC=8, AC_MAX=9, DUAL=10, REAR=11, INNER_LOOP=12, OUT_LOOP=13, AQS=14, FRONT_DEFROST=15,
  REAR_DEFROST=16, L_SEAT_COLD=17, L_SEAT_HOT=18, R_SEAT_COLD=19, R_SEAT_HOT=20, MODE=21,
  FAN_UP=22, FAN_MID=23, FAN_DOWN=24, …, ESP=35, DOOR_LOCK=36, WHEEL_HOT=37, NONE=-1`.
- **Climate state struct** the CAN app builds and broadcasts: `com.szchoiceway.canbus.CarAirState`
  (Parcelable, canbus2 `CanDataParseBase.java:473-486`). Of its ~170 fields only 36 are parcelled
  (`canbus/CarAirState.java` `writeToParcel`): `bAirOn, bAcOn, bOutCircleOn, bBigAutoOn,
  bSmallAutoOn, bDualOn, bMaxFrontOn, bRearOn, bFunDirectHead/Level/Foot, bAcMax, byFunStrength,
  m_byLeftTemp, m_byRighTemp` (Strings: "22.5℃"/"LO"/"HI"), `m_byTempUnit, bRearLock, …,
  byLeftColdLevel, byRightColdLevel, bLeftSeatHotLevel, bRightSeatHotLevel, byMaxFunStrengthStall…`.
  `bECOOn`, `bRearAirOn`, `bWheelHeat`, `m_OutSideTemp` are NOT parcelled. The launcher's carlib
  ships a same-name mirror so the extra unparcels. `getAirData()` is a stub returning null
  (`EventService.java:1369-1371`). HVAC buttons: broadcast `CAR_AIR_KEY_KEY` with int extra
  `car_key_value` = `CanUtils.CAR_AIR_KEY_*` (`CarAirClickWithVoice.java:432,462`) **[unverified on car]**.
- **5AA5 (Raise/CANBOX) framing & inner `0x0D 0x0A|len|payload|cksum`:** the concrete byte framer was
  not isolated in the files reviewed (parsing is spread across native/serial glue and the CAN-box
  apps). The task's own summary of those frames stands; use the `onCmd*` handler set above as the
  authoritative list of decoded message types. **[inferred / partially confirmed]**

---

## 6. What a custom LAUNCHER specifically needs

### 6.1 Registering as HOME
Standard Android — your launcher Activity needs:
```xml
<intent-filter>
  <action android:name="android.intent.action.MAIN"/>
  <category android:name="android.intent.category.HOME"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.LAUNCHER"/>
</intent-filter>
```
The stock launcher package is **`com.szchoiceway.customerui`** (`EventUtils.APPLIST_MODE_PACKAGE_NAME`,
`EventUtils.java:144`; app-list activity `com.szchoiceway.activity.AppLauncherListActivity`).
A secondary "car console" home is `com.android.atslcarconsole` (`CARCONSOLE_PACKAGE_NAME`,
`EventUtils.java:206`). **[confirmed]** (Full customerui source is not in this dump; its manifest is
not available, so the exact stock HOME filter is **[inferred]** standard.)

### 6.2 Launcher ↔ gateway UI mode
Not a handshake: the gateway delegates its day/night decision to the launcher. Two broadcasts
(`EventUtils.java:66,76`), both carrying ONE int extra `Extra_Day_Night_UiMode` (`:1235`) holding a
`Sys_Day_Night_Mode` value — 1 day, 2 night, 3 by sunrise/sunset, anything else (0) = follow headlamps:
- launcher → gateway: `com.szchoiceway.eventcenter.EventUtils.ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT`
  → `setDayNightMode(int)` (`EvtModel.java:1076`, `EventService.java:14043-14087`). A missing or
  non-int extra reads as 0 and switches the gateway to headlamp mode.
- gateway → launcher: `com.szchoiceway.eventcenter.EventUtils.ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT`
  is a *request* to echo the same int back; after 2 s without a reply the gateway applies it itself
  (`EventService.java:14856-14862`). Sent only on a `Sys_Day_Night_Mode` or headlamp change, so it
  is not a liveness signal.

A custom launcher should send `ACTION_LAUNCHER_KEY_CTRL` for control actions and theme itself from
`LAMP_STATUS` + `Sys_LAMP_STAUS_CHECK` (headlamps) or `uiModeNightChanged` (§1.3).

### 6.3 The car widgets a launcher shows, and where the data comes from

| Widget | Data source | Confirmed API |
|---|---|---|
| **Media / now-playing** | `ZXW_MUSIC_PLAY_SONG/ARTIST/ALBUM/PLAYFILE_EVT` broadcasts, or AIDL `getValidMode*Infor()` / `getValidPlayState()` | §1.3, §3.2 |
| **Radio** | `ZXW_RADIO_INFO_EVT` / `com.szchoiceway.radio.frequency`, or AIDL `getRadioFreq/Band/Num()`, control via `sendRadioKey/sendUserFreq` | §1.3, §3.2 |
| **Climate / A/C** | `com.choiceway.canbus.carairstruct` → Parcelable `CarAirState` (AIDL `getAirData` is a null stub) | §1.3, §5 |
| **Reverse / radar** | `ACTION_BACKCAR_START/END` + `MCU_CAR_CAN_RADAR_INFO` (byte[]) + `ZXW_CAN_WHEEL_TRACK_EVT` (angle) | §1.3 |
| **Navigation** | configured nav pkg/class in SysVar (`Set_NavPackageName`/`Set_NavClassName`), nav-sound broadcasts (`ACTION_NAVI_START/STOP_PLAY_SOUND`) | SysVar + consts |
| **Day/night theming** | `LAMP_STATUS` + SysVar `Sys_LAMP_STAUS_CHECK`, `uiModeNightChanged` (`mode`), `Sys_Day_Night_Mode` | §1.3, §2.3, §6.2 |
| **Status/nav bar config** | SysVar `Sys_Statusbar_Icon_Config_Key`, `Sys_Function_Icon_Config_Key`, `Sys_customer_statusbar`, `SYS_SHOW_TOOL_NAVI_BAR_WND` | §2.3 |
| **App list / hidden apps** | SysVar `SYS_LAUNCHER_APP_HIDE_KEY`, customized-app slots `SET_CustomizedPackageName_KEY0..6` | §2.3 |

Default home icon set (`EventUtils.DEFAULT_ICON_CONFIG`): `ICON_NAVI, ICON_RADIO, ICON_MUSIC,
ICON_AIR, ICON_CONSOLE, ICON_PIC, ICON_BT, ICON_MOVIE, ICON_CARPLAY, ICON_DVD, ICON_CUSTOMIZE,
ICON_EXPLORER, ICON_PHONE_APP, ICON_SET, ICON_APPLIST, ICON_DVR, ICON_360_CAM, ICON_FILE_MANAGER,
ICON_MORESETTING`. **[confirmed]**

### 6.4 Normal app vs privileged/system app

| Capability | Normal 3rd-party launcher | Notes |
|---|---|---|
| Be HOME, show icons/widgets | ✅ | standard |
| Receive **unprotected** car events (ACC, speed toggle, music, radio, GPS, day/night backlight is protected) | ✅ | |
| Receive **protected** events (`ACTION_BACKCAR_START/END`, `STEER_WHEEL_INFOR`, day/night backlight) | ⚠️ only if it can hold `com.szchoiceway.permission.broadcast` | if that perm is `signature`, **needs platform signature** → system app |
| **Read** SysVar provider | ✅ (expected) | |
| **Write** SysVar provider | ❌ as normal app | needs system uid or root/`content` shell |
| Bind `EventService`, call read-only AIDL | ✅ (exported) | |
| AIDL control side-effects (mode switch, MCU, secure settings) | ⚠️ | best as system app **[inferred]** |
| `sharedUserId=android.uid.system`, `WRITE_SECURE_SETTINGS`, inject events | ❌ | requires install to `/system/priv-app` + platform key (doable, device is rooted) |

**Recommendation:** build the launcher/apps as a **normal app** first (HOME + provider reads + AIDL
reads + unprotected broadcasts get you most of a usable UI). For reverse/SWC/climate that ride the
protected permission, and for writing settings, ship it as a **privileged system app** (platform-sign
+ push to `/system/priv-app`, whitelist the perms) — the rooted device makes this practical.

---

## 7. Component & identifier quick reference

| Thing | Value |
|---|---|
| Gateway package | `com.szchoiceway.eventcenter` (`sharedUserId=android.uid.system`, manifest:3) |
| Bound service | `com.szchoiceway.eventcenter.EventService`, action `com.szchoiceway.eventcenter.EventService`, exported (manifest:77-83) |
| AIDL | `com.szchoiceway.eventcenter.IEventService` / callback `...ICommunication` |
| Boot receiver | `com.szchoiceway.eventcenter.AutoStart` (BOOT_COMPLETED / CONNECTIVITY_CHANGE, manifest:69-76) |
| Reverse activity | `com.szchoiceway.view.BackCarActivity` (exported, manifest:63-65) |
| Broadcast permission | `com.szchoiceway.permission.broadcast` |
| SysVar provider | `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` (cols `keyname`,`keyvalue`) |
| Serial | `/dev/ttyHS1` @ 115200 |
| Stock launcher | `com.szchoiceway.customerui` (+ `com.android.atslcarconsole`) |
| Aux/reverse app | `com.szchoiceway.auxcamera` (`android.uid.system`) |
| CAN parser app | `com.szchoiceway.canbus2` (source unavailable — APK corrupt) |
| AVM/360 app | `com.ivicar.avm` |

Constant strings are defined in `EventUtils.java` (actions/keycodes/air-keys) and
`SysProviderOpt.java` (SysVar keys); the enum of "modes" is `EventUtils.eSrcMode`
(`SRC_RADIO, SRC_MUSIC, SRC_CARAIR, SRC_BACKCAR, SRC_HOME=43, SRC_CARPLAY, …`, `EventUtils.java:~2034`).
