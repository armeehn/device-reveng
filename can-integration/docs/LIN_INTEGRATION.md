# RAV4 (XA50) climate LIN bus → launcher integration

How the **LIN climate path** fits alongside the existing CAN path in `carlib`
(`com.reveng.carlauncher.carlib`), and everything you need to bring the hardware up.

Ground truth: the bitzero.tech / ufnalski RAV4 XA50 climate reverse-engineering notes. **Passive
listen only** — nothing here ever transmits on the LIN bus.

---

## 1. Where this sits among the three car-data paths

The launcher now has **three independent readers** that all fold into one shared, framework-free
state model:

| Path | Source | Node | Decoder | Reader |
|------|--------|------|---------|--------|
| Raw powertrain CAN | CANable 2.0 (slcan) on the vehicle bus | `/dev/ttyACM0` (CDC) | `RawCanDecoder` → `RawCanSignal` | `CanableReader` → `StateFlow<VehicleState>` |
| Vendor digest | HiWorld CANBOX broadcast | (Android Intent) | `HiworldCanDecoder` → `CanSignal` | gateway receiver |
| **Climate LIN** | **A/C-amp ↔ panel LIN bus via LIN-transceiver→USB-UART** | **`/dev/ttyUSB0` (FTDI)** | **`LinClimateDecoder` → `LinSignal`** | **`LinReader` → `StateFlow<LinClimateState>`** |

The three deliberately do **not** share a signal type (`RawCanSignal` / `CanSignal` / `LinSignal`
coexist in one package). They converge at the *state* layer — see §5.

Files added by this work:
- `LinClimateDecoder.kt` — pure Kotlin, zero Android deps. Compiles + `selfTest()` **PASS** under
  JDK 17 (verified with the Gradle-bundled `kotlin-compiler-embeddable`).
- `LinReaderService.kt` — `LinReader` (plain class, `start`/`stop`), `LinClimateState`,
  `UnifiedCarState`, `combineWithVehicle(...)`. Compiles clean against `android.jar` (API 34) +
  `kotlinx-coroutines` + the real `RootShell`/`RootSession` and the CAN reader's `VehicleState`.
- `lin-probe.sh` — laptop/adb bring-up + passive sniff.

---

## 2. Transceiver wiring recap

LIN is a **single-wire** bus. You need a LIN transceiver (e.g. **TJA1020**, or the TJA1027/MCP2003
family) to translate the 12 V-swing LIN line to the 3.3/5 V UART levels of a plain USB-UART:

```
  RAV4 climate LIN wire ───────► TJA1020  LIN pin
  vehicle GND ─────────────────► TJA1020  GND  (common ground with the USB-UART — essential)
  +12V (acc/ign) ──────────────► TJA1020  VBAT/VSUP
  TJA1020 RXD ─────────────────► USB-UART RX      (we only read; TXD left unconnected = listen-only)
  TJA1020 EN/NSLP ─────────────► tie to enable the transceiver (per its datasheet)
  USB-UART ────USB────────────► head unit / laptop
```

Notes:
- **Common ground** between the car and the USB-UART is mandatory or you get garbage/no bytes.
- We physically do not wire TXD (or leave the driver disabled), so we *cannot* put a byte on the
  bus even by mistake — belt-and-suspenders with the software's read-only open.
- LIN has no separate H/L pair and no termination resistor to fuss over like CAN; the master node
  already carries the ~1 kΩ pull-up.

---

## 3. Wire format: 19200 8N1, sync on `0x55`, known lengths

A LIN frame on the wire is: **BREAK → sync `0x55` → PID → data bytes → checksum**, at
**19200 baud, 8N1**.

A *plain* USB-UART has no LIN hardware framing, so:
- The **BREAK** (dominant-longer-than-a-byte) usually surfaces as a spurious **`0x00`** (a framing
  error read as a null) right before the `0x55`. We just ignore it.
- Strategy (both `LinReader.parseFrames` and `lin-probe.sh`): **sync on `0x55`**, take the next byte
  as the **PID**, slice `frameDataLenFor(pid)` data bytes + **1 checksum byte**, verify, decode.
- A `0x55` inside data can cause a rare false sync; we only frame on a PID we have a length for, and
  the checksum gate makes a false sync self-correct within a frame or two.
- Partial frames straddling a `read()` are carried to the next read (`LinReader.carry`).

### PID vs raw 6-bit ID (parity)
A LIN PID = 6-bit frame ID (bits 0–5) + two parity bits:
`P0 = ID0^ID1^ID2^ID4` (bit 6), `P1 = ~(ID1^ID3^ID4^ID5)` (bit 7).

The RE notes quote the **observed post-sync byte**. Working the parity out (checked in
`selfTest()`):
- **`0xB1`** (status) = the PID for raw ID **`0x31`**, parity `0b10`. So `0xB1` really is the
  parity-carrying PID; the "frame ID" you'd write in a LIN description file is `0x31`.
- **`0x39`** (buttons) = the PID for raw ID **`0x39`**, whose parity bits both happen to be `0b00`,
  so the PID equals its own raw ID numerically. This one is genuinely ambiguous on paper — it looks
  like both a raw ID and a PID — *precisely because its parity is zero*.

We **match on the observed byte** (`PID_STATUS = 0xB1`, `PID_BUTTONS = 0x39`) but also expose the
stripped id via `rawId(pid)` and a parity check via `pidValid(pid)`.

### Checksum: classic vs enhanced (documented ambiguity)
Two LIN forms, both over a modulo-255 carry-folded sum, then one's-complemented:
- **enhanced** (LIN 2.x): sum of **PID + data** — the form the RE notes describe
  (`~(sum of ID + all data)`). `enhancedChecksum(pid, data)` — **primary**.
- **classic** (LIN 1.x): sum of **data only**. `classicChecksum(data)` — fallback.

`verifyChecksum(pid, data, checksum)` tries both and reports `ENHANCED` / `CLASSIC` / `NONE`.

> ⚠ **The bitzero example payload `80 0X 13 00 2C 2C 00 81` is the 8 data bytes only — it does NOT
> include the trailing checksum byte.** So *which variant the amplifier actually emits cannot be
> confirmed from the example alone*; it must be read off a live capture. `selfTest()` therefore
> verifies checksum *self-consistency* (a frame built with our own computed checksum round-trips),
> not ground truth. `LinReader` treats a checksum mismatch as "log it, don't fold garbage in", and
> never as a reason to blank the UI — so an unconfirmed variant can't break the display. **Confirm
> ENH vs CLS from a real status-frame checksum byte** (the probe prints which validated).

---

## 4. Device node: FTDI (`ttyUSB0`) vs CDC (`ttyACM*`)

- An **FTDI**-based USB-UART enumerates as **`/dev/ttyUSB0`**. Its 19200 baud is a **real divisor**
  and mandatory — set it with `stty`.
- A **CDC-ACM** USB-UART enumerates as **`/dev/ttyACM*`**. Its baud is virtual (the slave sets the
  real rate), but `raw -echo` still matters.
- **The CANable (CAN adapter) already owns `/dev/ttyACM0`**, so the LIN adapter is **most likely
  `/dev/ttyUSB0`** — hence `LinReader.DEFAULT_NODE = "/dev/ttyUSB0"`.

`LinReader.resolveNode()` tries `preferredNode`, then any `ttyUSB*`, then any `ttyACM*` that is **not
the CANable's `ttyACM0`** — so a CDC LIN dongle still works. Hardware is offline as of writing, so
this is a documented **best-guess**; override `preferredNode` once confirmed with `lin-probe.sh`'s
`dmesg | grep -iE 'ftdi|cdc|usbserial'` output.

### Port config — root `stty` (the pragmatic path)
This head unit is rooted (SELinux Permissive), so `LinReader.configurePort()` runs:

```
stty -F <node> 19200 cs8 -cstopb -parenb -icrnl -inpck -istrip raw -echo
```

(8N1 = `cs8 -cstopb -parenb`.) A JNI/termios path would drop the `stty` dependency but buys nothing
on a rooted unit. The node is made openable with `chmod 666 <node>` via `RootShell`; if the chmod is
refused we fall back to reading through a persistent root `stdbuf -o0 cat <node>` pipe — same pattern
as `CanableReader`.

---

## 5. Unified vehicle state (one stream, both buses)

`LinReader` decodes into an immutable **`LinClimateState`** (`fanSpeed`, `mode`, `driverTempRaw` +
`driverSetpointC`, `passengerTempRaw` + `passengerSetpointC`, `acOn`, `eco`, `illumination`,
`rearDefrost`, `sync`, `lastButton`, …) and exposes `StateFlow<LinClimateState>`, plus `connected`
and `frameCount`.

> Named `LinClimateState` to avoid colliding with the **existing** vendor-AIDL `ClimateState` in the
> same package (that one decodes the gateway's `getAirData()` byte frame; this one decodes the real
> LIN bus).

Two ways it merges into the CAN reader's `VehicleState`, mirroring its existing `applyHiworld()`
seam:

**(A) Preferred — a one-line field on `VehicleState` (in `CanableReaderService.kt`):**
```kotlin
data class VehicleState(
    …,
    val climate: LinClimateState? = null,          // ← add this field
) {
    …
    /** Merge seam for the LIN climate bus, mirroring applyHiworld(). */
    fun applyLin(sig: LinSignal): VehicleState =
        copy(climate = (climate ?: LinClimateState()).apply(sig, atMs))
}
```
With that field present, `LinReader` pushes straight into the **same** `MutableStateFlow<VehicleState>`
the CANable reader owns (`state.value = state.value.applyLin(sig)` in `LinReader.emit`), collapsing
the buses into one flow. (This edit was *not* applied here because `VehicleState`/`CanableReader`
were read-only references for this task.)

**(B) Standalone / compile-today seam — `combineWithVehicle(...)`:**
Until edit (A) lands, `LinReaderService.kt` ships `UnifiedCarState(vehicle, climate)` and
```kotlin
fun combineWithVehicle(
    vehicle: StateFlow<VehicleState>,
    climate: StateFlow<LinClimateState>,
    scope: CoroutineScope,
): StateFlow<UnifiedCarState>
```
which `combine`s the two independent flows + `stateIn`s them into **one** `StateFlow` the UI collects
— no edit to the CAN reader required. Wiring:
```kotlin
val can = CanableReader().apply { start(scope) }
val lin = LinReader().apply { start(scope) }
val unified = combineWithVehicle(can.state, lin.state, scope)   // collect this one flow
```

---

## 6. UNCONFIRMED temp scale + toggle-and-diff

`LinClimateDecoder.setpointGuessC(raw) = raw * 0.5` °C is a **best guess**: the example driver/
passenger byte `0x2C` (44) → **22.0 °C**, comfortably inside the RAV4 climate range (~16–30 °C). An
alternative seen on the vendor CANBOX `AIR_CONDITIONER` key used a **5..33** set-point range, so a
direct-code encoding is also possible. **The scale is UNCONFIRMED** — every decoded state keeps the
**raw byte** alongside the guess so the UI can show the raw value while this is pinned down. Several
status bit assignments (`eco` b0, `illumination`/`ac` b7, `rearDefrost`/`sync` b3) are likewise
best-guesses (cross-referenced to the 0x39 button codes where possible).

### Toggle-and-diff procedure (run `lin-probe.sh` while doing this)
Change **one** control on the physical climate panel and watch which byte moves:

| Control | Status frame `0xB1` | Button frame `0x39` |
|---------|---------------------|---------------------|
| Fan +/− | byte 1 (`fan 0..7`) | byte 1 = `0x3C`/`0x3D` |
| Mode | byte 2 low nibble (`1`=face … `9`=defrost) | byte 2 = `0x1C` |
| **Driver temp** | **byte 4 (raw)** | byte 4 = `0x0F`/`0x11` |
| Passenger temp | byte 5 (raw) | byte 5 = `0x8F`/`0x91` |
| A/C | byte 7 bit0 | byte 1 = `0x80` |
| Recirc | — | byte 6 = `0xC0` |

**To nail the temp scale:** set the panel to a known low temp (e.g. 16 °C) and read byte 4, then a
known high temp (e.g. 30 °C) and read byte 4 again. Two points give you °C-per-count and the offset;
replace `setpointGuessC` with the confirmed mapping. **While you're there, grab a real status-frame
checksum byte** and confirm ENH vs CLS (the probe's output labels each frame `[ENH cks]` / `[CLS cks]`
/ `[BAD cks]`).

### Bring-up quickstart
```bash
# Laptop, adapter plugged into the laptop:
./lin-probe.sh                       # discovers node, sets 19200 8N1, live-decodes 0xB1/0x39

# Head unit: capture on-device, decode on the laptop (device toybox lacks python/awk):
adb shell "su -c 'stty -F /dev/ttyUSB0 19200 cs8 -cstopb -parenb raw -echo; timeout 20 cat /dev/ttyUSB0'" > lin_raw.bin
./lin-probe.sh --decode-file lin_raw.bin
```
