# Maestro flows: the launcher's user journeys on a farm instance

    headunit maestro [instance]        every flow here, JUnit + one PNG per step, into x:./maestro-out/

Maestro (mobile.dev, YAML over adb) reads Compose semantics: text and contentDescription.
Every top-bar icon carries a description (StatusIndicators.kt), so flows target by name;
what is unlabelled is tapped by panel percentage and noted as a finding.

Flows are journeys, not layout rules (test_layout_sweep.py owns those): a driver opens a
screen, does the one thing it is for, and comes back. A failed assertion is a bug; the PNGs
are for the eyes, the day/night pair for contrast.

## Screenshot baselines: shots.py

The PNGs are for the eyes until they are not. `shots.py` diffs a run's
`takeScreenshot` images against a blessed set, so layout drift turns a run red
instead of waiting to be noticed.

    shots.py accept maestro-out/2026-09-22_130732 e2e/maestro/baseline
    shots.py check  maestro-out/2026-09-22_131900 e2e/maestro/baseline

`check` prints one line per shot — `OK`, `DRIFT`, `NEW`, `MISSING` or `SIZE` —
with the percentage of pixels that moved, and exits 1 if anything drifted. A
shot the baseline has never seen is `NEW` and passes: that is a flow nobody has
accepted yet. A baseline with no shot is `MISSING` and fails: the flow that took
it stopped taking it. Baselines are flat files named `<flow-slug>__<shot>.png`,
so renaming a flow reads as `NEW` + `MISSING` rather than as a silent pass.

The status bar's clock changes between runs, so the clock's rectangle is cut out
of every compare by default (`CLOCK_MASK`, measured at 1920x720). `--mask
X,Y,W,H` replaces it, `--no-mask` compares the whole frame. `--tolerance`
(per channel, default 8) absorbs GL dither; `--threshold` (percent, default 0.2)
is how much of the frame may move before it counts. `--ignore REGEX` drops a
whole shot. The settings-hub shots settle at a different scroll offset on every
run and belong there until the flow pins its position.

No Pillow, no numpy: the PNG decoder is inside the tool. `test_shots.sh`
generates its own fixtures and runs in CI (launcher-ci, `build` job), so the
differ is covered without an emulator.

## Blessed screenshots

`baseline/` holds one PNG per flow screenshot, taken on farm instance 0 from a green 10/10 run
of the launcher flows. After a run:

    python3 shots.py check <run-dir> baseline      # 0 = no drift, 1 = something moved
    python3 shots.py accept <run-dir> baseline     # bless a run as the new truth

The clock is masked, so only the UI is judged. Flows never screenshot mid-scroll: a scroll
stops a few pixels off every run and the shot drifts ~10% with nothing wrong. Waits
are 20 s because a loaded farm host makes the emulator slow; a quiet host returns Home in
well under a second.

`baseline-suite/` is the same for the suite flows, minus three shots whose content is live:
News fetches real headlines, Music's now-playing bar advances, and Converter's input field
carries a caret. Check a suite run with those out:

    python3 shots.py check <run-dir> baseline-suite \
      --ignore 'suite-news' --ignore 'music-playing' --ignore 'suite-converter'

`run.sh` sets all three animation scales to 0 before a run: a screenshot taken during an entry
fade differs from the same screen a frame later, which cost the suite shots up to 41% drift
between runs of one build.

## In CI

`launcher-ci`'s `journeys` job runs these flows on every pull request that touches
`launcher/**`: the same 1920x720 @240dpi emulator the other two emulator jobs boot, the
`farm` APK (the flows name the release application id, which the debug build is not), and
`run.sh` verbatim. `.gitea/scripts/launcher-e2e.sh` is the whole procedure.

Nine of the ten run there. `05-apps` searches the grid for Calculator, which the farm has
and a bare `aosp_atd` image does not.

The screenshots stay the farm's job. CI's emulator renders host-side and reads every frame
back black, so a baseline of them would be green whatever the launcher drew. The job probes
the capture, says so in its log, and runs `shots.py check` against `baseline-ci/` the day an
image hands real pixels back.
