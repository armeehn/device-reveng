# Accessory board firmware (MicroPython)

The real board for `docs/ACCESSORY_BOARD.md`: lights and servos on the car's private network,
switched from the launcher. MicroPython, so there is no toolchain: flash the stock image, copy
three files, done.

```
launcher ──WiFi (mobile-gate)──▶ ESP32 :8765 ──GPIO──▶ MOSFET ──▶ light bar
                                        ├───PWM───▶ MOSFET ──▶ spot (dimmable)
                                        └───PWM───▶ servo signal (antenna)
```

## Files

| File | What |
|---|---|
| `board.py` | the contract: paths, state JSON, refusals. No hardware; tested under CPython |
| `main.py` | WiFi, pins, the socket server. MicroPython only |
| `config.example.json` | copy to `config.json`, edit |
| `serve.py` | the firmware's brain on a desk: `board.py` behind a CPython socket, pins replaced by print |
| `test_board.py` | the contract, `python3 -m unittest -v` in this directory |
| `test_wire.py` | `serve.py` and `tools/virtual-board.py` answer one request script identically; a `--lie` board must differ |

## Install

1. Flash MicroPython for the board (ESP32: `esptool.py --chip esp32 write_flash -z 0x1000
   ESP32_GENERIC-*.bin`).
2. `cp config.example.json config.json`, set the SSID, password and pins.
3. `mpremote cp board.py main.py config.json :` then `mpremote reset`.
4. `curl http://<board>:8765/state/bar` → `{"power": "off"}`.

Point the launcher's `accessory-config.json` base URL at the board and load it.

No board yet? `python3 serve.py --port 8766 bar:switch spot:level antenna:servo` on any host runs the
same code path with print in place of pins, so the launcher can be driven against the real
firmware logic before the ESP32 arrives.

## Kinds

| kind | pin does | config |
|---|---|---|
| `switch` | GPIO high for on (`"invert": true` for low-side drivers) | `pin` |
| `level` | PWM duty 0..100 %, duty 0 when off | `pin`, `freq` (default 1000 Hz) |
| `servo` | 50 Hz pulse `min_us`..`max_us` across 0..100, signal released when off | `pin`, `min_us`, `max_us` |

The board owns the mapping and the limits. A subclass of `board.Output` that cannot reach a
position must return the position it holds: the launcher reads that as refused and stops the
sequence, which is the intended behaviour, not a failure.

## Wiring

- Never drive a lamp from a GPIO. A logic-level MOSFET (IRLZ44N or a module) between the pin and
  the load, load on the car's 12 V through its own fuse.
- Servo power from a 5 V buck converter, not the board's 3.3 V; common ground with the board.
- The board never touches the vehicle bus. Nothing here is wired to the head unit.

## Tests

`board.py` is plain Python and the whole contract lives in it, so it is tested where a test
runner exists: `python3 -m unittest -v` here, and in `launcher-ci`.

`sh check-mpy.sh` runs the same three things the chip would, under a real MicroPython: `main.py`
through `mpy-cross`, `board.py` imported by the unix port, and the contract tests under it with
micropython-lib's `unittest`. Verified 2026-09-09 on MicroPython v1.25.0 (unix port):
12 tests pass. Not yet run on a chip; the pins are the one thing the desk cannot check.
