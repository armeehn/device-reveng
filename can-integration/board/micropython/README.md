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
| `test_board.py` | `python3 -m unittest -v` in this directory |

## Install

1. Flash MicroPython for the board (ESP32: `esptool.py --chip esp32 write_flash -z 0x1000
   ESP32_GENERIC-*.bin`).
2. `cp config.example.json config.json`, set the SSID, password and pins.
3. `mpremote cp board.py main.py config.json :` then `mpremote reset`.
4. `curl http://<board>:8765/state/bar` → `{"power": "off"}`.

Point the launcher's `accessory-config.json` base URL at the board and load it.

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
runner exists. `main.py` is not tested: it is 120 lines of glue that only runs on the chip.
