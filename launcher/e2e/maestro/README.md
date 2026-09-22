# Maestro flows: the launcher's user journeys on a farm instance

    headunit maestro [instance]        every flow here, JUnit + one PNG per step, into x:./maestro-out/

Maestro (mobile.dev, YAML over adb) reads Compose semantics: text and contentDescription.
Every top-bar icon carries a description (StatusIndicators.kt), so flows target by name;
what is unlabelled is tapped by panel percentage and noted as a finding.

Flows are journeys, not layout rules (test_layout_sweep.py owns those): a driver opens a
screen, does the one thing it is for, and comes back. A failed assertion is a bug; the PNGs
are for the eyes, the day/night pair for contrast.
