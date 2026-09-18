# Restoring the unit over EDL (after a slot that will not boot)

State this covers: the unit crashes at boot (`05c6:900e`), the RST pinhole brings it to EDL
(`05c6:9008`), a slot backup exists (`backup-slot.sh` output, sha256-verified), and the laptop
has `edl` (bkerler) with the unit's loader. Everything below is from the laptop.

    RST ──► 9008 ──► edl + loader (Sahara) ──► Firehose
                                               │ w boot_b / dtbo_b / vbmeta_b / vbmeta_system_b   (GPT partitions, direct)
                                               │ r super → lpdump → edl-extents.py → ws …          (logical partitions, by extent)
                                               └ reset ──► boots the restored slot

## One command

```
cd ~/rav4-headunit/edl
bash edl-restore.sh --backup ~/rav4-headunit/backup-20260915-093543_b --yes
```
`edl-restore.sh` (this directory, copied from `os/`) does steps 0–3 below in order, verifies the
backup's SHA256SUMS first, writes nothing until the GPT has been read, and logs to
`restore-<timestamp>/restore.log`. `--no-reset` to stop before the reboot, `--full-super` to
keep a full copy of the broken `super` first (6 GB, minutes). If it stops at "no GPT read", the
loader upload was refused: press RST and run it again. The steps by hand:

## 0. Loader

HWID `0x001750e1`, PK hash `d40eee56f3194665…` (Qualcomm's generic key): bkerler `Loaders/qualcomm/
model_generic/QCM6125/001750e100000000_d40eee56….bin`. The upload only works on a FRESH
enumeration: after any failed attempt press RST again before retrying.

```
cd ~/rav4-headunit/edl/src
E="../venv/bin/python edl.py --memory=ufs --loader=../prog_firehose_qcm6125.bin"
$E printgpt | tee ../gpt.txt            # note the byte Offset of `super`, and that boot_b etc. exist
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
$E rs <super sector> 4096 ../super-meta.img   # super Offset / 4096, then 16 MiB: what lpdump needs
lpdump ../super-meta.img > ../lpdump.txt
python3 edl-extents.py --lpdump ../lpdump.txt --super-offset <super Offset from gpt.txt, bytes> \
        --images $B --out ../restore-plan.sh
cat ../restore-plan.sh                   # read it: one dd + one ws per extent, sizes checked
bash ../restore-plan.sh
```
Two sector sizes meet here: lpdump counts 512-byte sectors from the start of `super`, `edl ws`
on UFS counts 4096-byte sectors from the start of the disk. `edl-extents.py` converts and refuses
an extent that does not start on a device sector (`test-edl-extents.sh` pins this). The backup
images are smaller than the resized partitions the failed flash left behind; the filesystem
size inside the image is what mounts, the extra extent space is ignored. If `edl-extents.py`
refuses (image larger than extents), rebuild super with `lpmake` instead using the geometry in
`lpdump.txt` — not needed for a backup restore.

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
