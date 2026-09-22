# UI/UX findings from the first Maestro + accessibility pass (2026-09-22, farm instance 0)

Fixed in the same change:

1. Phone: status line read "No phone · No phone" (device name and HFP state both said it).
2. Phone: a blank " " label held the party line's height; a screen reader announced nothing.
3. Top bar → Power & sleep opened over the Settings hub: two Backs to Home.

Open, by value:

4. Home contradicts itself while the suite Radio plays: the media card shows "87.50 MHz FM1"
   with transport, the Radio tile beside it says "Radio idle. Tap to tune." One state, two
   readings (`suite-news.png`).
5. Contrast: white on the primary blue reads 3.4:1 (FM/AM band chip, Call button, profile
   chips); 4.5:1 is the bar for text that size. Phone's "Enter number" hint is 1.9:1.
6. Night mode dims the default palette by 7% (mean luminance 23.8 → 22.2). The icon flips,
   the screen does not. Either a real night variant or no toggle.
7. Suite apps show the Android status bar (clock, Wi-Fi, battery) that the launcher hides:
   every launch flashes system chrome in and out.
8. Themes: each row's Duplicate and Export buttons are announced identically; a screen reader
   cannot tell which theme.
9. News: "Loading headlines…" stays under the title after the headlines are on screen.
10. Converter is the only suite app without its name on screen (`CATEGORY` is the first text).
11. Music: "1 tracks".
12. Music: the seek thumb is a 24 px dot on a 1920 px panel; the launcher's own bar was given
    a 32 dp slider for the same reason.

How to reproduce any of them: `headunit maestro 0 out/` (launcher) or
`MAESTRO_FLOWS=launcher/e2e/maestro/suite headunit maestro 0 out/` (suite), then the PNGs.
