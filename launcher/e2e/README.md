# Launcher end-to-end suite (ARTEMIS)

Natural-language UI tests for the CarLauncher, executed by
[google/artemis](https://github.com/google/artemis) on an emulated or real head unit.

```
pytest ──HTTP──► ARTEMIS host ──adb──► head unit
```

Each test states a goal in plain English plus the JSON it wants back. ARTEMIS
drives the panel, fills in the JSON from what it sees, and the test asserts on
that. Failures name a launcher fact ("first tap did not change the theme"), not
a coordinate.

## Run

```
ARTEMIS_URL=http://<artemis-host>:8000 \
ARTEMIS_DEVICE_SERIAL=emulator-5554 \
ARTEMIS_LAUNCHER_PACKAGE=com.ripostelabs.carlauncher.debug \
./run.sh                      # all cases
./run.sh -k theme             # one case
ARTEMIS_PROFILE=pro ./run.sh  # planning + checkpoint verification, slower
```

`run.sh` creates `.venv/` here on first use. The ARTEMIS host owns adb, the
models and the API keys; this side needs only Python 3.10+.

## Writing a case

- Start from the launcher home and end there, so cases are order-independent.
- Ask for few keys, boolean or short-string valued. "Is a Settings gear visible"
  is answered reliably; "describe the screen" is not.
- Keep `locked=True` (the default) so ARTEMIS stays inside the launcher package
  and reports if it lands elsewhere.
- Coordinates, if a goal must use them, are in the 1920x720 panel space.

Not part of CI: the suite needs a booted head unit and model credit.

## Car link cases (carsim)

`test_carsim.py` needs no model: it starts `carsim/carsim.py` (the simulated
vehicle on the emulator's virtio ports) and asserts over adb on what the
launcher logged and drew. It runs where adb and the QEMU sockets are local:

```
CARSIM_SERIAL=emulator-5554 CARSIM_MCU=127.0.0.1:5700 CARSIM_CAN=127.0.0.1:5701 \
python3 -m pytest -m carsim -v
```

Unset `CARSIM_SERIAL` skips the cases. See `carsim/README.md`.
