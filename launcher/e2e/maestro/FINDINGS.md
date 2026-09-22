# UI/UX findings from the first Maestro + accessibility pass (2026-09-22, farm instance 0)

Fixed in the same change:

1. Phone: status line read "No phone · No phone" (device name and HFP state both said it).
2. Phone: a blank " " label held the party line's height; a screen reader announced nothing.
3. Top bar → Power & sleep opened over the Settings hub: two Backs to Home.

Second pass (2026-09-22), also fixed:

4. Home contradicted itself while the suite Radio played: the media card showed "87.50 MHz
   FM1" with transport, the Radio tile beside it "Radio idle. Tap to tune." The tile read the
   MCU tuner, which is silent until the Radio screen asks it and knows nothing about a NET
   stream; with no frequency it now shows the suite Radio's own session. Flow: suite/radio-home.
5. The label on an accent was picked by a luminance threshold, so the default blue (0.23, under
   the 0.4 cut) carried white at 3.4:1. It is picked by contrast now: black on that blue, 4.8:1.
6. A disabled button dimmed its fill and kept its label at full strength (1.6:1) and never said
   it was disabled. Both together now, and the semantics say disabled.
7. The night palette's muted text was 3.3:1 on its own background; 4.9:1 now, still dimmer
   than day.
8. Every Themes row offered "Duplicate"/"Export"/"Edit"/"Delete" with no subject, and each
   Quick-controls icon repeated its row's title. The theme's name is in the label; a decorative
   icon is silent and an acting one says what it does ("Mute").

Open, by value:

9. Night mode dims the default palette by 7% (mean luminance 23.8 → 22.2). The icon flips,
   the screen does not. Either a real night variant or no toggle.
10. Suite apps show the Android status bar (clock, Wi-Fi, battery) that the launcher hides:
   every launch flashes system chrome in and out.
11. News: "Loading headlines…" stays under the title after the headlines are on screen.
12. Converter is the only suite app without its name on screen (`CATEGORY` is the first text).
13. Music: "1 tracks".
14. Music: the seek thumb is a 24 px dot on a 1920 px panel; the launcher's own bar was given
    a 32 dp slider for the same reason.

How to reproduce any of them: `headunit maestro 0 out/` (launcher) or
`MAESTRO_FLOWS=launcher/e2e/maestro/suite headunit maestro 0 out/` (suite), then the PNGs.
