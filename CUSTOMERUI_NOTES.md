# com.szchoiceway.customerui — Stock Launcher Notes (REFERENCE)

Reference for building the Kotlin/Compose replacement launcher in [`launcher/`](launcher/).
Cross-reference: [`CAR_API.md`](CAR_API.md) (the car-integration API).

> **STATUS — APK PULLED & DECOMPILED (2026-08-27).**
> `com.szchoiceway.customerui.apk` (260 MB, versionName `1.0_202412261721`, minSdk 28 / target 33 /
> compile 33 = Android 13) was decompiled **with resources** via
> `mcu-analysis/jadx/bin/jadx -d mcu-analysis/customerui-src`. Source at
> `mcu-analysis/customerui-src/sources/`, resources at `mcu-analysis/customerui-src/resources/`
> (946 layouts, a dedicated `res/layout-hdpi-1920x720/` + `res/values-hdpi-1920x720/`,
> `res/drawable-*-1920x720/`).
>
> Items are now marked **[confirmed]** (verified in customerui itself), **[corrected]** (the offline
> inference was wrong), or **[inferred]** (still only from the gateway). `file:line` citations are now
> **into customerui** unless the row says "(eventcenter)".
>
> **Biggest surprises (read these first):**
> 1. **customerui is NOT an Android HOME app.** No activity declares `category.HOME`. `MainActivity`
>    is a 25-line stub that only fixes orientation and calls `finish()` — see §2.
> 2. **The home UI is a *window*, not an activity.** The root home view `layout_launcher_zxw`
>    (→ `com.szchoiceway.customerui.views.LauncherView`) is referenced **only in R.java**, i.e. it is
>    inflated *by name* from another process — the same by-name inflation the gateway uses for the
>    status bar and side window (§2, §3).
> 3. **It's a giant multi-OEM launcher.** ~55 launcher skins (Benz/BMW/Audi/Porsche/Lexus/Toyota/BYD/
>    Wrangler/…) selected at runtime by an integer SysVar **`Sys_UINumber`**. Our unit uses the
>    **default** skin (§2). The `zxw` resources the gateway inflates by name are that default.
> 4. customerui declares **no custom permissions at all** — not even
>    `com.szchoiceway.permission.broadcast`. It receives protected broadcasts purely via
>    `android:sharedUserId="android.uid.system"` + platform signature (§7).

---

## 1. Identity & components  [confirmed]

Manifest: `mcu-analysis/customerui-src/resources/AndroidManifest.xml`.

| Thing | Value | Evidence |
|---|---|---|
| Package | `com.szchoiceway.customerui` | manifest `package=` |
| sharedUserId | **`android.uid.system`** | manifest line 3 **[confirmed]** |
| Application class | `com.szchoiceway.customerui.CustomerUIApp`, theme `@android:style/Theme.Wallpaper.NoTitleBar`, `configChanges="orientation"` | `<application>` |
| **"Home"/main activity** | `com.szchoiceway.customerui.MainActivity` — theme `@style/AppNewZxw`, exported, filter `MAIN`+`LAUNCHER` (**no HOME**). Stub, see §2 | `MainActivity.java:1-25` **[corrected]** |
| Duplicate launcher entry | `com.szchoiceway.customerui.EmptyActivity` — also `MAIN`+`LAUNCHER`, theme `AppNewZxw` | manifest |
| App-drawer activity | `com.szchoiceway.activity.AppLauncherListActivity` — `launchMode="singleInstance"`, `configChanges="smallestScreenSize|screenSize|uiMode|screenLayout|orientation"`, `resizeableActivity="false"`, theme `AppThemeAppLauncherListActivity` | manifest **[confirmed]** |
| Screensaver | `com.szchoiceway.activity.ScreensaverActivity` (`singleInstance`) | manifest |
| Background/home host | `com.szchoiceway.activity.BackgroundActivity` (`singleInstance`, theme `AppThemeSinkingInvasion`) | manifest |
| Icon-picker | `com.szchoiceway.icon.AppSelectActivity` (`singleTask`) | manifest |
| Dialogs | `dialog.SearchDialog`, `cheku.dialog.ChooseMyAppDialog` (both `singleInstance`) | manifest |
| Cheku (Lincoln/Bentian) | `cheku.activity.ChekulinkenChooseCarTypeActivity`, `…ChooseThemeActivity` (`singleTask`) | manifest |
| UI service | **`com.szchoiceway.service.UiService`** (action `com.szchoiceway.service.action.UI_SERVICE`) — only manages the up/down center popup (`WmUpDownCenterView`), NOT the launcher window | `UiService.java` |
| Content provider | `com.szchoiceway.customerui.CoreContentProvider` (authority `com.szchoiceway.customerui.CoreContentProvider`) — exposes `getGlobalContext()` | manifest |
| Media notification bridge | `com.zxw.lib.ui.service.MediaNotificationService` | manifest |
| **AppWidget providers** (customerui is also a widget host) | Music/Clock/Radio/Weather/Gyro/Compass/Meter/Calendar widgets in many sizes (`MusicWidget*`, `AnalogClockWidget*`, `RadioWidget*`, `WeatherWidget*`, `GyroWidget*`, `CompassWidget*`, `MeterWidget*`, `PlayerWidget_2x2`, `WeatherReportWidget_2x2`, …) backed by services `MusicService/ClockService/RadioService/WeathService/CalendarService/GyroService/CompassService/MeterService/AppCustomService` | manifest **[new]** |

Package roots that ship in the APK (all confirmed present): `com.szchoiceway.customerui.*`,
`com.szchoiceway.activity.*`, `com.szchoiceway.view.*` (+ `view.item.*`, `view.secondary.*`),
`com.szchoiceway.launcher.*` (per-OEM skins), `com.szchoiceway.cheku.*`, `com.szchoiceway.widget.*`,
`com.zxw.lib.ui.*` (shared UI lib), `com.core.ex.*` (shared "ex" widget lib: `SmpDn*` day/night
views, `CoreAdapterImp`, `LinearLayoutManagerX.PageGrid2`, `IndicatorView`, `RoundedImageView`).

`MusicModeActivity` / the `Reverse*`/`Radar*` overlay widgets that the offline pass listed **do not
exist** under those names in this APK. **[corrected]** Reverse is entirely the gateway's job (§5).

Runs as **`android.uid.system`** and is platform-signed. Our normal-app launcher can **not** share
that uid; consequences in §7.

---

## 2. How it becomes "home"  [corrected]

**It does not register as Android HOME.** No `<category android:name="android.intent.category.HOME">`
appears anywhere in the manifest. `MainActivity` and `EmptyActivity` only carry `MAIN`+`LAUNCHER`.

`MainActivity` is a **stub** that never draws anything:

```java
// MainActivity.java:11-24  [confirmed]
protected void onCreate(Bundle b){
  super.onCreate(b);
  String o = getIntent().getStringExtra("ScreenOrientation");
  if ("SCREEN_ORIENTATION_LANDSCAPE".equals(o)) { if (getRequestedOrientation()!=0 && getDisplayId()!=0) setRequestedOrientation(0); }
  else if ("SCREEN_ORIENTATION_PORTRAIT".equals(o) && getRequestedOrientation()!=1) setRequestedOrientation(1);
  finish();                       // <-- returns immediately
}
```

So "launching MainActivity" (which the gateway does from `DualScreenDisplayManage.java:402,419`,
`HDMIManage.java:439,456`, `HDMIOutUtils.java:40` — eventcenter) is just a way to **force screen
orientation**, not to show the launcher.

**The real home UI is a window inflated by name.** The root home layout is:

```xml
<!-- res/layout/layout_launcher_zxw.xml  [confirmed] -->
<com.szchoiceway.customerui.views.LauncherView
    android:theme="@style/Theme.Customerui" android:background="?attr/colorOnPrimary"
    android:layout_width="match_parent" android:layout_height="match_parent"/>
```

`layout_launcher_zxw` is referenced **only in `R.java`** — nothing inside customerui inflates it — so
it is inflated from **another process** (the gateway, via `createPackageContext("com.szchoiceway.customerui")`),
exactly the mechanism the notes already documented for the side window and status bar (§3). This is
why MainActivity can finish immediately: the launcher content lives in a system window, not an activity.

**Skin selection.** `LauncherView` reads integer SysVar **`Sys_UINumber`**
(via the helper `SystemPropertiesHelps.I.uiNumberKey`) and inflates one of ~55 per-OEM roots
(`LauncherView.java:40-188` **[confirmed]**). Mapping excerpt:

| `Sys_UINumber` | root layout | skin |
|---|---|---|
| **default (else)** | **`launcher_common_land`** → `com.szchoiceway.view.LauncherLandView` | **the plain "zxw" launcher — our unit** |
| 1200 / 1201 / 1202 | `bba_benz` / `bba_bwm` / `bba_audi` | Benz / BMW / Audi (force night) |
| 1041 / 1045 / 1053 | `benz_launcher_view` / `benz2` / `benz_second` | Benz variants |
| 1038 / 1046 / 1043 / 1800 | porsche / porsche_second / porsche_768 / ch_porsche | Porsche |
| 1044 / 1050 | lexus / lexus_gyroscope | Lexus |
| 1039 / 1040 | cheku_bentian / cheku_lincoln | Honda / Lincoln (cheku) |
| 5000 / 5001 / 5100 / 5200 | wrangler / zhihang_alphard / yuntang / zhiyin_alphard | Toyota/Jeep skins |
| 10008 / 10009 / 10001 / 10007 | bentley / mazda_axela / vertical_default / vertical_benz | misc |
| 1051 / 1052 / 1500 / 1600 / 1900 | byd_ts / tanke_300 / hrui / trda / yimi | misc |

Because our device shows the plain grid, its `Sys_UINumber` is the default bucket, and the gateway's
by-name inflation of `layout_status_bar_zxw` / `layout_left_side_window_view_zxw` (the **`_zxw`** =
default resources) matches. **To confirm the exact number on the device:**
`content query --uri content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` and look for the
`Sys_UINumber` row (`SysProviderOpt.SYS_UI_NUMBER_KEY`; `uiNumberKey` is only the helper method's name).

For **our** launcher: since customerui is not a HOME app, "taking over HOME" the Android way is a
non-issue — but there is also no HOME app to fall back on. The practical replacement path is to draw
our own window / register as HOME ourselves and stop the gateway from inflating `layout_launcher_zxw`.
Both `com.szchoiceway.customerui` and `com.android.atslcarconsole` (`EventUtils.java:206`, eventcenter)
are the OEM's home candidates.

---

## 3. Home-screen composition & data sources

### 3a. Default "zxw" home = `LauncherLandView`  [confirmed]

`launcher_common_land` (in `res/layout-xhdpi/`) is just `com.szchoiceway.view.LauncherLandView`.
`LauncherLandView` builds a full-screen **`ViewPager`** (`launcher_land_view.xml` — root
`SmpDnFrameLayout` with day/night wallpaper `icon_bg_desktop_bg_1` / `…_night`, `LauncherLandView.java:468,474`):

- **Page 0 = "negative / minus-one screen"** — widget dashboard,
  `page_menu_one_negative_screen_land.xml` (`LauncherLandView.java:475`). Layout percentages
  (of full 1920×720): **TimeCard 18 % width** (left), then a right block with **`dashboard`
  VirtualView 72 %** + **weather/music column 26 %** (`WeatherInfoView` `weather_view_type=11` 35 % +
  `MusicInfoView` `type=1` 63 %), and a bottom **`ItemAppInfoOneNegScreenView` 25 % height**.
  (`v_navi`/`v_navi_space` weather slot is hidden by code at `:477-478`.)
- **Pages 1…N = the app-icon grid** — each page is `page_launcher_recycler_view.xml`
  (= `com.szchoiceway.view.LauncherLandAppListView`, `LauncherLandView.java:481`).

### 3b. The icon grid — real 1920×720 metrics  [confirmed]

`LauncherLandAppListView extends ItemAppListView`. Grid geometry
(`ItemAppListView.java:139-162 setRowsAndColumns()`):

```
ratio = width/height ;  for 1920x720 ratio = 2.667
  ratio <= 2.2            -> 3 rows x 5 cols
  2.2 < ratio < 3.0       -> 2 rows x 6 cols   <-- OUR DEVICE (12 icons/page)
  3.0 <= ratio < 3.4      -> 2 rows x 7 cols
  ratio >= 3.4            -> 2 rows x 8 cols
  uiNumberKey == 10008    -> 2 rows x 5 cols (Bentley)
```

- **1920×720 = 2 rows × 6 columns = 12 icons per page**, paged horizontally
  (`LinearLayoutManagerX.PageGrid2(orientation=0, rows, cols).isAutoSetItemWH(true)`,
  `ItemAppListView.java:119-126`). `isAutoSetItemWH(true)` = cells auto-sized to fill the page, so
  there is **no fixed cell dp** — cells fill `(pageW − 80dp margins) / 6` × `(pageH − 90dp) / 2`.
- Page container margins/indicator (`item_launcher_recycler_view.xml` **[confirmed]**):
  RecyclerView `marginLeft/Right = 40dp`, `marginTop/Bottom = 45dp`; `IndicatorView` bottom-center,
  `marginBottom 30dp`, dots `25dp × 5dp`, `5dp` spacing, selected `#0096ff`.
- **Icon cell** (`item_app_info_phone_gird_rec.xml`, chosen when `isLandRectangleScreen()`,
  `AppListItemBeanView.java:22` **[confirmed]**): vertical `SmpDnLinearLayout`, centered; icon
  `RoundedImageView` **93 dp × 93 dp, corner radius 15 dp**; label `SmpDnTextView` **20 sp**,
  `marginTop 5dp`, 1 line, day `#000` / night `#fff`; press feedback scale/alpha **0.9**.
- Icons are **drag-reorderable**; order persists to SysVar **`desktopStyleAppOrderKey`**
  (`LauncherLandAppListView.java:113`, `SystemPropertiesHelps.java:809`). The app set comes from
  `AppFilterHelps.Other.queryAppInfoList()` (installed apps) — **not** a fixed widget list.
- The status-bar height is padded in at the top (`ItemAppListView.java:136`,
  `DisplayHelps.getStatusBarHeight`).

### 3c. Confirmed device-specific composition `launcher_main_common_land_1920_720`  [confirmed / key]

`res/layout/launcher_main_common_land_1920_720.xml` is a **fixed clock + grid** composition explicitly
tuned for 1920×720 (root `SmpDnCslView`, day bg `gb_land_bj_1920_720`, night `gb_land_bj_night`):

| Element | Constraint (fraction of 1920×720) | ≈ px |
|---|---|---|
| `vwTimeCard` (`TimeCard`) | left, full height, **width 14.6 %** | ≈ 280 × 720 |
| `layout_ViewPager` block | right, full height, **width 89 %** | ≈ 1709 × 720 |
| ↳ `leftBounds` / `rightBounds` gutters | **2.5 %** each of the block | ≈ 43 px |
| ↳ `viewPager` (`com.android.internal.widget.ViewPager` — needs system app) | **95 %** of the block | ≈ 1624 × 720 |
| ↳ `BtInfoView` overlay | full width, **height 17 %** | ≈ 1709 × 122 |
| `lottie_home_float` (`lottie/home_float_click.json`) | over TimeCard | **105 × 105 dp** |
| `vwBottom` reserved strip | bottom, **height 10 %** | ≈ 1920 × 72 |

So on our panel: **clock column ~14.6 % (~280 px) on the far left, paged icon grid fills ~89 % on the
right**, with ~2.5 % inner gutters and a ~10 % bottom strip. (This resource is R-referenced only, so it
too is inflated by name — a variant of the negative-screen composition; the two share `TimeCard` +
`ViewPager`.)

### 3d. The clock (`TimeCard`)  [confirmed]

`TimeCard` inflates a **1920×720-specific** layout `layout_item_time_info_land_1920_720.xml`
(`TimeCard.java:31`): hours & minutes each **90 sp**, AM/PM & date labels **20 sp**, small
±10 dp nudges. So the clock is plain Android time, big 90 sp digits (no car API).

### 3e. Live-data widgets — data sources  [confirmed / refined]

| Widget (class) | How it actually gets data | vs CAR_API / prior note |
|---|---|---|
| **Media** (`view.item.MusicInfoView`, layout `layout_item_music_info_ui1`) | Binds a **`ZxwMediaBean`** abstraction — `getMediaTitle()/getMediaArtist()/getMediaCurrentTimeStr()/getMediaTotalTimeStr()/getMediaComponent().getLabel()` (`MusicInfoView.java:200-214`). Fed by AIDL valid-mode + `MediaNotificationService`; broadcast triggers `VALID_MODE_INFOR_CHANGE`, `ZXW_ACTION_NOTIIFY_MEDIA_PLAY_PATH` (`IBroadcastImp.java`). **[corrected]** — it does **not** listen to the raw `ZXW_MUSIC_PLAY_SONG_NAME_EVT` broadcasts the offline note assumed; use `getValidModeInfor()/getValidCurTrack()` per CAR_API §3.2/§6.3 |
| **Weather** (`view.item.WeatherInfoView`) | **Online weather API** — `resultsDTO.getAir().getCity().getQuality()` etc. (`WeatherInfoView.java:132`); layouts `desktop_weather_info` / `layout_item_weather_info_land` / `screesaver_weather_info` (`:200-205`). **[new]** — this is Internet weather, **not** a CAN signal |
| **Radio** | `RadioWidget*` + `RadioService`; radio actions resolve via `EventUtils` (`EventUtils.java` has `ZXW_RADIO_INFO`). Consistent with CAR_API §2.3 (`getRadioFreq/Band`) |
| **Climate / A/C** | Broadcast **`com.choiceway.canbus.carairstruct`** → `CarAirState` parcelable, registered in `IBroadcastImp.java`. Matches CAR_API §1.3/§5. **[confirmed]** |
| **BT status** (`view.item.BtInfoView`) | `com.szchoiceway.btsuite.HBCP_EVT_*` (power/connected-device/HSHF status), `IBroadcastImp.java`. **[new]** |
| **Outside temp** | `com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT` (+`_EXTRA_STR`), `IBroadcastImp.java`. Matches CAR_API §1.3 **[confirmed]** |
| **SWC / keys** | `com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR`, `MCU_KEY_INFOR`, `CMD_PANEL_STUDY_INFOR`, `ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_EVT`, `ACTION_CLICK_SYSTEM_KEYCODE_EVENT`, `IBroadcastImp.java`. Matches CAR_API §4 **[confirmed]** |
| **Nav** | SysVar `Set_NavPackageName`/`Set_NavClassName` (`SysProviderOpt.java`); `weather_view_type=11` slot in the neg-screen is nav/weather. |
| **Dashboard/gauges** (`view.item.VirtualView`) | 72 %-wide virtual-instrument view on the negative screen (data via gyro/meter services) **[new]** |

### 3f. `ICON_*` / `Sys_Function_Icon_Config_Key`  [refined]

The `ICON_NAVI,ICON_RADIO,…` catalog + `Sys_Function_Icon_Config_Key` live in the **per-OEM
`Customer*` profile classes** (`customerui/customer/CustomerKW.java`, `CustomerKSP.java`, `CustomerCK.java`,
`CustomerCHWY.java`, `CustomerKLD.java`, … **[confirmed present]**). That is an **older / alternate**
menu mechanism used by some skins. **Our default skin's home grid uses `desktopStyleAppOrderKey`**
(drag order) + `queryAppInfoList` instead (§3b). So for a replacement, follow §3b, not the ICON_ list —
but the ICON_ config still governs the fixed function tiles on OEM skins that use `Customer*`.

### 3g. Shared floating UI hosted BY customerui, inflated BY the gateway  [confirmed]

Still true and now verified — all three are thin custom-View wrappers:

- **Side window**: `layout_left_side_window_view_zxw.xml` = `com.szchoiceway.customerui.views.BaseLeftNavBarView`
  (draws its own children). Inflated by gateway `SideWindow.java:98-103` (eventcenter). **[confirmed]**
- **Status bar**: `layout_status_bar_zxw.xml` = `views.StatusBarView`;
  `layout_tool_status_bar_zxw.xml` = `views.ToolStatusBarView`. The `btn*` ids the gateway's
  `CustomStatusbar.java` resolves (`ibtscreenshot, btn_home, btnShowApp, btnUp, btnDown, btnTask,
  btnWifiStatus, btnAirplanemode, btnBTStatus, btnCast, btnHotspot, btnBlackScreen, btnDataroaming`)
  are built by those View classes (they also appear in skin layouts such as `land_status_view.xml`,
  `bba_audi_status_view.xml`, `layout_navi_bar_*`). **[confirmed]**
- **System nav bar** (`sys_nav_land_status_view.xml`): symmetric left/right blocks each `250dp` wide,
  value labels `120×100dp`, a center `view_pager` (media/air/weather pager: `sys_nav_item_media`,
  `sys_nav_item_air`, `sys_nav_item_weather`), double-button vol/media glyphs `48×137dp`. **[new]**

If we replace customerui we must either (a) keep those exact layout/id names so the gateway keeps
inflating them, or (b) also stop the gateway drawing them and draw our own. Config SysVars unchanged:
`Sys_Statusbar_Icon_Config_Key`, `Sys_customer_statusbar`, `SYS_SHOW_TOOL_NAVI_BAR_WND` (CAR_API §2.3/§6.3).

---

## 4. Launcher ↔ gateway handshake, day/night, key control

Runtime broadcast registry: `com/zxw/lib/ui/broadcast/IBroadcastImp.java` (customerui registers these
**dynamically** — the manifest has **no** static `<receiver>` for gateway events; its 45 `<receiver>`
entries are all AppWidget providers). Confirmed actions customerui listens to:
`com.szchoiceway.ACTION_LAUNCHER_KEY_CTRL`, `…EventUtils.STEER_WHEEL_INFOR`, `…MCU_KEY_INFOR`,
`…VALID_MODE_INFOR_CHANGE`, `…ZXW_ACTION_NOTIIFY_MEDIA_PLAY_PATH`, `…ACTION_ACC_SLEEP_STATUS_EVT`,
`…ACTION_CLICK_SYSTEM_KEYCODE_EVENT`, `…CMD_PANEL_STUDY_INFOR`, `…ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_EVT`,
`com.choiceway.canbus.carairstruct`, `CAN_CAR_OUT_SIDE_TEMP_EVT`, `HBCP_EVT_*` (BT),
`com.szchoiceway.action.ACTION_SINGLE_DOUBLE_BUTTON_FUNCTION_SELECTION`,
`com.szchoiceway.EventUtils.ACTION_REFRESH_CUSTOMER_UI`, and a per-launcher `uiModeNightChanged`
(`com.szchoiceway.uiModeNightChanged`). **[confirmed / expanded]**

**Day/night** — the offline pass's broadcast pair
(`ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT` ⇄ `ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT`) is an
**eventcenter-side** constant; **inside customerui the day/night switch is done by
`SettingsSystemHelps.SystemPropertiesX.setUiModeNight/​setUiModeDay` + the local `uiModeNightChanged`
broadcast + `ConfigurationHelps.curUIModeNight()`** (`LauncherView.java:47-76`) driving the
`SmpDn*` day/night views (`app:*_day` / `app:*_night` attrs everywhere, e.g. `launcher_land_view.xml`).
**[refined]** Benz/BMW/Audi skins force night (`setUiModeNight(true,2)`, `LauncherView.java:44-48`).
Our Compose theme should key off `uiModeNightChanged` / SysVar `Sys_Day_Night_Mode`.

**Launcher → gateway control** (`com.szchoiceway.ACTION_LAUNCHER_KEY_CTRL`, String extra
`EXTRA_LAUNCHER_KEY_WORD`) — still handled gateway-side (`EventService.java:12953-12989`, eventcenter);
keyword prefixes `BlackScreen / DIM / Power / TaskList / StatusBar / Setting`. customerui **sends** these
(it registers the action in `IBroadcastImp`). `Sidebar_function_action`, `ACTION_MORE_SETTINGS`
unchanged. **[inferred — gateway side]**

**Customer/OEM variant** now has a customerui-side counterpart: the `Customer*` profile classes
(`customerui/customer/*`) + the integer **`Sys_UINumber`** SysVar (§2) select skin/behavior; the
gateway's `Sys_CustomerType` still gates "more settings". **[refined]**

---

## 5. Reverse trigger & SWC keys  [confirmed — gateway owns reverse]

Confirmed: customerui has **no** reverse/radar activity of its own (the `Reverse*`/`Radar*` classes the
offline pass guessed are absent). It only **listens** to SWC/key events (`STEER_WHEEL_INFOR`,
`MCU_KEY_INFOR`, `ACTION_CLICK_SYSTEM_KEYCODE_EVENT` in `IBroadcastImp.java`). Reverse is entirely the
gateway + `com.szchoiceway.auxcamera` / `com.ivicar.avm`:

- Gateway detects reverse from MCU 0x71 (`EventService.java:2354`, eventcenter), broadcasts
  **protected** `ACTION_BACKCAR_START` / `_END`. CAR_API §1.3/§5.
- For **our** launcher: listen for `ACTION_BACKCAR_START/END` to pause/restore home. Radar =
  `MCU_CAR_CAN_RADAR_INFO`, trajectory = `ZXW_CAN_WHEEL_TRACK_EVT`. **These are protected → need
  `android.uid.system` (§7).** Reverse tunables in SysVar unchanged.

**SWC** (CAR_API §4) — listen to `STEER_WHEEL_INFOR` (protected), `ACTION_HOST_MCU_BUTTON_KEY` /
`MCU_KEY_INFOR` (unprotected), and injected KeyEvents. Keycode constants `CAR_KEY_*`,
`MCU_KEY_SYS_*` per eventcenter. Wheel-learn: customerui has **no** `CarWheelActivity/McuWheelActivity`
(also absent — those are gateway/settings side). **[corrected]**

---

## 6. App launching / drawer  [confirmed]

- **Drawer** = `AppLauncherListActivity` (`singleInstance`). Its content root is also chosen by
  `uiNumberKey` (`AppLauncherListActivity.java:20+`); the **default** bucket uses the same
  `ItemAppListView` grid engine as the home pages (§3b) → **2×6 on 1920×720**, drag-orderable via
  `desktopStyleAppOrderKey`. App set from `AppFilterHelps.queryAppInfoList()`.
- Landscape (our unit): the gateway broadcasts `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT` (extra
  `LAUNCHER_EXTRA="AppList"`) and the launcher opens its in-process drawer; customerui registers this
  action (`EventUtils.java`, customerui). So **register a receiver for
  `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`** in our launcher. **[confirmed]**
- Built-in mode launches (`SRC_SETUP`, `SRC_EXPLORER`, `SRC_DVR`, …) stay gateway-driven via
  `EventUtils.startActivityIfNotRuning` (eventcenter). A tile can `startActivity` directly or send a
  mode. **[inferred — gateway side]**

---

## 7. Permissions / uid  [corrected]

customerui runs as **`android.uid.system`** + platform-signed. Full custom-permission audit of its
manifest: **it declares NO custom permissions** — no `<permission>`, no
`uses-permission com.szchoiceway.permission.broadcast`, nothing OEM-specific. Its non-Android
uses-permissions list is **empty**. **[corrected — the offline note guessed it held that permission]**
It receives protected broadcasts (`ACTION_BACKCAR_START/END`, `STEER_WHEEL_INFOR`, backlight) **purely
by virtue of `sharedUserId=android.uid.system` + the platform signature.** It does hold the strong
platform perms `WRITE_SECURE_SETTINGS`, `INJECT_EVENTS`, `INTERNAL_SYSTEM_WINDOW`,
`MANAGE_ACTIVITY_TASKS`, `START_TASKS_FROM_RECENTS`, `REMOVE_TASKS`, `READ_PRIVILEGED_PHONE_STATE`,
`INTERACT_ACROSS_USERS`, `SET_WALLPAPER`, `NETWORK_STACK` (manifest lines 14-40).

Consequence for our replacement is unchanged but the *mechanism* is now clear: **holding the Choiceway
permission is not an option (it doesn't exist)**; to get protected broadcasts + SysVar writes we must
ship as a **platform-signed system app under `android.uid.system`** (or `/system/priv-app` with those
platform perms), which the rooted unit allows. As a plain HOME app we still get: SysVar reads, AIDL
reads, unprotected broadcasts (media/radio/air/outside-temp/BT/`ALLAPPS`), app launching, day/night via
SysVar — a usable first cut (CAR_API §6.4).

| Capability | Normal-app launcher | Fix |
|---|---|---|
| Protected events (`BACKCAR`, `STEER_WHEEL`, backlight) | ❌ (perm doesn't exist to request) | platform-sign + `android.uid.system` |
| Write SysVar (`desktopStyleAppOrderKey`, icon order, tunables) | ❌ | system uid / root / `content update …SysVarProvider/SysVar` |
| Read SysVar, bind `EventService`, unprotected broadcasts | ✅ | plain app |
| Gateway inflates our status/side/nav bars | keep exact `_zxw` layout/id names | else draw our own |
| `com.android.internal.widget.ViewPager`, INJECT_EVENTS, WRITE_SECURE_SETTINGS, task control | ❌ | `/system/priv-app` + platform key |

---

## 8. 1920×720 layout metrics — CONFIRMED  [confirmed]

Panel **1920×720 landscape**, Android 13. Read geometry from SysVar `Sys_Screen_Width/Height/Density`
at runtime; but the launcher's own numbers are now known:

**Default home (skin = default), from the confirmed resources above:**

- **Skin selector**: SysVar `Sys_UINumber`; default bucket → `LauncherLandView` (§2).
- **Grid**: **2 rows × 6 columns = 12 icons/page**, horizontal paging, auto-sized cells
  (`ItemAppListView.java:139-162,119-126`). Page insets **40 dp L/R, 45 dp T/B**; page indicator
  dots **25×5 dp**, 5 dp gap, `marginBottom 30dp`, selected `#0096ff` (`item_launcher_recycler_view.xml`).
- **Icon cell**: icon **93×93 dp** rounded **15 dp**; label **20 sp**, `marginTop 5dp`; press scale 0.9
  (`item_app_info_phone_gird_rec.xml`).
- **Clock (`TimeCard`)**: `layout_item_time_info_land_1920_720.xml` — hours+minutes **90 sp**,
  labels **20 sp**.
- **Fixed 1920×720 composition** `launcher_main_common_land_1920_720.xml`:
  clock **14.6 % (~280 px)** left, grid block **89 % (~1709 px)** right, inner gutters **2.5 %** each,
  `viewPager` **95 %** of block, `BtInfoView` overlay **17 %** height, `vwBottom` strip **10 %** height,
  home-float lottie **105×105 dp**. Backgrounds `gb_land_bj_1920_720` (day) / `gb_land_bj_night`.
- **Negative screen** (page 0) `page_menu_one_negative_screen_land.xml`: TimeCard **18 %** + dashboard
  **72 %** + weather(35 %)/music(63 %) column **26 %** + bottom app-info bar **25 %** height;
  right padding 30 dp.
- **System nav bar** `sys_nav_land_status_view.xml`: side blocks **250 dp**, value labels **120×100 dp**,
  center media/air/weather `view_pager`, vol/media glyphs **48×137 dp**.
- Note the fixed composition uses `com.android.internal.widget.ViewPager` (system-app API).

Everything is in `mcu-analysis/customerui-src/resources/res/` (base `layout/`, plus
`layout-hdpi-1920x720/` which only overrides a `cheku_binli` variant — the default uses base `layout/`
+ code-computed sizes, so there is **no** `dimens.xml` under `values-hdpi-1920x720/`, only `drawables.xml`).

---

## 9. Confirm-when-online checklist — RESULTS

1. ✅ **Done** — APK pulled, jadx `-d customerui-src` **with** resources (946 layouts + arsc).
2. ✅ `sharedUserId="android.uid.system"` **confirmed** (manifest:3). ❗ **`MainActivity` has NO HOME
   filter** — it is a stub that finishes; home is a **window** inflating `layout_launcher_zxw`
   (`LauncherView`) by name (§2). **[corrected]**
3. ✅ **1920×720 dimens recovered** (§3b, §3c, §8) — 2×6 grid, 93 dp icons/20 sp labels, 14.6 %/89 %
   split; status/side/nav bars are `StatusBarView`/`ToolStatusBarView`/`BaseLeftNavBarView`/
   `sys_nav_land_status_view` (§3g).
4. ✅ Receivers are **runtime-registered** (`IBroadcastImp.java`), not in the manifest. Confirmed:
   `ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT`, `STEER_WHEEL_INFOR`, `MCU_KEY_INFOR`, `carairstruct`,
   `CAN_CAR_OUT_SIDE_TEMP_EVT`, `HBCP_EVT_*`, `ACTION_LAUNCHER_KEY_CTRL`, local `uiModeNightChanged`
   (§4). Day/night in-app uses `setUiModeNight/Day` (not the eventcenter UIMODE broadcast pair). ❗
   **customerui declares NO `com.szchoiceway.permission.broadcast`** (or any custom perm) — protected
   events come via `android.uid.system` only (§7). **[corrected]**
5. ⬜ Choiceway broadcast `protectionLevel` — still not answerable from customerui (it declares no
   `<permission>`; the definition lives in eventcenter/canbus, and canbus2 was corrupt — CAR_API §1.1).
   But customerui's reliance on `android.uid.system` (not a permission) implies the protection is
   `signature|system`-class.

### Net effect for our launcher
Build the default-skin equivalent: full-screen `ViewPager`, **2 rows × 6 icon grid** (93 dp rounded-15
icons, 20 sp labels, 40/45 dp insets, dot indicator), a **left ~14.6 % clock column (90 sp)** and
negative-screen widgets (dashboard 72 % + weather 26 % + media). Media via AIDL `getValidModeInfor()`
(not raw music broadcasts); weather is Internet, not CAN; climate via `carairstruct`. First cut = plain
app; platform-sign into `android.uid.system` later for protected reverse/SWC + SysVar writes.
