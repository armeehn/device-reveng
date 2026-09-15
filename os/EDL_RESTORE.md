# Restoring the unit over EDL (after a slot that will not boot)

State this covers: the unit crashes at boot (`05c6:900e`), the RST pinhole brings it to EDL
(`05c6:9008`), a slot backup exists (`backup-slot.sh` output, sha256-verified), and the laptop
has `edl` (bkerler) with the unit's loader. Everything below is from the laptop.

    RST ──► 9008 ──► edl + loader (Sahara) ──► Firehose
                                               │ w boot_b / dtbo_b / vbmeta_b / vbmeta_system_b   (GPT partitions, direct)
                                               │ r super → lpdump → edl-extents.py → ws …          (logical partitions, by extent)
                                               └ reset ──► boots the restored slot

## 0. Loader

HWID `0x001750e1`, PK hash `d40eee56f3194665…` (Qualcomm's generic key): bkerler `Loaders/qualcomm/
model_generic/QCM6125/001750e100000000_d40eee56….bin`. The upload only works on a FRESH
enumeration: after any failed attempt press RST again before retrying.

```
cd ~/rav4-headunit/edl/src
E="../venv/bin/python edl.py --memory=ufs --loader=../prog_firehose_qcm6125.bin"
$E printgpt | tee ../gpt.txt            # note the LUN and first sector of `super`, and of boot_b etc.
```

## 1. Physical partitions, straight from the backup

```
B=~/rav4-headunit/backup-20260915-093543_b
for p in boot dtbo vbmeta vbmeta_system; do $E w ${p}_b $B/$p.img; done
```
(`--lun N` if printgpt shows them on a LUN other than the default.) `abl`/`xbl` were not changed;
leave them.

## 2. Logical partitions, into super's existing extents

```
$E r super ../super-current.img          # ~6 GB, minutes; keep it, it is a second backup of the broken state
lpdump ../super-current.img > ../lpdump.txt
python3 edl-extents.py --lpdump ../lpdump.txt --super-sector <first sector of super from gpt.txt> \
        --images $B --out ../restore-plan.sh
cat ../restore-plan.sh                   # read it: one dd + one ws per extent, sizes checked
bash ../restore-plan.sh
```
The backup images are smaller than the resized partitions the failed flash left behind; the
filesystem size inside the image is what mounts, the extra extent space is ignored. If
`edl-extents.py` refuses (image larger than extents), rebuild super with `lpmake` instead using
the geometry in `lpdump.txt` — not needed for a backup restore.

## 3. Reset and watch

```
$E reset
```
Expect the stock boot (Choiceway logo, then CarLauncher on slot b). If it crashes again, the
physical partitions are the suspects (repeat step 1 with the backup's `abl`/`xbl` too, since
the OTA never replaced them).

## Then: 0.1 attempt two

Only after `kernel-mount-probe.sh` passes on the booted unit, with images from `lib.sh` at
commit `71224dd` or later (vendor ext4 feature set), copied to the laptop over the home LAN,
and with `flash.sh` (in place, `-S 64M`).
