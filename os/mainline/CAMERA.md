# Cameras on mainline

How the reverse and 360 cameras reach mainline Linux on this unit. Facts come from the
unit's live device tree and its stock kernel (2026-10-01 pull); nothing here has run yet.

    AHD camera ──► PR2000 / XS9922B ──CSI-2──► CSIPHY ─► CSID ─► ISPIF ─► VFE ─► /dev/videoN
                   (decoder, on CCI)            camss on mainline (SDM660 generation)

## The SoC side: camss

SM6125's camera blocks are the SDM660 generation that mainline `camss` already drives. The
stock kernel names the same versions SDM660's downstream kernel does: CSIPHY v3.5, CSID
v5.0, ISPIF v3.0, VFE 4.8. Mainline has no SM6125 entry; adding one is a resource table
and a DT node, not new hardware code.

| Block | Stock node | Registers | IRQ (SPI) |
|---|---|---|---|
| CSIPHY 0-2 | `qcom,csiphy-v3.5` | `0x1628000`, `0x1629000`, `0x162a000` (0x1000 each); clk mux `0x5c00120`+4n | 72, 73, 74 |
| CSID 0-3 | `qcom,csid-v5.0` | `0x5c30000` + 0x400 n | 208-211 |
| ISPIF | `qcom,ispif-v3.0` | `0x5c31000` (0xc00); csi clk mux `0x5c00020` | 212 |
| VFE 0-1 | `qcom,vfe48` | `0x5c10000`, `0x5c14000` (0x4000); VBIF `0x5c40000` | 214, 215 |
| CCI | `qcom,cci` | `0x5c0c000` (0x4000) | 207 |

The difference from SDM660: the clocks live in GCC, not a multimedia clock controller.
Mainline's `gcc-sm6125` has all of them (`GCC_CAMSS_*`, 78 entries) and the power domains
`CAMSS_TOP_GDSC`, `CAMSS_VFE0_GDSC`, `CAMSS_VFE1_GDSC`. The CCI is the v2 controller
mainline's `i2c-qcom-cci` supports. Stock clock rates: CSI 311 MHz, CSIPHY timer 200 MHz,
VFE 404/480/576 MHz, CCI 19.2/37.5 MHz.

## The unit side: two camera slots

| Slot | CSIPHY | CSID | CCI master |
|---|---|---|---|
| `qcom,camera@1` | 1 | 1 | 1 |
| `qcom,camera@2` | 2 | 2 | 0 |

The decoders are not on the general I2C buses; they sit behind CCI. The stock AIS camera
config (`/vendor/etc/camera/ais_camera_config.xml`, pulled 2026-10-02) maps them:

| Decoder | Slot | CCI bus | CSIPHY | Lanes | Inputs |
|---|---|---|---|---|---|
| XS9922B | `camera@2` | 0 (GPIO 37/38) | 2 | 2, `laneAssign 0x20` | 4 x 1920x1080 AHD |
| PR2000 | `camera@1` | 1 (GPIO 39/40) | 1 | 4, `laneAssign 0x4320` | 1 x 1280x720 (reverse) |

The stock AIS user-space drivers (`libais_pr2000.so`, `libais_xs9922b.so`) carry the rest:

| Decoder | CCI address (7-bit) | Chip ID | Reset |
|---|---|---|---|
| XS9922B | 0x30 | reg 0x40F0 = 0x9999 | |
| PR2000 | 0x5C | reg 0xFC (word) = 0x2000 | GPIO 115, active low: 50 ms high, 100 ms low, 50 ms high |

## The decoders

Both register sets are decoded from the stock kernel the unit runs, build #328 of
2025-03-08 (pull folder `20261002-1843`, `FINDINGS.md`). An older dump, build #64, differs
only in the PR2000 NTSC table:

- **XS9922B**: one 503-write init table, 16-bit registers, 8-bit values.
- **PR2000**: one ~204-write table per input mode (NTSC, PAL, PAL60, AHD 720p 25/30/60,
  AHD 1080p 25/30), 8-bit registers with page select `0xff`.

Each becomes a V4L2 subdevice driver: init on stream-on, the mode table chosen from the
detected signal, a fixed `MEDIA_BUS_FMT_UYVY8_1X16` output.

## Order of work

1. SM6125 in `camss` and the board DT: CSIPHY/CSID/ISPIF/VFE nodes, CCI. **Built
   2026-10-02** (`camss-sm6125.patch`, board DTS). Proof on the bench: `media-ctl -p`
   lists 3 CSIPHY, 4 CSID, ISPIF and 2 VFE entities, and `i2cdetect` sees the CCI buses.
   Assumption to check there: the `ahb` clock maps to `GCC_CAMSS_AHB_CLK_SRC` (SM6125 has
   no gated camss AHB clock in mainline's GCC).
2. Slot map from the unit: decoder per slot, lanes, CCI addresses, chip IDs **(done,
   tables above)**.
3. PR2000 driver (reverse camera first: one input, simplest table). **Built 2026-10-02**
   (`media-pr2000.patch`): reset, chip-ID check, AHD 720p/1080p at 25/30 fps from the
   stock tables, UYVY over 4 lanes at a 148.5 MHz link. Bench proof: `dmesg` shows
   "PR2000 at 0x5c"; then `media-ctl` links pr2000 -> csiphy1 -> csid1 -> ispif ->
   vfe0_rdi0 and `v4l2-ctl --stream-mmap` on that video node captures frames. The 4-lane
   `data-lanes <0 1 2 3>` mapping of stock `laneAssign 0x4320` is an assumption to check.
4. XS9922B driver (four AHD inputs, virtual channels). **Built 2026-10-03**
   (`media-xs9922b.patch`): chip ID 0x9999, the stock AIS start-stream sequence (pre +
   resolution + all-channel start, 1080p default, 720p), 2 lanes. The tables come from the
   user-space AIS driver, which is what streams on the stock unit; the kernel driver's own
   INIT0 table is a different, unused-at-stream configuration. Assumptions to check: the
   750 MHz link frequency (the PLL registers are not decoded) and the 2 ms per-write delay.
5. camss CSID 4.7 routes only virtual channel 0 (`vc = 0` in `camss-csid-4-7.c`), so on
   mainline the XS9922B shows camera 0 alone until CSID 4.7 maps VC 1..3 to RDI 1..3.
