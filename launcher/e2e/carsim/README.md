# carsim — the vehicle side of the head unit, simulated

The vendor's Android cannot be emulated (Qualcomm SoC, arm64 vendor HALs,
platform-signed apps). Everything vehicle-side reaches Android through one
UART, so the desk rig emulates the **car** instead: `carsim.py` plays the MCU
and the HiWorld CAN box behind it, plus the raw body bus a CANable would tap.

```
carsim.py ──TCP──▶ qemu -chardev socket ──▶ virtserialport "carsim.mcu" ──▶ /dev/vportNpM ──▶ McuOwner
          ──TCP──▶ qemu -chardev socket ──▶ virtserialport "carsim.can" ──▶ /dev/vportNpM ──▶ SlcanLinkSource
```

## Why virtio ports and not `/dev/ttyS1`

The emulator pins `-serial null` on COM1 and appends `8250.nr_uarts=1` to the
kernel command line *after* any `-qemu` arguments, so the guest has exactly one
8250 (`/dev/ttyS0`, wired to nothing) and a second one cannot be added. A QEMU
`virtconsole` gives a tty (`/dev/hvcN`) but its driver resets termios on last
close, so a `stty` run by another process is gone before the port is opened.
A `virtserialport` has no line discipline at all: raw bytes, one opener
(EBUSY on a second open), EOF while no host is attached. That is what
`CharDevLink` in carlib handles, selected by `riposte.mcu.link=dev:/dev/vportNpM`.

## Link selection

| property              | value                          | opens            |
|-----------------------|--------------------------------|------------------|
| `riposte.mcu.link`    | unset                          | `/dev/ttyHS1` @ 115200 (the car) |
|                       | `tty:/dev/ttyS1[@baud]`        | `TtyLink` (stty; `@0` leaves the speed) |
|                       | `dev:/dev/vport8p2`            | `CharDevLink`    |
|                       | `tcp:10.0.2.2:5700`            | `TcpLink`        |
| `riposte.canbus.link` | unset                          | none (the car's bus is the USB CANable) |
|                       | same grammar                   | `SlcanLinkSource`|

`ro.riposte.os.car_owner=1` must also be set, and eventcenter absent, or
`McuOwner` refuses the port (`AndroidOwnerGate`).

## Scenarios

`smoke` (handshake + one of everything), `commute` (a whole drive: engine
start, door, two climate setpoints, D, 0→60→0 on both carriers, dusk, reverse
into the spot, P, key off), `radio` (parked, tuner armed: the launcher's `01 01`
starts the `73` reports at 96.3 MHz "CBC R1", `02 <key>` seeks/steps/switches
band, `0C` tunes directly), `replay-door-cycle` (a candump capture on the bus;
the ticker's own bus frames pause while it plays, the capture is the bus).
`carsim.py --help` prints the timeline grammar; a text file with the same
lines is a scenario too.

The parked-only gate reads speed from the **raw bus only** (`CarEvents.CAN_SPEED_TRUSTED`
is false for the MCU digest), so `speed`/`ramp` events feed `0x361` on the bus
channel at 10 Hz. Without `--can`, replays convert the ids `RawCanDecoder`
knows (0x361 speed, 0x3BC gear, 0x4A5 doors) into HiWorld relay frames, which
the launcher shows but does not gate on.

With `--can`, `CanSide` also plays the engine ECU: an `obd` event sets what it
answers to the launcher's `0x7DF` service 01 requests (coolant, load,
throttle), which `SlcanLinkSource` sends at 2 Hz while frames are arriving.

## What it cannot fake

- The CANable path itself (USB CDC-ACM): no USB on the emulator, hence the second carrier.
- A camera: reverse shows the "no camera" verdict, which is the gate firing.
- ACC sleep: the vendor polls `sys.gotoSleep.state`, which nothing here writes.
- Audio, amplifier, backlight: `08`/`2E` frames are logged, nothing moves.
