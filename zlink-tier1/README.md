# ZLink Tier-1 kit — "better CarPlay" without rebuilding the receiver

Goal (iPhone owner): keep ZLink's **licensed CarPlay engine** (real Apple CoreUtils + the
on-board MFi chip — see `../ZLINK_REVERSE_ENGINEERING.md`) and remove its liabilities:
vendor phone-home, Tencent telemetry, activation re-gating, and the flaky call/Siri mic path.

Everything here is **reversible** and designed for the Magisk-rooted GT6-CAR unit. Nothing here
touches Apple's protocol or the CarPlay stream — we only change what surrounds it.

> **`zlink5` is the CarPlay engine** — it runs as a native init service
> (`init.svc.zlink5: running`). Do **not** stop/disable it. We keep it running and only fence
> off its network + tune it.

## Apply order (all on-device, device online)

1. **`./01-observe.sh`** — run FIRST. Non-destructive. Captures the current activation state,
   all relevant props, and the live behavior, and does the **fail-open test**: temporarily
   blocks the vendor hosts and checks whether CarPlay still connects. This tells us whether the
   activation neutralization even needs the Frida step. Read its output before continuing.
2. **`net-block-zjinnova/`** — Magisk **systemless hosts** module: nulls the vendor phone-home
   (`*.zjinnova.com`, `zjinnova.net`) and Tencent Bugly/RQD telemetry. Install with
   `./install-netblock.sh` (copies to `/data/adb/modules/` and reboots). Disable = delete the
   module dir or toggle it off in Magisk.
3. **`zlink-activation.js`** — Frida script. Only needed if step 1 shows CarPlay **fails
   closed** when the vendor server is unreachable (i.e. it insists on re-checking activation).
   Run in recon mode first to learn the real return values, then flip `ENFORCE = true`.
4. **`zlink-tune.sh`** — reversible AEC/mic tuning for call/Siri echo. Captures a baseline to
   `baseline-props.txt` first; `./zlink-tune.sh restore` reverts.

## Files
| File | Purpose | Reversible |
|------|---------|------------|
| `01-observe.sh` | baseline capture + fail-open test (run first) | n/a (read-only) |
| `net-block-zjinnova/` | Magisk systemless-hosts module (phone-home + telemetry) | yes (remove module) |
| `install-netblock.sh` | install the module to `/data/adb/modules` | yes |
| `zlink-activation.js` | Frida recon/enforce for the activation JNI (insurance) | yes (don't run it) |
| `zlink-tune.sh` | AEC/APM mic tuning + `restore` | yes (baseline restore) |

## What we deliberately do NOT do
- Don't stop `zlink5` / hostapd — they run CarPlay + the wireless AP.
- Don't edit `/system` directly — all changes are systemless (Magisk) or `setprop`, so an OTA
  or module-disable cleanly reverts them.
- Don't touch the MFi chip or the AirPlay stream — the licensed path already works.

## The RF/reconnect fix (not a prop — do when online)
The disconnect/choppy issue is 5 GHz AP quality (see `../CARPLAY.md`). There is **no channel
prop**; the AP is `hostapd`. When online, `01-observe.sh` dumps the live hostapd config +
`iw`/`wpa` state so we can pin a clean 5 GHz channel (e.g. 149/44) and width in the hostapd
conf via a small systemless overlay. Deferred until we can read the running config.

## Baseline facts captured offline (from `recon/getprop.txt`)
- `init.svc.zlink5 = running` (pid 3556); `init.svc.hostapd = stopped` (starts on wireless
  session). BT identity `persist.zlink.bt.name = GT6-BT-4E1F`, `persist.zj.BTmac = 008761344D2A`.
- Feature lever: `rw.zlink.disable.features = abldyzqrce` (per-letter mode/feature disable —
  mapping unknown; `01-observe.sh` will probe it safely).
- AEC/mic baseline: `persist.blinkbt.carplay.aecdelay=1000`, `persist.blinkbt.aec.delay=1000`,
  `persist.zj.apm.delay=200`, `persist.zj.apm.micgain=1`, `persist.zj.apm.speakergain=2`,
  `persist.zj.apm.rnn=false`, `persist.zj.force_cp_mic_8k=false`.
- Phone-home/telemetry hosts confirmed in binaries: `www.zjinnova.com`, `com.zjinnova.net`,
  `url.zjinnova.com`, `bugly.qq.com`.
- Activation JNI (in `libzlink*`): `ZlinkCore.getChipActivationInfo`, `setActivationKey`,
  `setActivationResult`, `verifyMirrorFreeEnv`, `verifyHiCarFreeEnv` (note: **no CarPlay
  "free" method** — CarPlay is the activation-gated mode).
