# Mainline Linux on the GT6-EAU

The long path past Android 16: a current LTS kernel (6.18) instead of the vendor's 4.14
(`../KERNEL.md`). Step one is a boot image that runs from RAM and touches nothing on the
unit, so a failed boot costs a reboot, not a flash.

    ABL ──► mainline 6.18 + sm6125-choiceway-gt6eau.dtb ──► initramfs /init
                                                            ├─ panel: console on the splash buffer
                                                            └─ USB:  ttyACM shell + usb0 172.16.42.1

| File | What |
|---|---|
| `sm6125-choiceway-gt6eau.dts` | the board, from mainline's Xiaomi ginkgo (same SoC, same PMIC rails): QCM6125 msm-id, the stock board IDs, the bootloader's 1920x720 splash buffer as a `simple-framebuffer`, USB in device mode |
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

## What is not there yet

Display beyond the splash buffer (GM8775C bridge has no driver), touch, audio, Wi-Fi, the
MCU UART node, GPU (no upstream SM6125 GPU node). The splash buffer is an assumption:
ABL must leave the panel lit and the format must be `a8r8g8b8`. A black panel with a
working USB gadget means the assumption is wrong, not the kernel.
