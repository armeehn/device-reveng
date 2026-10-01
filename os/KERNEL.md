# The head unit's kernel

What runs under Riposte OS, what a newer Android needs from it, and what a replacement
kernel must carry. The plan this serves: `share/carlauncher/os/ANDROID-CEILING.md`.

## Stock kernel

Read from `share/carlauncher/os/base/boot.img` (sha256 `1d592152df6244fa…`), 2026-09-30.

| | |
|---|---|
| Version | `4.14.190-perf`, clang 10.0.7, built 2024-12-27 |
| Tree | Qualcomm CAF msm-4.14, `CONFIG_ARCH_TRINKET` (QCM6125) |
| Board | Fibocom module: DT model `TRINKET IOT IDP Overlay + FIBO SC820 EVK`, board-id `0x22 0x00` |
| Modules | 11 (`=m`). Everything the car needs is built in. |
| BPF | `CONFIG_BPF_SYSCALL`, `CGROUP_BPF`, `BPF_JIT` on. No 4.19/5.4 BPF backports. |
| Source | none on the estate |

Extracted copies sit in `share/carlauncher/os/kernel/`: the config, the 3 base DTBs and the
3 `dtbo` overlays as `.dts`, and the 481 source paths the kernel's strings name.

## Why it caps Android

AOSP's `NetBpfLoad` refuses to start on a kernel below its floor, and the network stack
stays down without it:

    Android   14 QPR1  14 QPR2  15     16    16 QPR2  17
    floor     4.14     4.19     4.19   5.4   5.10     5.10 (5.15 from 26Q4)
              ▲ 0.2 runs here

## What the car needs from any kernel

| Function | Stock driver | In a public SM6125 tree? |
|---|---|---|
| Panel 1920x720 | DSI → GM8775 MIPI-to-LVDS bridge, panel picked by ID from a ~50-entry table in the overlay (`gm8775_cheku`, `gm8775_auo`, `lt9211` …) | no |
| Alternate bridge | Lontium LT9211 (`lt9211d_mipi2lvds_init`) | no |
| Touch | Ilitek TDDI v3 (`drivers/input/touchscreen/TDDI9881x/ilitek_v3.c`), plus Himax, Goodix, FocalTech, Silead, Betterlife, Jadard probed | Ilitek/Goodix/FocalTech: generic versions yes |
| Reverse and 360 camera | XS9922B and PR2000 AHD decoders, char devices with ioctls, under `camera_v2` | no |
| Video serializer | THine THCV33x (`qcom,thcv33x`) | no |
| MCU link | `msm_geni_serial` HS UART, `/dev/ttyHS1` (`0x4c80000` or `0x4c90000`) | yes |
| USB-C | `staging/typec/fusb302` | yes |
| GPU, audio, Wi-Fi | Adreno 610 `kgsl`, WCD937x + WSA881x, WCN3990 | yes (CAF) |

## Vendor source

None is public (search of GitHub code, Gitee, XDA, 4PDA, 2026-09-30). The closest tree is
Qualcomm's CodeLinaro `msm-4.14` tag `LA.UM.8.11.1.r1-00600-QCM6125.0`, which carries no car
drivers. Fibocom ships its SDK only to business customers. Choiceway, the distributor, is the
party GPLv2 obliges to hand over the source on written request.

Public code for each chip, for a rebuilt or mainline kernel:

| Chip | Mainline | Elsewhere |
|---|---|---|
| LT9211 | yes, `lontium,lt9211` since 5.19 | LT9211C: patch v8 on dri-devel, 2026-09 |
| GM8775C | no | panel tables only (Sophgo `dsi_gm8775c.h`) |
| XS9922B | no | Kendryte `k230_sdk` `xs9922b_drv.c` |
| PR2000 | no | Ambarella out-of-tree `pr2000.c` |
| THCV33x | no | none found |
| Ilitek / Jadard / Betterlife touch | partial | many vendor copies |

A kernel built from LineageOS's `android_kernel_xiaomi_sm6125` (4.14.357, with the eBPF
backports Android 16 QPR2 needs) boots this SoC but loses the panel, the cameras and
probably touch. Those four drivers decide every path.

## Android 16 QPR2 on the stock kernel

The shortcut: keep this kernel and run a GSI whose `NetBpfLoad` warns instead of refusing.
crDroid 12.12 (Android 16 QPR2, 2026-09-22) carries the `Doze-off/fuck-bpf` patch set: patch
0010 turns every "requires kernel" refusal into a warning. Its author warns that networking
still fails on a kernel missing the BPF helper backports. Our config has every BPF and
network option that set asks for, except `NET_SCH_NETEM` and `XFRM_MIGRATE`. Whether the
helpers are there only shows on the car.

A test image built with `build.sh --profile gsi --bench --system crDroid-12.12-…VANILLA…`
sits in `share/carlauncher/os/0.3-candidate/` (`CANDIDATE.md` there says how to test it).

## Open questions for the next bench session

1. `cat /proc/cmdline` – which panel ID the bootloader passes.
2. `ls -l /sys/class/tty/ttyHS1` – which UART the MCU uses.
3. `ls /dev | grep -iE 'xs99|pr2000|thcv'` – the camera decoder device nodes.
