# Mainline Linux on the GT6-EAU

The long path past Android 16: a current LTS kernel (6.18) instead of the vendor's 4.14
(`../KERNEL.md`). Step one is a boot image that runs from RAM and touches nothing on the
unit, so a failed boot costs a reboot, not a flash.

    ABL ──► mainline 6.18 + sm6125-choiceway-gt6eau.dtb ──► initramfs /init
                                                            ├─ panel: console on the splash buffer
                                                            └─ USB:  ttyACM shell + usb0 172.16.42.1

| File | What |
|---|---|
| `sm6125-choiceway-gt6eau.dts` | the board, from mainline's Xiaomi ginkgo (same SoC, same PMIC rails): QCM6125 msm-id, the stock board IDs, the bootloader's splash buffer as a `simple-framebuffer`, USB in device mode, and the panel on DSI0 |
| `panel-s6d7aa0-yuntang.patch` | the unit's panel as a variant of mainline's Samsung S6D7AA0 driver: init and timings decoded from the unit's own device tree (`yuntang,s6d7aa0-720x1920`) |
| `media-pr2000.patch`, `pr2000-tables.py`, `pr2000/` | the reverse camera's decoder: V4L2 driver, and its mode tables as decoded from the stock kernel (`test-pr2000-tables.sh` keeps the generated header honest) |
| `media-xs9922b.patch`, `xs9922b-tables.py`, `xs9922b/` | the 360 cameras' decoder: V4L2 driver, and the stock AIS start-stream tables (`test-xs9922b-tables.sh`) |
| `camss-vc-per-rdi.patch` | SM6125 only: each RDI line reads its own CSI-2 virtual channel (VFE0 RDI0-2 = VC 0-2, VFE1 RDI0 = VC 3), so the four XS9922B cameras are four video nodes |
| `camss-sm6125.patch` | SM6125 in mainline `camss`: SDM660's CSID/ISPIF/VFE tables, own CSIPHY table (per-PHY clock, no AHB2CRIF) |
| `pwm-lpg-pm6125.patch` | the PM6125's single LPG PWM channel (`qcom,pm6125-pwm`, base 0xb300) for the backlight |
| `gt6eau.config` | what arm64 `defconfig` leaves out for this board |
| `pdmapper-sm6125.patch` | SM6125 in the kernel's protection-domain mapper, with SM6115's domain list: the modem starts its WLAN domain only once a mapper answers |
| `firmware.sh` | stages the modem, audio DSP, Wi-Fi and Bluetooth firmware from the unit's own `modem` and `bluetooth` partition dumps into a `/lib/firmware` tree with sums (the blobs stay out of the repo) |
| `test-firmware.sh` | `firmware.sh` against fake partitions: paths, names, sums, and a refusal when `wlanmdsp.mbn` or `adsp.mdt` is missing (CI) |
| `init` | busybox init: USB gadget (ACM + NCM), telnetd, dmesg on the panel |
| `mkboot_v2.py` | packs a header-v2 boot image with the stock image's addresses |
| `build.sh` | all of the above from a v6.18 tree, plus `modules/`, `Image`, the DTB and `kernel-build-id` for Riposte OS's `gt6` rootfs; heavy, runs on the build server |
| `test-mkboot.sh` | the packer keeps the stock fields and page layout (CI) |

## Bench test

The bootloader is unlocked and boots images from RAM: `root.sh` started TWRP this way over
the 4-pin USB cable.

```
adb reboot bootloader
fastboot boot boot-mainline.img      # RAM only; a power cycle returns to the installed system
```

On this unit's pigtail a `fastboot boot` transfer can stall; enter the bootloader fresh
(`adb reboot bootloader`), `usbreset 18d1:d00d`, then one `fastboot boot`. A transfer cut off
mid-way leaves the bootloader wedged until RST. The bootloader only boots this DTB when it
matches the board exactly (msm-id/board-id in the DTS); otherwise it applies the unit's own
dtbo overlay, fails and returns to fastboot. A failed test boot reboots itself after 90 s.

Pass: the panel shows kernel messages, then the laptop sees `18d1:4ee7`
(`lsusb`), `screen /dev/ttyACM0` gives a shell, `dmesg` there lists what probed.

## The panel

The unit's panel is "yuntang", a 720x1920 MIPI DSI panel on a Samsung S6D7AA0-family
controller, run rotated to landscape; there is no bridge chip (the stock kernel's GM8775 and
LT9211C probes fail on this unit). The init (32 commands), reset timing and the three enable
GPIOs come from the live device tree pulled off the unit on 2026-10-01. The vendor driver
replaces the node's 768x1024 timings with 720x1920 at boot; the porches are the node's.
The backlight is `pwm-backlight` on the PM6125's LPG channel (stock: `qcom,pwms@b300` on
SPMI USID 1, 100 us period, 12-bit levels), enabled by GPIO 97. Mainline's LPG driver had
no PM6125 entry; `pwm-lpg-pm6125.patch` adds it. It starts at half brightness:
`echo N > /sys/class/backlight/backlight/brightness` (0-4095) from the bench shell.

The splash buffer stays as the first console: if the DSI path fails, the panel should still
show the bootloader's picture with kernel text over it.

## What is not there yet

GPU (no upstream SM6125 GPU node) and Bluetooth call audio (Audio, below). Wi-Fi, Bluetooth, speaker audio and the microphones are described but unproven until a bench boot (below); the microphone links ship switched off. Cameras: `CAMERA.md`.

## USB: device or host

The USB controller (dwc3) is dual-role. It boots as a **device**, so the bench laptop stays the
host: `fastboot boot`, adb on Android, the boot gadget's ACM shell and NCM link. Riposte OS
(Linux) switches it to **host** for Android Auto, where the phone on the cable is the device:

```
cat /sys/class/usb_role/*-role-switch/role          # device | host | none
echo host   > /sys/class/usb_role/*-role-switch/role
echo device > /sys/class/usb_role/*-role-switch/role   # back, e.g. when Android Auto exits
```

There is no connector or extcon node: Linux sees no ID pin on the unit's socket, so nothing
switches by itself. Unknown until the bench: whether the car's USB sockets get VBUS from the
SoC side in host mode (stock powers phones for CarPlay/Android Auto through the same port, but
the switch that does it is not described here).

## Touch and the MCU link

Touch is the stock Goodix GT9xx on SE2 (`i2c2`, 0x14, irq GPIO 88, reset GPIO 87) under
mainline's `goodix` driver. It reports in the panel's portrait coordinates; a rotated
desktop may need `touchscreen-swapped-x-y` once the bench shows which way it is off.

The MCU link is SE5 as a 2-wire UART on GPIO 24/25 (`/dev/ttyHS1` on stock). Mainline only
describes SE5 as `i2c5`/`spi5`, so the board DTS adds the `geni-uart` node; the `serial1`
alias makes it `/dev/ttyMSM1`. From the bench shell: `stty -F /dev/ttyMSM1 115200 raw` and
`cat /dev/ttyMSM1 | od -An -tx1` should show the MCU's 10 Hz `0x8E` frames.

## Wi-Fi and Bluetooth

The unit carries a Qualcomm WCN3990-family combo chip: WLAN on the SoC's SNOC bus, run by
firmware inside the modem DSP, and Bluetooth on a UART. Evidence, all from the stock system:

- the live device tree: `bt_wcn3990` (`qca,wcn3990`), `qcom,icnss@C800000` with SPIs 358-369,
  SMMU stream 0x80 and the fixed MSA region at 0x53300000, a `wcn3990` BTFM slave on SLIMbus
- vendor properties: `vendor.qcom.bluetooth.soc=cherokee`, the HAL's name for the WCN399x line
- the HAL (`android.hardware.bluetooth@1.0-impl-qti.so`) loads from `/bt_firmware/image/`, the
  `bluetooth` partition; `/vendor/firmware/wlanmdsp.mbn` is a link into the modem's files
- stock dmesg: `ttyHS0` is SE9 (0x4c90000), the one 4-wire UART left after the MCU (`ttyHS1`)

| Block | Stock | Board DTS |
|---|---|---|
| modem | `qcom,mss@6080000`, PAS id 4, wdog SPI 307, SMP2P SMEM 435/428 | `remoteproc_mpss` (`qcom,sm6115-mpss-pas`: same PAS id, crash SMEM 421, SSCTL 0x12) |
| modem file system | `qcom,rmtfs_sharedmem`, 2 MiB allocated at boot, client 1 | `rmtfs_mem`, dynamic, needs `rmtfs` in the rootfs |
| WLAN | `qcom,icnss` | `wifi@c800000` (`qcom,wcn3990-wifi`, ath10k_snoc) |
| Bluetooth | `bt_wcn3990` on `ttyHS0` | `bt_uart` (SE9, GPIO 10-13) + `qcom,wcn3990-bt` |
| rails | L8 cx-mx, L9 BT io, L16 xo, L17 rfa/core, L23 ch0/pa | the same, through the RPM regulators |

The bindings come from SM6115 (Qualcomm RB2): same modem resources and the same five rails.
Defconfig already builds every driver as a module (`QCOM_Q6V5_PAS`, `QCOM_RMTFS_MEM`,
`QRTR_SMD`, `QCOM_PD_MAPPER`, `ATH10K_SNOC`, `BT_HCIUART_QCA`), so `gt6eau.config` gains nothing.

### Firmware

`firmware.sh OUT [LINUX_FIRMWARE]` stages what the kernel asks for:

| File | From |
|---|---|
| `qcom/sm6125/choiceway/gt6eau/modem.mdt`, `modem.bNN` | `modem` partition, `image/` |
| `qcom/sm6125/choiceway/gt6eau/wlanmdsp.mbn`, `*.jsn` | `modem` partition, `image/` (served to the modem by `tqftpserv`) |
| `ath10k/WCN3990/hw1.0/board.bin` | `modem` partition, `image/bdwlan.bin` |
| `ath10k/WCN3990/hw1.0/firmware-5.bin` | linux-firmware |
| `qca/crbtfw*.tlv`, `crnv*.bin` (or `apbtfw*`, `apnv*`) | `bluetooth` partition, `image/` |

Neither partition has been dumped yet: the stock OTA carries only the A/B system images, and
the share's base dump stops at the default set. One car visit fixes that:
`os/dump-base.sh --parts "modem bluetooth"`, then `assemble`. The script writes
`SHA256SUMS-gt6eau` next to the files; record those sums here after the first run.

Userspace on the rootfs: `rmtfs` (modem file system, from the `modemst1`/`modemst2`/`fsg`
partitions), `tqftpserv`, `qrtr` tools. The modem is not auto-booted: start it with
`echo start > /sys/class/remoteproc/remoteprocN/state` once those run.

### Unverified until the bench boot

- WCN3990 or WCN3988: the stock tree says `qca,wcn3990` for the whole line, and the HAL carries
  both firmware sets. `btqca` prints the SoC version at probe; WCN3988 means the BT node
  becomes `qcom,wcn3988-bt` and the `ap*` files load.
- that SM6115's protection-domain list fits this modem (`modemr.jsn` from the dump says)
- that the dynamic `rmtfs_mem` and `qcom,msa-fixed-perm` match what the modem expects
- that `bdwlan.bin` is the right board file name in this modem image, and that ath10k takes it
  as `board.bin` when linux-firmware's `board-2.bin` has no entry for the chip's board id
- the BT UART pins (stock pulls copied), baud 3.2 Mbit/s, and no enable GPIO (stock has none)
- BT call audio: the stock path is SLIMbus to the ADSP (BTFM), not described here (Audio, below)

## Audio

Sound leaves the SoC as I2S on the secondary MI2S port and goes to the car's audio chain, which
the MCU owns: it switches sources and sets the amp volume (`05 05 v`). Evidence, all stock:

- the live device tree: card `qcom,sm6150-asoc-snd`, model `trinket-idp-snd-card` (Qualcomm's
  reference IDP card, kept almost as is); `qcom,msm-mi2s-master` set for every port, secondary
  MI2S with one RX line and no TX; its pins `sec_i2s_*` on GPIO 125 (SCK), 126 (WS), 127 (SD0),
  128 (SD1), and no MCLK pin
- the HAL: `audio_platform_info_intcodec.xml` maps `SND_DEVICE_OUT_SPEAKER` to `SEC_MI2S_RX`,
  24 bits; the vendor's own lines in `mixer_paths_idp.xml` add `SEC_MI2S_RX Audio Mixer
  MultiMediaN` to every playback path and set `SEC_MI2S_RX Format` to `S24_LE`
- no audio chip on the SoC's I2C buses (the stock `i2c-devices` list has panels, bridges and
  touch only), so the receiver is driven by the MCU, not by Linux
- stock dmesg: the ADSP loads `adsp.mdt` + `adsp.b02`-`b27` from `/vendor/firmware_mnt/image`
  (the `modem` partition, ueventd's only firmware directory), then the card registers

The SoC's own codec is there too: a WCD937x on SoundWire (both slaves bind at boot) behind the
LPASS RX and TX macros (stock `qcom,va-without-decimation`: no VA macro). Its four WSA881x speaker amps are in the tree but absent on the board
(all four probes fail). The HAL also routes playback to the WCD937x headphone outputs, and the
vendor patched its driver to drive a PA enable on GPIO 131 with them. Whether those outputs
reach anything in the car is unknown; the speaker device says I2S.

| Path | Stock | Mainline 6.18 |
|---|---|---|
| ADSP | `qcom,lpass@ab00000` PIL, PAS id 1, SMEM 423, SSCTL 0x14, wdog SPI 396 | `remoteproc_adsp` (`qcom,sm6115-adsp-pas`, the same ids), APR over glink |
| speaker | MultiMedia → `SEC_MI2S_RX` (I2S, SoC master) → car amp | q6asm MultiMedia1-3 → q6routing → q6afe `SECONDARY_MI2S_RX`, SD0 |
| microphone (CarPlay, voice) | WCD937x ADC1 + ADC2 (`INP3`) → TX macro → `TX_CDC_DMA_TX_3` | described, links off: `wcd9370-codec` + `sm8250-lpass-rx/tx-macro` + `soundwire-v1.5.1` → q6afe `TX_CODEC_DMA_TX_3` → MultiMedia4 |
| Bluetooth calls | WCN399x over SLIMbus (BTFM slave, `SLIMBUS_7`) ↔ `SEC_MI2S_RX` | not described: no WCN399x SLIMbus codec, no q6afe `SLIMBUS_7` (below) |
| FM / tuner | MCU side (the `SLIM_8` FM path in the HAL is the WCN's and unused) | n/a |

The card is SM6115's (`qcom,qrb4210-rb2-sndcard`): Qualcomm's RB2 drives a bridge from the
same port. The receiver is a `linux,spdif-dit` stand-in, since nothing about it is controllable
from the SoC. Mainline's machine driver fixes the back end at 48 kHz, 2 channels, S16_LE,
BCLK 1.536 MHz (32 fs); stock sent 24-bit samples. The PEARL array, if it arrives over USB, needs
nothing here (`snd-usb-audio`).

The ADSP starts by itself once `adsp.mdt` is in `/lib/firmware/qcom/sm6125/choiceway/gt6eau/`
(`firmware.sh` takes it from the `modem` dump the Wi-Fi already needs; no extra partition). APR
waits for the `msm/adsp/audio_pd` domain, which `pdmapper-sm6125.patch` answers.

Proposed for Riposte OS's `gt6` rootfs (`/etc/asound.conf`), once the card shows up as `GT6EAU`:

```
# Route the first stream to the car amp's I2S line, once per boot:
#   amixer -c GT6EAU cset name='SEC_MI2S_RX Audio Mixer MultiMedia1' 1
pcm.!default {
    type plug
    slave { pcm "hw:GT6EAU,0" rate 48000 channels 2 format S16_LE }
}
ctl.!default { type hw card GT6EAU }
```

Volume stays on the MCU (`riposte-mcud`): the card has no gain of its own, and the amp is
silent until the MCU selects the head unit as its source (stock: `3F` on every stream start).

### Microphones

Mainline 6.18 has every driver the stock mic path uses; only the device tree was missing:

- WCD937x: `sound/soc/codecs/wcd937x.c` and `wcd937x-sdw.c` (`qcom,wcd9370-codec`)
- macros: `lpass-tx-macro.c` and `lpass-rx-macro.c` match `qcom,sm8250-lpass-*-macro`;
  6.18 also has `qcom,sm6115-lpass-tx-macro`, and the SM6115 RX and VA compatibles follow in
  6.19 (sound tree commits `8ff3dcb0e8a8` and `893e2fd509e9`)
- SoundWire: `drivers/soundwire/qcom.c` matches v1.3.0 to v2.0.0 and reads the block's own
  version register at probe
- the ADSP side: q6afe `TX_CODEC_DMA_TX_3` and its q6routing mixers

This SoC's LPASS is laid out like SM8250's, not SM6115's: the TX macro owns the TX SoundWire
master and there is no VA macro. So the board DTS takes the sm8250 match data, with the stock
addresses, interrupts, clock ids and pins. The macros' `fsgen` input (SM8250's VA macro) is
an always-on stand-in, since each macro here gates its own MCLK.

The capture links (`mm4-dai-link`, `mic-dai-link`) ship disabled. The card waits for every
link's components, so a macro that fails to probe would silence the speaker too. At the bench,
check that `wcd937x` binds both slaves, set both links to `okay`, add the `AMIC` to `MIC BIAS`
routes noted in the DTS, then:

```
amixer -c GT6EAU cset name='MultiMedia4 Mixer TX_CODEC_DMA_TX_3' 1
amixer -c GT6EAU cset name='TX_AIF1_CAP Mixer DEC0' 1
amixer -c GT6EAU cset name='TX DEC0 MUX' 'SWR_MIC'
amixer -c GT6EAU cset name='TX SMIC MUX0' 'ADC0'   # TX SWR_ADC0 = WCD ADC1 (stock route)
amixer -c GT6EAU cset name='ADC1 Switch' 1
arecord -D hw:GT6EAU,3 -f S16_LE -r 48000 -c 1 -d 5 mic.wav
```

The control names are the mainline drivers'. Stock's mixer paths name the same blocks, but the
exact ADC and mux values are a bench question.

### Bluetooth call audio

Not possible on 6.18 without new driver code. Stock carries SCO between the WCN399x and the
ADSP over a second SLIMbus (`slim@a340000`, stock `qcom,btfmslim_slave`, elemental address
`00 01 20 02 17 02`: manufacturer 0x217, product 0x220). Mainline lacks three pieces:

- a SLIMbus codec driver for the WCN399x: nothing in `sound/soc/codecs`. A draft exists
  (sdm845-mainline merge request 99, `wcn3990.c`, unmerged and unfinished)
- q6afe `SLIMBUS_7`: q6afe-dai ports stop at `SLIMBUS_6_TX` (`q6afe-dai.c` line 408)
- the NGD controller's version for this SoC: `qcom-ngd-ctrl.c` knows v1.5.0 and v2.1.0, and
  the stock node gives no version

SCO over HCI would sidestep all three, with BlueZ and PipeWire doing the audio in software.
Mainline does not mark the WCN3990 as wideband-capable (`hci_qca.c`, `qca_soc_data_wcn3990`
has no capabilities; the WCN3991 does). It does not show where the controller sends SCO,
either: the vendor NVM decides, and stock routes it to SLIMbus. The sdm845 phones that use the
same chip went the SLIMbus way, which suggests the vendor NVMs do not offer HCI.

Driver work, in order:

| Piece | Files | Size |
|---|---|---|
| q6afe `SLIMBUS_7_RX/TX` (AFE ports 0x400e/0x400f) | `qcom,q6dsp-lpass-ports.h`, `q6afe.c`, `q6dsp-lpass-ports.c`, `q6afe-dai.c` | about 60 lines, half a day |
| NGD on SM6125: stock reg 0xa340000 (0x2c000) + BAM 0xa304000 (0x20000), SPI 403/404, SMMU SID 0x73 | DTS, maybe an offset table in `qcom-ngd-ctrl.c` | 1 day once the version register is read |
| WCN399x SLIMbus codec: BT SCO RX/TX DAIs on `slim217,220`, ports from Qualcomm's `btfm_slim_wcn3990.c` | new `sound/soc/codecs/wcn3990.c` | 1 to 2 weeks with bench access |
| card links SLIMBUS_7 ↔ MultiMedia, a session manager that routes call audio | DTS, PipeWire "hardwired SCO" setup | days |

Until then, the fallback is USB. A USB Bluetooth adapter (btusb) carries SCO over HCI, and
PipeWire's native HFP backend handles calls with it. A USB microphone, or the PEARL array over
USB, needs only `snd-usb-audio`.

### Unverified until the bench boot

- that the ADSP boots under `qcom,sm6115-adsp-pas` with only the CX vote (stock proxy-votes
  `vdd_cx`; SM6125's RPM has no LPI domains), and that SID 0x41 is the audio stream
- that the receiver locks to 16-bit samples in a 32 fs frame; if it wants 64 fs or 24 bits, the
  machine driver's back-end fixup needs a GT6 case
- what the receiver is: scope GPIO 125-127 during playback, then follow SD0 on the board
- whether GPIO 131 (the vendor's PA enable) or the WCD937x headphone outputs feed the car at all
- that the macros run with SM8250's match data (bolero register maps of one generation), and
  that the macros need nothing from the absent VA block
- that `wcd937x` binds both slaves at 6.18's port maps, and what powers the mic bias (stock
  names no supply; Linux uses a dummy)
- which input the car's mic is on: stock enables ADC1 (AMIC1) and ADC2 with `INP3` (AMIC3)
- where the WCN3990 sends SCO with the unit's NVM: run an HFP call and watch `btmon` for SCO
  data on the UART. If it shows, SCO over HCI works and the SLIMbus work above is moot

