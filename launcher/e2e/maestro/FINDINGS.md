# UI/UX findings

What the Maestro flows (`launcher/e2e/maestro/`), the accessibility audit
(`app/src/androidTest/.../a11y/AccessibilityAuditTest.kt`), the source guard
(`app/src/test/.../ui/UiSourceAuditTest.kt`) and the suite sweeps have found, and where each
one ended up. Reproduce any of them with `headunit maestro 0 out/` (launcher) or
`MAESTRO_FLOWS=launcher/e2e/maestro/suite headunit maestro 0 out/` (suite), then read the PNGs.

## Fixed

Launcher, first pass (2026-09-22):
1. Phone's status line read "No phone · No phone": the device name and the HFP state both said
   it. One label when nothing is linked.
2. Phone held the party line's height with a blank " " label, which a screen reader announced
   as an unnamed node. A spacer now.
3. The top bar's Power & sleep opened its page over the Settings hub, so one Back went to the
   hub and a second Home. A deep link opens its page alone.

Launcher, second pass:
4. Home contradicted itself while the suite Radio played: the media card showed 87.50 MHz FM1,
   the Radio tile beside it "Radio idle. Tap to tune." The tile reads the MCU tuner, which is
   silent until the Radio screen asks it and blind to a NET stream; with no frequency it now
   shows the suite Radio's own session. Flow: `suite/radio-home.yaml`.
5. The label on an accent was picked by a luminance threshold, so the default blue carried
   white at 3.4:1. Picked by contrast now.
6. A disabled button dimmed its fill and kept its label at full strength (1.6:1) without
   saying it was disabled. Both dim together, and the semantics say disabled.
7. The night palette's muted text read 3.3:1 on its own background. 4.9:1 now.
8. Themes rows offered "Duplicate"/"Export" with no subject and Quick-controls icons repeated
   their row titles. Names in the labels; a decorative icon is silent.

Launcher, third pass:
9. `McuStateExport` bound its diagnostic socket in `onCreate` with no guard, so a port already
   held took the whole launcher down at start. Guarded, logged, and a carlib test holds the
   port and expects the launcher to live.
10. Only Settings caught the system Back. From Notifications, Themes, Vehicle or Media it fell
    through and left the launcher, showing whatever app sat behind it. Back returns Home now.
11. Night mode dimmed the screen 7%. Every preset's night field now drops 30–92% of its day
    luminance and every text pair clears 4.5:1 in both variants (`NightModeContrastTest`).
12. Five destructive controls under 48 dp (delete preset, delete backup, delete export, rename
    and delete profile, slider steps), an unnamed selection state in the option picker, a theme
    name that could wrap out of its cell, and two hardcoded descriptions. `UiSourceAuditTest`
    holds all three rules.

Suite apps:
13. Every app flashed the Android status bar the launcher hides. `Palette.apply` asks the insets
    controller (`windowFullscreen` is a no-op on API 30+).
14. News kept "Loading headlines…" under a full page of headlines; Converter never named itself;
    "1 tracks" (and its cousins in photos, recorder and files); a 24 px seek dot became a 48 dp
    control shared by Music and Radio. Guard: `scope/check-plurals.sh`.
15. Twelve more from the instance-2 sweep: tapping a search box replaced ten apps with a blank
    IME page; "STOPWATC/H"; "ETC/UTC"; four sub-48 dp controls in GPS and Speedometer;
    "ACCESS_FINE_LOCATION" and "FUSED fix" shown to a driver; three empty states that read as
    "nothing here" when the app was simply locked; "AM 5.30 kHz"; "1 note" over "No matching
    notes"; a raw storage path in a toast; a raw exception and "3km/h" in Weather. Guard:
    `scope/check-tap-targets.sh`.

Launcher, fourth pass (degraded states, tap targets, settings audit):
16. Five screens stated what they did not know: the Bluetooth chip read "nothing connected"
    when the permission to count was revoked; the notification shelf claimed "Nothing new"
    after access was withdrawn; the Ignition tile read "ACC on / vendor ACC broadcast" on a
    unit that had never heard the car (the ACC signal fails open to true by design, so a
    readout could not tell a default from a reading); a Phone escape hatch to a missing vendor
    page did nothing and said nothing; and backup, restore and SysVar export dropped their own
    failures, so the buttons were dead on a full disk. Guards: `DegradedReadingTest` and a
    fourth `UiSourceAuditTest` rule that fails any UI file discarding a fallible answer.
17. Every Quick controls target is 48 dp: the strip's tune icon sat in a 42 px box padded out
    by its neighbours, and the dialog's row icons were 24 dp. The 48 dp rule now has no
    exemptions. The SysVar row weights its key and value 1:2, so a long value no longer starves
    the key column. The shade's dismiss scrim is named rather than hidden, because the shade has
    no other close affordance a screen reader can use.
18. The accessibility audit walks all 25 Settings screens as well as the top bar, 35 in total.
    It found one ERROR: Climate's "Heated seats" row reached the accessibility tree unlabelled,
    because its name lives in a child below the viewport fold.
19. The CarPlay screen printed the daemon's protocol trace over the panel while waiting for the
    phone's picture. A bench build keeps the trace; every other build says what it is waiting
    for (rav4-apps #75).

## Open

Launcher:
- At `font_scale 1.3` AND `wm density 280` together, the Vehicle dashboard clips its tile
  captions and its bottom row falls off the panel. The fix is a layout rework of the weighted
  three-column grid into intrinsic heights with a scroll. At 240 dpi the same font scale holds.
- Parking radar announces two switches both as "On". Naming them would duplicate the row name
  they sit beside, so the warning is listed rather than fixed.

Suite apps:
- Photos throws on an image it cannot decode; Files reads "This folder is empty" for a folder it
  cannot read; Video shows a black player when audio focus is refused. All three need a state
  that is hard to reach on the farm.
- Compass says "True bearing" over an uncorrected magnetic heading.
- Clock shows a 24 h panel above 12 h alarm cards.
