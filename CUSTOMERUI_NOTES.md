# com.szchoiceway.customerui — Stock Launcher Notes (REFERENCE)

Reference for building our own Kotlin/Compose replacement launcher (`/home/sasha/projects/device-reveng/launcher`).
Cross-reference: `/home/sasha/projects/device-reveng/CAR_API.md` (the car-integration API).

> **STATUS — device UNREACHABLE, APK NOT PULLED.**
> `adb connect <ip>:<port>` timed out (Connection timed out, exit 124; `adb devices`
> empty on two attempts, 2026-08-27). The head unit is offline, so **customerui.apk could not
> be pulled or decompiled**. No prior copy of the APK or its decompiled source exists anywhere
> under `/home/sasha/projects/device-reveng` (searched).
>
> **Everything below is therefore [inferred]** — reconstructed from the *gateway* app
> `com.szchoiceway.eventcenter` (decompiled at `mcu-analysis/eventcenter-src/sources/`), which
> names, launches, and reflectively reaches into customerui, plus `CAR_API.md`. Citations of the
> form `file:line` are **into the eventcenter source**, NOT into customerui (which we do not have).
> When the device is back, re-run the pull/jadx steps to confirm the [inferred] items marked below.
>
> To finish the job later:
> ```
> adb connect <ip>:<port>
> adb -s <ip>:<port> shell pm path com.szchoiceway.customerui
> adb -s <ip>:<port> pull <path> mcu-analysis/apks/customerui.apk
> mcu-analysis/jadx/bin/jadx -d mcu-analysis/customerui-src --no-res -q mcu-analysis/apks/customerui.apk
> # + a resource pass (unzip the apk, or jadx without --no-res) for the layouts named in §3/§4
> ```

---

## 1. Identity & components

| Thing | Value | Evidence (eventcenter) |
|---|---|---|
| Package | `com.szchoiceway.customerui` | `EventUtils.java:144` (`APPLIST_MODE_PACKAGE_NAME`) |
| **Home/main activity** | `com.szchoiceway.customerui.MainActivity` | `DualScreenDisplayManage.java:402,419`; `HDMIManage.java:439,456`; `HDMIOutUtils.java:40` |
| App-drawer activity | `com.szchoiceway.activity.AppLauncherListActivity` | `EventUtils.java:143` (`APPLIST_MODE_CLASS_NAME`) |
| Music screen | `com.szchoiceway.fragment.MusicModeActivity` | `EventService.java:3197` |
| Empty/placeholder | `com.szchoiceway.customerui.EmptyActivity` | grep of decompiled apps |
| App-list adapter pkg | `com.szchoiceway.customerui.applist` | grep of decompiled apps |
| Reverse/radar views (in-launcher) | `com.szchoiceway.view.{ReverseAssistLineView, ReverseAssistLineControlView, ReverseCarTrackView, ReverseCarTrackParameterControl, RadarViewUp, RadarViewDown}` | grep |
| Wheel-learn activities | `com.szchoiceway.activity.{CarWheelActivity, McuWheelActivity}` | grep |
| Custom widgets | `com.szchoiceway.view.{CoverFlowView, CoverFlowAdapter, DayNightImageButton, DayNightImageBtnDrawable, SelectStateTextView, VerticalSeekBar, SideBarView, SideBigBarView, ColorAdjustmentView, CalibrationView}` | grep |

Note the class names live in **three** package roots that all ship inside the customerui APK:
`com.szchoiceway.customerui.*`, `com.szchoiceway.activity.*`, `com.szchoiceway.fragment.*`,
`com.szchoiceway.view.*`, `com.szchoiceway.base.*`. Our replacement only needs to re-implement
`MainActivity` (HOME) + a drawer; the `view.*` reverse/radar widgets are optional (the gateway's
own `BackCarActivity` covers reverse — see §5).

Runs as **`android.uid.system`** like the gateway (CAR_API §0, §6.4). Our normal-app launcher can
**not** share that uid; consequences in §7.

---

## 2. How it registers as HOME  [inferred — manifest not available]

customerui's manifest is not in this dump, so the exact filter is inferred as the standard set
(CAR_API §6.1). `MainActivity` is the HOME activity:

```xml
<activity android:name="com.szchoiceway.customerui.MainActivity" ...>
  <intent-filter>
    <action   android:name="android.intent.action.MAIN"/>
    <category  android:name="android.intent.category.HOME"/>
    <category  android:name="android.intent.category.DEFAULT"/>
    <category  android:name="android.intent.category.LAUNCHER"/>
  </intent-filter>
</activity>
```

Corroboration that `MainActivity` is treated as "home to return to": the gateway explicitly
re-launches `ComponentName(customerui, "com.szchoiceway.customerui.MainActivity")` whenever it needs
to bring the launcher forward — e.g. leaving dual-screen (`DualScreenDisplayManage.java:402,419`) and
HDMI transitions (`HDMIManage.java:439,456`, `HDMIOutUtils.java:40`). Mode enum has `SRC_HOME=43`
(`EventUtils.java:2034`).

For **our** launcher: replacing HOME is a normal-app operation. To actually take over, either
uninstall/disable customerui or win the HOME chooser. Both `com.szchoiceway.customerui` and
`com.android.atslcarconsole` (`EventUtils.java:206`, `CARCONSOLE_PACKAGE_NAME`) are HOME candidates
on this device.

---

## 3. Home-screen cards / widgets and their data sources

The stock home grid is a **configurable icon list**, not fixed widgets. The default order is defined
gateway-side and mirrored by the launcher:

```
Customer.java:51  DEFAULT_ICON_CONFIG =
  "ICON_NAVI,ICON_RADIO,ICON_MUSIC,ICON_AIR,ICON_CONSOLE,ICON_PIC,ICON_BT,ICON_MOVIE,
   ICON_CARPLAY,ICON_DVD,ICON_CUSTOMIZE,ICON_EXPLORER,ICON_PHONE_APP,ICON_SET,ICON_APPLIST,
   ICON_DVR,ICON_360_CAM,ICON_FILE_MANAGER,ICON_MORESETTING"
```
`Customer.java:280-306` maps each `ICON_*` tag → a label string resource (`lbl_nav`, `lbl_radio`,
`lbl_music`, `lbl_air`, `lbl_360`, `lbl_applist`, …). The **icon order is a persisted setting** the
launcher reads/writes: SysVar `Sys_Function_Icon_Config_Key` (`SysProviderOpt.java:308`), the home
page choice `Sys_Home_Page_Display` (`:323`), and hidden apps `SYS_LAUNCHER_APP_HIDE_KEY` (`:337`).

Live-data cards and where each pulls its data (all cross-referenced to CAR_API §1.3/§3.2):

| Card | Data source (broadcast / SysVar / AIDL) | CAR_API |
|---|---|---|
| **Media / now-playing** | Broadcasts `com.choiceway.musicplayer.ZXW_MUSIC_PLAY_SONG_NAME_EVT` / `_ARTIST_NAME_EVT` / `_ALBUM_NAME_EVT` / `_PLAYFILE_EVT` (String `*_EXTRA`); or AIDL `getValidModeInfor()/getValidPlayState()/getValidCurTrack/Time()`. Music screen is `MusicModeActivity` (`EventService.java:3197`). The `CoverFlowView`/`CoverFlowAdapter` classes are the album-art carousel. | §1.3, §3.2, §6.3 |
| **Radio** | Broadcasts `ZXW_RADIO_INFO_EVT` / `com.szchoiceway.radio.frequency` (`BROADCAST_RADIO_FREQUENCY_EVENT`, extra `com.szchoiceway.radio.frequency_extra`); or AIDL `getRadioFreq/Band/Num()`. Control via `sendRadioKey/sendUserFreq`. Presets in SysVar `Rdo_MyFavorite0..5`. | §1.3, §2.3, §3.2 |
| **Climate / A/C display** | Broadcast `com.szchoiceway.canbus.carairstruct` → Parcelable `com.szchoiceway.canbus.CarAirState` (extra `com.choiceway.canbus.carairstruct.airstate`); or AIDL `getAirData(int,byte[])`. Show/hide driven by `SHOW_CAR_AIR_EVT`/`HIDE_CAR_AIR_EVT`/`ACTION_SHOW_CAR_AIR_WND_EVENT` (`EventService.java:8970-8973`). A/C bar visibility from SysVar `Sys_BarAirShow_Set`. | §1.3, §5 |
| **Navigation** | Configured nav pkg/class in SysVar `Set_NavPackageName`/`Set_NavClassName`; nav-sound broadcasts `ACTION_NAVI_START/STOP_PLAY_SOUND`. `ICON_NAVI` → `lbl_nav`. | §6.3, §2.3 |
| **Clock** | Standard Android time (no car API); day/night styling per §4. | — |
| **Shortcuts / function icons** | SysVar `Sys_Function_Icon_Config_Key` (order) + `SYS_LAUNCHER_APP_HIDE_KEY` (hidden) + customized-app slots `SET_CustomizedPackageName_KEY0..6`. | §2.3, §6.3 |
| **Outside temp / trip / TPMS** (if shown) | Broadcasts `CAN_CAR_OUT_SIDE_TEMP_EVT` (int + String), `CAN_TPMS_DATA_EVT`, `CAN_CAR_TIRP_INFO`, `CAN_FUEL_CONSUMPTION_INFOR`. | §1.3 |

**Shared floating UI hosted BY customerui, inflated BY the gateway** — important architectural note.
The status bar and side windows are *not* drawn by the gateway from its own resources; the gateway
opens a remote package context on customerui and inflates **customerui's** layouts/ids by name:

- Side window: `createPackageContext("com.szchoiceway.customerui")` → inflate layout
  `layout_left_side_window_view_zxw` (`SideWindow.java:98-103`; base helper `BaseWindow.java:162-167`).
- Custom status bar buttons (ids resolved in customerui's `R.id`, `CustomStatusbar.java:57-72`):
  `ibtscreenshot, btn_home (SocketUtils.VIEW_BUTTON_HOME), btnShowApp, btnUp, btnDown, btnTask,
  btnWifiStatus, btnAirplanemode, btnBTStatus, btnCast, btnHotspot, btnBlackScreen, btnDataroaming`.
  A long-press on the "task" button broadcasts `ACTION_SPLITSCREEN` (`CustomStatusbar.java`).

If we replace customerui we must either (a) keep those exact layout/id names so the gateway's
`SideWindow`/`CustomStatusbar` keep working, or (b) also stop the gateway from drawing them and draw
our own status/side bars. Config of that bar: SysVar `Sys_Statusbar_Icon_Config_Key`,
`Sys_customer_statusbar`, `SYS_SHOW_TOOL_NAVI_BAR_WND` (CAR_API §2.3/§6.3).

---

## 4. Launcher ↔ gateway handshake, day/night theming, key control

**UI-mode / day-night handshake** (bidirectional broadcasts):
- gateway → launcher: `ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT`
  (`EventUtils.java:66`), sent from `sendDayNightUiModeToLauncher(int)` with extra
  `EXTRA_DAY_NIGHT_UIMODE` (int) — `EventService.java:14856-14860`.
- launcher → gateway: `ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT` (`EventUtils.java:76`), received
  at `EvtModel.java:1076-1080`, reads `EXTRA_DAY_NIGHT_UIMODE` and calls `setDayNightMode(int)`.

So the launcher both **receives** the current day/night mode and can **push** a mode back. For raw
illumination our launcher can also listen to `ACTION_DAY_BACKLIGHT_CHAGNED` /
`ACTION_NIGHT_BACKLIGHT_CHAGNED` (protected — needs the permission) and read SysVar
`Sys_Day_Night_Mode` (CAR_API §1.3/§2.3). The stock UI's `DayNightImageButton`/`DayNightImageBtnDrawable`
widgets swap drawables on this signal — our Compose theme should key off the same event.

**Launcher → gateway control:** send `com.szchoiceway.ACTION_LAUNCHER_KEY_CTRL`
(`ACTION_LAUNCHER_KEY_CTRL`) with String extra `EXTRA_LAUNCHER_KEY_WORD`. Handled in
`startLauncherCtrl(Intent)` — `EventService.java:12953-12989`. Recognized keywords (prefix match):

| `LauncherKeyWord` prefix | Gateway action |
|---|---|
| `BlackScreen` | toggle black-screen (`setSysBlackScreenState(true)`) |
| `DIM` | `ProccessDIMKey()` (dim toggle) |
| `Power` | `onPowerClicked(true)` |
| `TaskList` | recents (`onShowRecentTaskList()` on API>29, else broadcast `ACTION_SHOW_TASK_LIST`) |
| `StatusBar` | show/hide custom status bar (handler msg 297) |
| `Setting` | open settings mode (`postRunModeActivity(SRC_SETUP)`) |

Also useful: `Sidebar_function_action` (`SIDEBAR_FUNCTION_ACTION`, extra `Sidebar_function_extra`) →
`startSidebarFunctionCtrl` (`EventService.java:14594`); `ACTION_MORE_SETTINGS` opens the settings app
or the password-gated factory page depending on `getCustomerType()` (`EvtModel.java:914-935`).

**Customer/OEM variant** gates behavior: `getCustomerType()` (`EventService.java:4376`) reads/writes
SysVar `Sys_CustomerType` (`SysProviderOpt.java:287`; `EventService.java:6725,6868,11672`). e.g.
`customerType==1` routes "more settings" through the nav password page (`EvtModel.java:915-926`).
Our launcher can read `Sys_CustomerType` to match layout expectations.

---

## 5. Reverse trigger & SWC keys (how the launcher copes)

**Reverse.** The launcher does **little** here — reverse is owned by the gateway + a dedicated
activity, which draws over whatever is on screen:
- Gateway detects reverse from the MCU 0x71 sys-event (byte1 bit `0x02`, `EventService.java:2354`),
  calls `startBackcar()` and broadcasts `ACTION_BACKCAR_START` / `_END` (**protected**,
  `EventService.java:8978,8994`). CAR_API §1.3/§5.
- The reverse camera UI is the gateway/aux stack (`com.szchoiceway.view.BackCarActivity`, CAR_API §7;
  aux app `com.szchoiceway.auxcamera`). customerui *has* its own overlay widgets
  (`ReverseAssistLineView`, `ReverseCarTrackView`, `RadarViewUp/Down`) but on this device the
  full-screen reverse view is the gateway's.
- For **our** launcher: just listen for `ACTION_BACKCAR_START/END` to pause/hide home animations and
  restore on `_END`. Radar overlay data = `MCU_CAR_CAN_RADAR_INFO` (byte[] `CAR_CAN_DATA`), steering
  trajectory = `ZXW_CAN_WHEEL_TRACK_EVT` (int angle). Reverse tunables in SysVar
  (`Sys_backcar_fullscreen`, `Sys_Backcar_speed_threshold`, `Sys_Reverse_Assist_Line_Key`,
  `Sys_TrackLineType`, `Sys_BackCar_Display_Radar_Key`). CAR_API §2.3. **The protected broadcasts
  need `com.szchoiceway.permission.broadcast`** (see §7).

**SWC / steering-wheel & panel keys** (CAR_API §4 — listen to all three paths):
1. `STEER_WHEEL_INFOR` (protected) — extras `STEER_WHEEL_INFOR_LPARAM` (key idx), `_WPARAM`
   (3=down,4=up), `_VOLTAGE`. `EventService.java:2846-2857`.
2. `ACTION_HOST_MCU_BUTTON_KEY` (`HostKeyWord` int, `HostKeyStatus` byte) + `MCU_KEY_INFOR`
   (`MCU_KEY_VALUE` int). Unprotected.
3. Injected Android KeyEvents (`sendKeyDownUpSync`) — ordinary `onKeyDown` catches media/home keys.

Keycode constants `CAR_KEY_*` (`HOME=2, FAV=3, PREV=4, NEXT=5, MENU=6, MEDIA=8, RADIO=9, BACK=10`,
`EventUtils.java:1000-1014`) and `MCU_KEY_SYS_*` (`HOME=76, MENU=77, ESC=78`). The stock launcher maps
`HOME` → bring `MainActivity` forward; `MENU` → status bar; media keys → the media card. Wheel-key
learning uses activities `CarWheelActivity`/`McuWheelActivity` and SysVar `wheel_key_learn_custom`
(`SYS_WHEEL_INDEX_CUSTOM_KEY`) — our launcher can reuse the gateway's learned mapping rather than
re-implement learning.

---

## 6. App launching / drawer

- **Drawer entry point** = mode `SRC_APPLIST`. On landscape (`EventApp.getOrientation()!=0`) the
  gateway does **not** start a separate activity — it broadcasts `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`
  (`ACTION_LAUNCHER_ALLAPPS_START_EVT`, `EventUtils.java:74`) with extra `LAUNCHER_EXTRA="AppList"`,
  and **the launcher itself opens its in-process drawer** (`EventService.java:8221-8227`). In portrait
  it launches the standalone `AppLauncherListActivity`. Our 1920x720 unit is landscape, so we should
  **register a receiver for `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`** and show our own drawer.
- The home grid launches apps by resolving the `ICON_*` config (§3) to package/class. Customized user
  slots live in SysVar `SET_CustomizedPackageName_KEY0..6`; hidden apps in `SYS_LAUNCHER_APP_HIDE_KEY`.
- Mode launches for built-ins are done by the gateway via
  `EventUtils.startActivityIfNotRuning(pkg, cls)` per `SRC_*` (`EventService.java:8200-8240`), e.g.
  `SRC_SETUP` → settings app, `SRC_EXPLORER`/`SRC_DVR`/`SRC_AUX`/`SRC_PHONELINK`/`SRC_BT_ECAR`. A
  launcher tile can either `startActivity` directly or ask the gateway by sending a mode
  (`ACTION_LAUNCHER_KEY_CTRL` "Setting", or AIDL `sendMode/postRunModeActivity`).

---

## 7. Permissions / uid the stock launcher relies on (and what we must do)

customerui runs as **`android.uid.system`** and is platform-signed. Capabilities that depend on that:

| Capability stock launcher has | Our normal-app launcher | Fix |
|---|---|---|
| Receive **protected** events: `ACTION_BACKCAR_START/END`, `STEER_WHEEL_INFOR`, day/night backlight | ⚠️ only if it can hold `com.szchoiceway.permission.broadcast` (likely `signature` → normal app silently misses them) | Build as **privileged/system app**: platform-sign + push to `/system/priv-app`, whitelist the perm. Device is rooted → feasible. |
| **Write** SysVar (`Sys_Function_Icon_Config_Key`, icon order, hidden apps, reverse tunables) | ❌ as normal app | system uid / root; from shell `content update --uri content://com.szchoiceway.eventcenter.SysVarProvider/SysVar ...` |
| **Read** SysVar, bind `EventService` (read-only AIDL), receive **unprotected** events (media, radio, ACC, outside-temp, `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`) | ✅ | works as a plain HOME app |
| Have the gateway inflate its status/side bar from our resources | only if we keep the exact layout/id names of §3 | otherwise draw our own bars |
| `WRITE_SECURE_SETTINGS`, inject KeyEvents, split-screen control | ❌ | requires `/system/priv-app` + platform key |

**Recommendation (mirrors CAR_API §6.4):** ship the replacement as a **normal HOME app** for the
first cut (HOME + SysVar reads + AIDL reads + unprotected broadcasts already give a usable launcher:
media, radio, climate-read, outside temp, drawer, day/night-via-SysVar, app launching). Then, to get
reverse/SWC/protected day-night and to persist icon config, **re-sign with the platform key and
install to `/system/priv-app`** with `com.szchoiceway.permission.broadcast` (and optionally
`android.uid.system`) whitelisted — practical because the unit is rooted.

---

## 8. 1920x720 layout metrics

No customerui dimens are available (APK not pulled). What we know:
- Panel is **1920x720 landscape**, Android 13 (CAR_API header). Geometry is exposed via SysVar
  `Sys_Screen_Width` / `Sys_Screen_Height` / `Sys_Screen_Density` / `Sys_Landscape`
  (`SysProviderOpt.java`, CAR_API §2.3) — read these instead of hard-coding.
- Orientation drives drawer behavior: landscape (`getOrientation()!=0`) uses the in-process drawer via
  `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT` (§6).
- **To recover the real dp grid / card sizes**, decompile customerui *with* resources when the device
  is back: `MainActivity` layout + `layout_left_side_window_view_zxw` + the status-bar layout hosting
  the `btn*` ids in §3. This is the one section that genuinely needs the APK.

---

## 9. Confirm-when-online checklist (all §-items marked [inferred] until then)

1. Pull APK, jadx `-d mcu-analysis/customerui-src`, plus a resource pass.
2. Verify `MainActivity` HOME intent-filter (§2) and `sharedUserId="android.uid.system"` (§7).
3. Read the home layout dimens for 1920x720 (§8) and the status/side-bar layouts (§3).
4. Confirm receivers: `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`, `ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT`,
   `ACTION_BACKCAR_START/END`, `STEER_WHEEL_INFOR` (§4-6) and whether it declares
   `uses-permission com.szchoiceway.permission.broadcast`.
5. Confirm the `<permission android:protectionLevel>` for the Choiceway broadcast (still unknown —
   canbus2 APK corrupt; CAR_API §1.1).
