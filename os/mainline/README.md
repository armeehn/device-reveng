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
| `camss-sm6125.patch` | SM6125 in mainline `camss`: SDM660's CSID/ISPIF/VFE tables, own CSIPHY table (per-PHY clock, no AHB2CRIF) |
| `pwm-lpg-pm6125.patch` | the PM6125's single LPG PWM channel (`qcom,pm6125-pwm`, base 0xb300) for the backlight |
| `gt6eau.config` | what arm64 `defconfig` leaves out for this board |
| `init` | busybox init: USB gadget (ACM + NCM), telnetd, dmesg on the panel |
| `mkboot_v2.py` | packs a header-v2 boot image with the stock image's addresses |
| `build.sh` | all of the above from a v6.18 tree; heavy, runs on the build server |
| `test-mkboot.sh` | the packer keeps the stock fields and page layout (CI) |

## Bench test

The bootloader is unlocked and boots images from RAM: `root.sh` started TWRP this way over
the 4-pin USB cable.

```
adb reboot bootloader
fastboot boot boot-mainline.img      # RAM only; a power cycle returns to the installed system
```

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

Audio, Wi-Fi, cameras (plan: `CAMERA.md`), GPU (no upstream SM6125 GPU node).

## Touch and the MCU link

Touch is the stock Goodix GT9xx on SE2 (`i2c2`, 0x14, irq GPIO 88, reset GPIO 87) under
mainline's `goodix` driver. It reports in the panel's portrait coordinates; a rotated
desktop may need `touchscreen-swapped-x-y` once the bench shows which way it is off.

The MCU link is SE5 as a 2-wire UART on GPIO 24/25 (`/dev/ttyHS1` on stock). Mainline only
describes SE5 as `i2c5`/`spi5`, so the board DTS adds the `geni-uart` node; the `serial1`
alias makes it `/dev/ttyMSM1`. From the bench shell: `stty -F /dev/ttyMSM1 115200 raw` and
`cat /dev/ttyMSM1 | od -An -tx1` should show the MCU's 10 Hz `0x8E` frames.
